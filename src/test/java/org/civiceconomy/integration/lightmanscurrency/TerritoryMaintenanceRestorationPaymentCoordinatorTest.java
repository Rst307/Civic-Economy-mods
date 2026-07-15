package org.civiceconomy.integration.lightmanscurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.PrepareTerritoryMaintenanceRestoration;
import org.civiceconomy.territory.TerritoryFiscalServiceProvisioner;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationQuote;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationRegistry;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryMaintenanceRestorationPaymentCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE = TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY;
    private static final NationId NATION_ID = new NationId(
            UUID.fromString("36c41415-4838-4799-91b5-3a4e8b229ebe"));
    private static final UUID TEAM_ID =
            UUID.fromString("e3560065-5e0b-4a22-ac83-5efc245e27a9");
    private static final UUID ACTOR_ID =
            UUID.fromString("a4937cd1-69bf-4c2e-876a-765012ca118f");
    private static final UUID POLICY_ID =
            UUID.fromString("adcbde93-c12b-4247-a41a-715d92829b40");
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID.value() + ":treasury");
    private static final AccountId PUBLIC_FUND =
            LightmansCurrencyPublicMaintenanceFundProvisioner.ACCOUNT_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void authorizedRestorationMovesExactPolicySplitOnceAndReplayCannotDoubleCharge() {
        try (CivicDatabase database = database()) {
            suspendedAssessment(database);
            NationFiscalAuthorityRegistry authorities = authorities(database);
            authorities.grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("governance"),
                    "grant-restoration",
                    NATION_ID,
                    ACTOR_ID,
                    ACTOR_ID,
                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                    "Authorize Restoration"));
            Map<AccountId, Long> balances = new HashMap<>();
            balances.put(TREASURY, 130L);
            balances.put(PUBLIC_FUND, 0L);
            Set<UUID> appliedPayments = new HashSet<>();
            Set<UUID> appliedDestructions = new HashSet<>();
            TerritoryMaintenanceRestorationPaymentCoordinator coordinator = coordinator(
                    database, authorities, balances, appliedPayments, appliedDestructions);

            var first = coordinator.restore(request("authorized-restoration"));
            var replay = coordinator.restore(request("authorized-restoration"));

            assertEquals(
                    TerritoryMaintenanceRestorationState.CIVIC_COMMITTED,
                    first.restoration().state());
            assertEquals(first.restoration(), replay.restoration());
            assertEquals(MoneyAmount.ofMinorUnits(52L), first.publicFundAmount());
            assertEquals(MoneyAmount.ofMinorUnits(78L), first.destroyedAmount());
            assertEquals(0L, balances.get(TREASURY));
            assertEquals(52L, balances.get(PUBLIC_FUND));
            assertEquals(1, appliedPayments.size());
            assertEquals(1, appliedDestructions.size());
            assertEquals(52L, database.cumulativeNetIssuanceMinorUnits());
        }
    }

    @Test
    void missingNationPermissionFailsBeforeReservationOrExternalMoneyMovement() {
        try (CivicDatabase database = database()) {
            suspendedAssessment(database);
            NationFiscalAuthorityRegistry authorities = authorities(database);
            Map<AccountId, Long> balances = new HashMap<>();
            balances.put(TREASURY, 130L);
            balances.put(PUBLIC_FUND, 0L);
            TerritoryMaintenanceRestorationPaymentCoordinator coordinator = coordinator(
                    database, authorities, balances, new HashSet<>(), new HashSet<>());

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.restore(request("unauthorized-restoration")));

            assertEquals(130L, balances.get(TREASURY));
            assertEquals(0L, balances.get(PUBLIC_FUND));
            assertEquals(0L, database.activeReservedMinorUnits(TREASURY.value()));
            assertEquals(
                    Optional.empty(),
                    new TerritoryMaintenanceRestorationRegistry(database, CLOCK)
                            .find(SERVICE, "unauthorized-restoration"));
        }
    }

    private static TerritoryMaintenanceRestorationPaymentCoordinator coordinator(
            CivicDatabase database,
            NationFiscalAuthorityRegistry authorities,
            Map<AccountId, Long> balances,
            Set<UUID> appliedPayments,
            Set<UUID> appliedDestructions) {
        new TerritoryFiscalServiceProvisioner(new FiscalAuthorization(database))
                .ensureAuthorized(TREASURY);
        var session = FiscalTestSessions.open(database, SERVICE, "civiceconomy");
        if (database.cumulativeNetIssuanceMinorUnits() == 0L) {
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    SERVICE.value(),
                    "seed-restoration-issuance",
                    "ISSUANCE",
                    130L,
                    "mint-batch:restoration-payment-test",
                    "Seed Restoration payment test issuance",
                    NOW.toEpochMilli(),
                    1_000L);
        }
        return new TerritoryMaintenanceRestorationPaymentCoordinator(
                new NationRegistry(database, teams()),
                authorities,
                FiscalLedger.authorized(
                        database,
                        account -> MoneyAmount.ofMinorUnits(
                                balances.getOrDefault(account, 0L)),
                        session),
                PaymentCoordinator.authorized(
                        database,
                        payment -> {
                            if (appliedPayments.add(payment.transactionId())) {
                                balances.compute(
                                        payment.sourceAccount(),
                                        (ignored, balance) -> Math.subtractExact(
                                                balance, payment.amount().minorUnits()));
                                balances.merge(
                                        payment.recipientAccount(),
                                        payment.amount().minorUnits(),
                                        Math::addExact);
                            }
                        },
                        session),
                PermanentDestructionCoordinator.authorized(
                        database,
                        destruction -> {
                            if (appliedDestructions.add(destruction.destructionId())) {
                                balances.compute(
                                        destruction.sourceAccount(),
                                        (ignored, balance) -> Math.subtractExact(
                                                balance, destruction.amount().minorUnits()));
                            }
                        },
                        session,
                        CLOCK),
                new TerritoryMaintenanceRestorationRegistry(database, CLOCK));
    }

    private static NationFiscalAuthorityRegistry authorities(CivicDatabase database) {
        return new NationFiscalAuthorityRegistry(database, provider(), CLOCK);
    }

    private static NationProvider provider() {
        NationFacts facts = new NationFacts(NATION_ID, ACTOR_ID, Set.of(ACTOR_ID));
        return new NationProvider() {
            @Override
            public Optional<NationFacts> find(NationId nationId) {
                return NATION_ID.equals(nationId) ? Optional.of(facts) : Optional.empty();
            }

            @Override
            public Optional<NationFacts> findForCitizen(UUID playerId) {
                return ACTOR_ID.equals(playerId) ? Optional.of(facts) : Optional.empty();
            }
        };
    }

    private static NationTeamDirectory teams() {
        NationTeam team = new NationTeam(TEAM_ID, ACTOR_ID, Set.of(ACTOR_ID));
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return TEAM_ID.equals(teamId) ? Optional.of(team) : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return ACTOR_ID.equals(playerId) ? Optional.of(team) : Optional.empty();
            }
        };
    }

    private static PrepareTerritoryMaintenanceRestoration request(String requestId) {
        return new PrepareTerritoryMaintenanceRestoration(
                SERVICE,
                requestId,
                NATION_ID,
                TEAM_ID,
                ACTOR_ID,
                "minecraft:overworld",
                8,
                9,
                POLICY_ID,
                NOW.plus(Duration.ofDays(7)),
                new TerritoryMaintenanceRestorationQuote(
                        MoneyAmount.ofMinorUnits(130L), NOW.plus(Duration.ofDays(14))),
                "Authorized out-of-Cycle Restoration");
    }

    private static void suspendedAssessment(CivicDatabase database) {
        long cycleStart = NOW.minus(Duration.ofDays(7)).toEpochMilli();
        database.registerNation(
                NATION_ID.value(), "restoration-payment-test", "register", TEAM_ID, cycleStart);
        database.scheduleTerritoryMaintenancePolicy(
                POLICY_ID,
                SERVICE.value(),
                "policy",
                "operator:test",
                Duration.ofDays(7).toMillis(),
                100L,
                15_000,
                20L,
                30L,
                Duration.ofDays(14).toMillis(),
                6_000,
                NOW.minus(Duration.ofDays(21)).toEpochMilli(),
                "Restoration payment test policy",
                cycleStart);
        UUID cycleId = UUID.randomUUID();
        database.openTerritoryMaintenanceCycle(
                cycleId,
                SERVICE.value(),
                "cycle",
                cycleStart,
                NOW.toEpochMilli(),
                cycleStart);
        database.assessTerritoryFiscalValidity(
                UUID.randomUUID(),
                SERVICE.value(),
                "assess-suspended",
                cycleId,
                NATION_ID.value(),
                TEAM_ID,
                "minecraft:overworld",
                8,
                9,
                100L,
                TerritoryMaintenancePriority.CAPITAL.name(),
                "Unfunded capital",
                cycleStart);
        database.suspendTerritoryMaintenance(
                UUID.randomUUID(),
                SERVICE.value(),
                "suspend",
                cycleId,
                NATION_ID.value(),
                "Insufficient maintenance balance",
                NOW.toEpochMilli());
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-restoration-payment.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("f0ab57a2-e80e-4fae-94e6-6992c6bc9bcf"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
