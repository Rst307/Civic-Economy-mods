package org.civiceconomy.integration.lightmanscurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.ChargeTerritoryMaintenance;
import org.civiceconomy.territory.SettleAvailableTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryFiscalServiceProvisioner;
import org.civiceconomy.territory.TerritoryFiscalValidity;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.civiceconomy.territory.TerritoryMaintenanceSettlementOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryMaintenancePaymentCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("b3f976e8-282a-43fd-8db4-92afc683ee4f"));
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID.value() + ":treasury");
    private static final AccountId PUBLIC_FUND =
            LightmansCurrencyPublicMaintenanceFundProvisioner.ACCOUNT_ID;
    private static final UUID TEAM_ID =
            UUID.fromString("7e4d20de-b4ae-4086-a966-07e4cfe1c622");
    private static final UUID CYCLE_ID =
            UUID.fromString("4d2c544b-0241-47cf-a617-ae65c542c6f0");

    @TempDir Path temporaryDirectory;

    @Test
    void settlesOnlyTheAffordablePriorityPrefixWithoutCallerSuppliedAssessments() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 100L);
        balances.put(PUBLIC_FUND, 0L);
        Set<UUID> appliedPayments = new HashSet<>();
        Set<UUID> appliedDestructions = new HashSet<>();
        UUID capitalAssessmentId = UUID.fromString("1830d235-fb82-4722-a6da-6dc35435683c");
        UUID ordinaryAssessmentId = UUID.fromString("7661402b-827c-4693-8465-5559b18eb9f5");

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "maintenance-test", "register-nation", TEAM_ID, NOW.toEpochMilli());
            database.openTerritoryMaintenanceCycle(
                    CYCLE_ID,
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "open-maintenance-cycle",
                    NOW.toEpochMilli(),
                    NOW.plusSeconds(3_600L).toEpochMilli(),
                    NOW.toEpochMilli());
            assess(database, capitalAssessmentId, "capital", 0, 0, 100L, TerritoryMaintenancePriority.CAPITAL);
            assess(database, ordinaryAssessmentId, "ordinary", 1, 0, 50L, TerritoryMaintenancePriority.ORDINARY);
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "seed-partial-maintenance-issuance",
                    "ISSUANCE",
                    100L,
                    "mint-batch:partial-maintenance",
                    "Seed partial maintenance issuance",
                    NOW.toEpochMilli(),
                    2_000L);
            TerritoryMaintenancePaymentCoordinator coordinator = coordinator(
                    database,
                    balances,
                    appliedPayments,
                    appliedDestructions,
                    new AtomicBoolean(false));

            var result = coordinator.settleAvailable(new SettleAvailableTerritoryMaintenance(
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                    "partial-maintenance",
                    CYCLE_ID,
                    NATION_ID,
                    TREASURY,
                    6_000,
                    "Automatic partial maintenance"));

            assertEquals(TerritoryMaintenanceSettlementOutcome.PARTIALLY_FUNDED, result.settlement().outcome());
            assertEquals(Set.of(capitalAssessmentId), Set.copyOf(result.settlement().fundedAssessmentIds()));
            assertEquals(Set.of(ordinaryAssessmentId), Set.copyOf(result.settlement().suspendedAssessmentIds()));
            assertEquals(
                    MoneyAmount.ofMinorUnits(100L),
                    result.payment().orElseThrow().reservation().amount());
            assertEquals(0L, balances.get(TREASURY));
            assertEquals(40L, balances.get(PUBLIC_FUND));

            var replay = coordinator.settleAvailable(new SettleAvailableTerritoryMaintenance(
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                    "partial-maintenance",
                    CYCLE_ID,
                    NATION_ID,
                    TREASURY,
                    6_000,
                    "Automatic partial maintenance"));

            assertEquals(result.settlement(), replay.settlement());
            assertEquals(
                    result.payment().orElseThrow().reservation().reservationId(),
                    replay.payment().orElseThrow().reservation().reservationId());
            assertEquals(
                    result.payment().orElseThrow().publicFundPayment().transactionId(),
                    replay.payment().orElseThrow().publicFundPayment().transactionId());
            assertEquals(0L, balances.get(TREASURY));
            assertEquals(40L, balances.get(PUBLIC_FUND));
        }
    }

    @Test
    void keepsZeroCostAssessmentsEffectiveWithoutInventingFiscalEvidence() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 0L);
        balances.put(PUBLIC_FUND, 0L);
        UUID zeroCostCapital = UUID.fromString("9a078d86-f179-4211-bf59-a7358bea2257");
        UUID paidOrdinary = UUID.fromString("ca99775d-aed2-4c45-9a7f-c573f6f71c7a");

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "maintenance-test", "register-nation", TEAM_ID, NOW.toEpochMilli());
            database.openTerritoryMaintenanceCycle(
                    CYCLE_ID,
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "open-maintenance-cycle",
                    NOW.toEpochMilli(),
                    NOW.plusSeconds(3_600L).toEpochMilli(),
                    NOW.toEpochMilli());
            assess(database, zeroCostCapital, "zero-cost-capital", 0, 0, 0L, TerritoryMaintenancePriority.CAPITAL);
            assess(database, paidOrdinary, "paid-ordinary", 1, 0, 50L, TerritoryMaintenancePriority.ORDINARY);
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "seed-zero-cost-test-issuance",
                    "ISSUANCE",
                    1L,
                    "mint-batch:zero-cost-test",
                    "Keep the coordinator fixture from adding another Assessment",
                    NOW.toEpochMilli(),
                    2_000L);
            TerritoryMaintenancePaymentCoordinator coordinator = coordinator(
                    database,
                    balances,
                    new HashSet<>(),
                    new HashSet<>(),
                    new AtomicBoolean(false));

            var result = coordinator.settleAvailable(new SettleAvailableTerritoryMaintenance(
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                    "zero-cost-maintenance",
                    CYCLE_ID,
                    NATION_ID,
                    TREASURY,
                    6_000,
                    "Automatic zero-cost maintenance"));

            assertEquals(TerritoryMaintenanceSettlementOutcome.PARTIALLY_FUNDED, result.settlement().outcome());
            assertEquals(Set.of(zeroCostCapital), Set.copyOf(result.settlement().fundedAssessmentIds()));
            assertEquals(Set.of(paidOrdinary), Set.copyOf(result.settlement().suspendedAssessmentIds()));
            assertTrue(result.payment().isEmpty());
            assertEquals(0L, database.activeReservedMinorUnits(TREASURY.value()));
            assertEquals(0L, balances.get(TREASURY));
            assertEquals(0L, balances.get(PUBLIC_FUND));
        }
    }

    @Test
    void recordsUnfundedWithoutMovingLcWhenCapitalIsUnaffordable() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 99L);
        balances.put(PUBLIC_FUND, 0L);
        UUID capitalAssessmentId = UUID.fromString("845a334c-8a13-4346-80a7-53fe7110e11f");

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "maintenance-test", "register-nation", TEAM_ID, NOW.toEpochMilli());
            database.openTerritoryMaintenanceCycle(
                    CYCLE_ID,
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "open-maintenance-cycle",
                    NOW.toEpochMilli(),
                    NOW.plusSeconds(3_600L).toEpochMilli(),
                    NOW.toEpochMilli());
            assess(database, capitalAssessmentId, "capital", 0, 0, 100L, TerritoryMaintenancePriority.CAPITAL);
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "seed-unfunded-test-issuance",
                    "ISSUANCE",
                    1L,
                    "mint-batch:unfunded-test",
                    "Keep the coordinator fixture isolated",
                    NOW.toEpochMilli(),
                    2_000L);
            TerritoryMaintenancePaymentCoordinator coordinator = coordinator(
                    database,
                    balances,
                    new HashSet<>(),
                    new HashSet<>(),
                    new AtomicBoolean(false));

            var result = coordinator.settleAvailable(new SettleAvailableTerritoryMaintenance(
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                    "unfunded-maintenance",
                    CYCLE_ID,
                    NATION_ID,
                    TREASURY,
                    6_000,
                    "Automatic unfunded maintenance"));

            assertEquals(TerritoryMaintenanceSettlementOutcome.UNFUNDED, result.settlement().outcome());
            assertEquals(Set.of(), Set.copyOf(result.settlement().fundedAssessmentIds()));
            assertEquals(Set.of(capitalAssessmentId), Set.copyOf(result.settlement().suspendedAssessmentIds()));
            assertTrue(result.payment().isEmpty());
            assertEquals(99L, balances.get(TREASURY));
            assertEquals(0L, balances.get(PUBLIC_FUND));
            assertEquals(0L, database.activeReservedMinorUnits(TREASURY.value()));
        }
    }

    @Test
    void crashAfterDestructionRecoversExactConservativeSplit() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1_000L);
        balances.put(PUBLIC_FUND, 0L);
        Set<UUID> appliedPayments = new HashSet<>();
        Set<UUID> appliedDestructions = new HashSet<>();
        AtomicBoolean crashAfterDestruction = new AtomicBoolean(true);
        ChargeTerritoryMaintenance request = new ChargeTerritoryMaintenance(
                TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                "maintenance-cycle-1",
                CYCLE_ID,
                NATION_ID,
                TREASURY,
                MoneyAmount.ofMinorUnits(101L),
                6_000,
                "Territory maintenance cycle 1");

        try (CivicDatabase database = database()) {
            TerritoryMaintenancePaymentCoordinator coordinator = coordinator(
                    database,
                    balances,
                    appliedPayments,
                    appliedDestructions,
                    crashAfterDestruction);

            assertThrows(IllegalStateException.class, () -> coordinator.charge(request));
            assertEquals(899L, balances.get(TREASURY));
            assertEquals(41L, balances.get(PUBLIC_FUND));
            assertEquals(1_000L, database.cumulativeNetIssuanceMinorUnits());
        }

        try (CivicDatabase database = database()) {
            TerritoryMaintenancePaymentCoordinator recovery = coordinator(
                    database,
                    balances,
                    appliedPayments,
                    appliedDestructions,
                    new AtomicBoolean(false));
            recovery.recoverPermanentDestructions();
            var payment = recovery.charge(request);

            assertEquals(MoneyAmount.ofMinorUnits(41L), payment.publicFundAmount());
            assertEquals(MoneyAmount.ofMinorUnits(60L), payment.destroyedAmount());
            assertEquals(
                    TerritoryMaintenanceSettlementOutcome.FULLY_FUNDED,
                    payment.settlement().outcome());
            assertEquals(899L, balances.get(TREASURY));
            assertEquals(41L, balances.get(PUBLIC_FUND));
            assertEquals(940L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(0L, database.activeReservedMinorUnits(TREASURY.value()));
        }
    }

    @Test
    void zeroRoundedDestructionSettlesEntireMinorUnitIntoPublicFund() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1L);
        balances.put(PUBLIC_FUND, 0L);
        Set<UUID> appliedPayments = new HashSet<>();
        Set<UUID> appliedDestructions = new HashSet<>();
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "maintenance-test", "register-nation", TEAM_ID, NOW.toEpochMilli());
            database.openTerritoryMaintenanceCycle(
                    CYCLE_ID,
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "open-maintenance-cycle",
                    NOW.toEpochMilli(),
                    NOW.plusSeconds(3_600L).toEpochMilli(),
                    NOW.toEpochMilli());
            database.assessTerritoryFiscalValidity(
                    UUID.randomUUID(),
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "assess-maintenance-due",
                    CYCLE_ID,
                    NATION_ID.value(),
                    TEAM_ID,
                    "minecraft:overworld",
                    10,
                    20,
                    1L,
                    org.civiceconomy.territory.TerritoryMaintenancePriority.ORDINARY.name(),
                    "One-minor-unit assessment",
                    NOW.toEpochMilli());
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "seed-maintenance-issuance",
                    "ISSUANCE",
                    1L,
                    "mint-batch:one-unit-maintenance",
                    "Seed one-unit issuance",
                    NOW.toEpochMilli(),
                    2_000L);
            TerritoryMaintenancePaymentCoordinator coordinator = coordinator(
                    database,
                    balances,
                    appliedPayments,
                    appliedDestructions,
                    new AtomicBoolean(false));

            var payment = coordinator.charge(new ChargeTerritoryMaintenance(
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                    "one-unit-maintenance",
                    CYCLE_ID,
                    NATION_ID,
                    TREASURY,
                    MoneyAmount.ofMinorUnits(1L),
                    3_000,
                    "One-unit maintenance"));

            assertEquals(MoneyAmount.ZERO, payment.destroyedAmount());
            assertEquals(MoneyAmount.ofMinorUnits(1L), payment.publicFundAmount());
            assertEquals(
                    TerritoryMaintenanceSettlementOutcome.FULLY_FUNDED,
                    payment.settlement().outcome());
            assertEquals(0L, balances.get(TREASURY));
            assertEquals(1L, balances.get(PUBLIC_FUND));
            assertEquals(1L, database.cumulativeNetIssuanceMinorUnits());
        }
    }

    private TerritoryMaintenancePaymentCoordinator coordinator(
            CivicDatabase database,
            Map<AccountId, Long> balances,
            Set<UUID> appliedPayments,
            Set<UUID> appliedDestructions,
            AtomicBoolean crashAfterDestruction) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new TerritoryFiscalServiceProvisioner(authorization).ensureAuthorized(TREASURY);
        FiscalServiceSession session = FiscalTestSessions.open(
                database,
                TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                "civiceconomy");
        if (database.cumulativeNetIssuanceMinorUnits() == 0L) {
            database.registerNation(
                    NATION_ID.value(), "maintenance-test", "register-nation", TEAM_ID, NOW.toEpochMilli());
            database.openTerritoryMaintenanceCycle(
                    CYCLE_ID,
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "open-maintenance-cycle",
                    NOW.toEpochMilli(),
                    NOW.plusSeconds(3_600L).toEpochMilli(),
                    NOW.toEpochMilli());
            database.assessTerritoryFiscalValidity(
                    UUID.randomUUID(),
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "assess-maintenance-due",
                    CYCLE_ID,
                    NATION_ID.value(),
                    TEAM_ID,
                    "minecraft:overworld",
                    10,
                    20,
                    101L,
                    org.civiceconomy.territory.TerritoryMaintenancePriority.ORDINARY.name(),
                    "Maintenance payment test assessment",
                    NOW.toEpochMilli());
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "seed-maintenance-issuance",
                    "ISSUANCE",
                    1_000L,
                    "mint-batch:maintenance-test",
                    "Seed maintenance test issuance",
                    NOW.toEpochMilli(),
                    2_000L);
        }
        FiscalLedger ledger = FiscalLedger.authorized(
                database,
                account -> MoneyAmount.ofMinorUnits(balances.getOrDefault(account, 0L)),
                session);
        PaymentCoordinator payments = PaymentCoordinator.authorized(
                database,
                payment -> applyPayment(payment, balances, appliedPayments),
                session);
        PermanentDestructionCoordinator destructions =
                PermanentDestructionCoordinator.authorized(
                        database,
                        destruction -> {
                            if (appliedDestructions.add(destruction.destructionId())) {
                                balances.compute(
                                        destruction.sourceAccount(),
                                        (ignored, balance) -> Math.subtractExact(
                                                balance,
                                                destruction.amount().minorUnits()));
                                if (crashAfterDestruction.getAndSet(false)) {
                                    throw new IllegalStateException("simulated process death");
                                }
                            }
                        },
                        session,
                        CLOCK);
        return new TerritoryMaintenancePaymentCoordinator(
                database,
                ledger,
                payments,
                destructions,
                new TerritoryMaintenanceRegistry(database, CLOCK));
    }

    private static void applyPayment(
            ExternalPayment payment,
            Map<AccountId, Long> balances,
            Set<UUID> appliedPayments) {
        if (!appliedPayments.add(payment.transactionId())) {
            return;
        }
        balances.compute(
                payment.sourceAccount(),
                (ignored, balance) -> Math.subtractExact(balance, payment.amount().minorUnits()));
        balances.merge(
                payment.recipientAccount(), payment.amount().minorUnits(), Math::addExact);
    }

    private static void assess(
            CivicDatabase database,
            UUID assessmentId,
            String requestId,
            int chunkX,
            int chunkZ,
            long due,
            TerritoryMaintenancePriority priority) {
        database.assessTerritoryFiscalValidity(
                assessmentId,
                TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                requestId,
                CYCLE_ID,
                NATION_ID.value(),
                TEAM_ID,
                "minecraft:overworld",
                chunkX,
                chunkZ,
                due,
                priority.name(),
                "Automatic settlement test assessment",
                NOW.toEpochMilli());
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-maintenance-payment.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("b5ce29b6-d9ce-48c8-b3d7-24a1b38a0387"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
