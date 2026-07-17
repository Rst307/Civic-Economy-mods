package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.ReleaseReservation;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.SettleReservation;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryMaintenanceRestorationRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE = TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY;
    private static final NationId NATION_ID = new NationId(
            UUID.fromString("e84c7a4a-26d9-4489-a3e0-70f7004de5a4"));
    private static final UUID TEAM_ID =
            UUID.fromString("4980e4d7-82d7-4816-9f1a-3fa78f0cf584");
    private static final UUID ACTOR_ID =
            UUID.fromString("d1ee87f5-51fb-4ba7-a631-3d360aa4fb55");
    private static final UUID POLICY_ID =
            UUID.fromString("30a1102e-cbaa-4b02-bfa5-d482dbbe5935");
    private static final UUID CYCLE_ID =
            UUID.fromString("e3420f4c-f9e8-4ffc-aa8c-a8aff6200244");
    private static final UUID ASSESSMENT_ID =
            UUID.fromString("dc888c8f-fdad-47a1-af14-7b20d37b1ceb");

    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesExactLatestSuspendedTargetWithoutMutatingItsImmutableConclusion() {
        try (CivicDatabase database = database()) {
            suspendedAssessment(database);
            TerritoryMaintenanceRestorationRegistry restorations =
                    new TerritoryMaintenanceRestorationRegistry(database, CLOCK);
            PrepareTerritoryMaintenanceRestoration request = request("restore-capital", 8, 9);

            TerritoryMaintenanceRestoration prepared = restorations.prepare(request);

            assertEquals(TerritoryMaintenanceRestorationState.PREPARED, prepared.state());
            assertEquals(ASSESSMENT_ID, prepared.sourceSuspendedAssessmentId());
            assertEquals(POLICY_ID, prepared.policyId());
            assertEquals(MoneyAmount.ofMinorUnits(100L), prepared.nextCyclePrepayment());
            assertEquals(MoneyAmount.ofMinorUnits(30L), prepared.restorationFee());
            assertEquals(MoneyAmount.ofMinorUnits(130L), prepared.totalDue());
            assertEquals(NOW.plus(Duration.ofDays(14)), prepared.cooldownEndsAt());
            assertEquals(prepared, restorations.prepare(request));
            assertEquals(
                    TerritoryFiscalValidity.SUSPENDED.name(),
                    database.territoryFiscalAssessment(SERVICE.value(), "assess-suspended")
                            .validity());
        }
    }

    @Test
    void replayRejectsChangedTargetAndUnknownTargetFailsClosed() {
        try (CivicDatabase database = database()) {
            suspendedAssessment(database);
            TerritoryMaintenanceRestorationRegistry restorations =
                    new TerritoryMaintenanceRestorationRegistry(database, CLOCK);
            restorations.prepare(request("stable-restoration", 8, 9));

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> restorations.prepare(request("stable-restoration", 9, 9)));
            assertThrows(
                    IllegalStateException.class,
                    () -> restorations.prepare(request("unknown-target", 80, 90)));
        }
    }

    @Test
    void committedEvidenceRestoresFiscalValidityAndReleasesTheForceLoadRestriction() {
        try (CivicDatabase database = database()) {
            suspendedAssessment(database);
            TerritoryMaintenanceRestorationRegistry restorations =
                    new TerritoryMaintenanceRestorationRegistry(database, CLOCK);
            TerritoryMaintenanceRestoration prepared =
                    restorations.prepare(request("commit-restoration", 8, 9));
            FiscalEvidence evidence = fiscalEvidence(database);

            TerritoryMaintenanceRestoration committed = restorations.confirm(
                    new ConfirmTerritoryMaintenanceRestoration(
                            SERVICE,
                            prepared.restorationId(),
                            evidence.reservationId(),
                            evidence.publicFundPaymentId(),
                            evidence.destructionOperationId()));

            assertEquals(TerritoryMaintenanceRestorationState.CIVIC_COMMITTED, committed.state());
            assertEquals(
                    TerritoryFiscalValidity.SUSPENDED.name(),
                    database.territoryFiscalAssessment(SERVICE.value(), "assess-suspended")
                            .validity());
            assertEquals(
                    true,
                    new TerritoryMaintenanceRegistry(database, CLOCK).isEffective(
                            CYCLE_ID,
                            NATION_ID,
                            TEAM_ID,
                            "minecraft:overworld",
                            8,
                            9));
            assertEquals(
                    0,
                    database.activeTerritoryForceLoadRestrictions(
                                    NOW.plus(Duration.ofDays(2)).toEpochMilli())
                            .size());
            assertEquals(
                    new TerritoryMaintenanceRestorationHistory(
                            false, java.util.Optional.of(NOW.plus(Duration.ofDays(14)))),
                    new TerritoryMaintenanceRegistry(database, CLOCK)
                            .restorationHistory(
                                    NATION_ID,
                                    TEAM_ID,
                                    NOW.plusMillis(1L))
                            .get(new TerritoryClaimPosition(
                                    "minecraft:overworld", 8, 9)));
            assertEquals(committed, restorations.confirm(new ConfirmTerritoryMaintenanceRestoration(
                    SERVICE,
                    prepared.restorationId(),
                    evidence.reservationId(),
                    evidence.publicFundPaymentId(),
                    evidence.destructionOperationId())));

            TerritoryMaintenanceRegistry maintenance =
                    new TerritoryMaintenanceRegistry(database, CLOCK);
            var nextCycle = maintenance.openCycle(new OpenTerritoryMaintenanceCycle(
                    SERVICE,
                    "next-full-cycle",
                    NOW.plus(Duration.ofDays(7)),
                    NOW.plus(Duration.ofDays(14))));
            AssessTerritoryFiscalValidity exactTarget = new AssessTerritoryFiscalValidity(
                    SERVICE,
                    "assessment-consuming-restoration-credit",
                    nextCycle.cycleId(),
                    NATION_ID,
                    TEAM_ID,
                    "minecraft:overworld",
                    8,
                    9,
                    100L,
                    TerritoryMaintenancePriority.CAPITAL,
                    "Next full Cycle");
            TerritoryFiscalAssessment credited = maintenance.assess(exactTarget);

            assertEquals(MoneyAmount.ZERO, credited.maintenanceDue());
            assertEquals(credited, maintenance.assess(exactTarget));
            assertEquals(
                    MoneyAmount.ZERO,
                    restorations.find(SERVICE, "commit-restoration")
                            .orElseThrow()
                            .remainingNextCycleCredit());
            assertEquals(
                    MoneyAmount.ofMinorUnits(100L),
                    maintenance.assess(new AssessTerritoryFiscalValidity(
                                    SERVICE,
                                    "assessment-other-target",
                                    nextCycle.cycleId(),
                                    NATION_ID,
                                    TEAM_ID,
                                    "minecraft:overworld",
                                    9,
                                    9,
                                    100L,
                                    TerritoryMaintenancePriority.ORDINARY,
                                    "Other target in next full Cycle"))
                            .maintenanceDue());
        }
    }

    private static PrepareTerritoryMaintenanceRestoration request(
            String requestId, int chunkX, int chunkZ) {
        return new PrepareTerritoryMaintenanceRestoration(
                SERVICE,
                requestId,
                NATION_ID,
                TEAM_ID,
                ACTOR_ID,
                "minecraft:overworld",
                chunkX,
                chunkZ,
                POLICY_ID,
                NOW.plus(Duration.ofDays(7)),
                new TerritoryMaintenanceRestorationQuote(
                        MoneyAmount.ofMinorUnits(130L), NOW.plus(Duration.ofDays(14))),
                "Restore exact suspended capital");
    }

    private static void suspendedAssessment(CivicDatabase database) {
        long cycleStart = NOW.minus(Duration.ofDays(7)).toEpochMilli();
        database.registerNation(
                NATION_ID.value(), "restoration-test", "register", TEAM_ID, cycleStart);
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
                "Restoration test policy",
                cycleStart);
        database.openTerritoryMaintenanceCycle(
                CYCLE_ID,
                SERVICE.value(),
                "cycle",
                cycleStart,
                NOW.toEpochMilli(),
                cycleStart);
        database.assessTerritoryFiscalValidity(
                ASSESSMENT_ID,
                SERVICE.value(),
                "assess-suspended",
                CYCLE_ID,
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
                UUID.fromString("d18ab172-a20d-4de5-9868-343b004b5cb1"),
                SERVICE.value(),
                "suspend",
                CYCLE_ID,
                NATION_ID.value(),
                "Insufficient maintenance balance",
                NOW.toEpochMilli());
    }

    private static FiscalEvidence fiscalEvidence(CivicDatabase database) {
        AccountId treasury = new AccountId("nation:" + NATION_ID.value() + ":treasury");
        AccountId publicFund =
                new AccountId("system:territory:public-maintenance-fund");
        new TerritoryFiscalServiceProvisioner(new FiscalAuthorization(database))
                .ensureAuthorized(treasury);
        var session = FiscalTestSessions.open(database, SERVICE, "civiceconomy");
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(treasury, 130L);
        balances.put(publicFund, 0L);
        FiscalLedger ledger = FiscalLedger.authorized(
                database,
                account -> MoneyAmount.ofMinorUnits(balances.getOrDefault(account, 0L)),
                session);
        var reservation = ledger.reserve(new ReserveFunds(
                SERVICE,
                "restoration:reserve",
                treasury,
                MoneyAmount.ofMinorUnits(130L),
                "Out-of-Cycle Restoration"));
        var payment = PaymentCoordinator.authorized(
                        database,
                        external -> {
                            balances.compute(
                                    external.sourceAccount(),
                                    (ignored, balance) -> Math.subtractExact(
                                            balance, external.amount().minorUnits()));
                            balances.merge(
                                    external.recipientAccount(),
                                    external.amount().minorUnits(),
                                    Math::addExact);
                        },
                        session)
                .settle(
                        new SettleReservation(
                                SERVICE,
                                "restoration:public-fund",
                                reservation.reservationId(),
                                publicFund,
                                MoneyAmount.ofMinorUnits(52L)),
                        FailurePoint.NONE);
        database.confirmMonetarySupplyChange(
                UUID.randomUUID(),
                SERVICE.value(),
                "restoration:issuance",
                "ISSUANCE",
                130L,
                "mint-batch:restoration-registry-test",
                "Seed Restoration test issuance",
                NOW.toEpochMilli(),
                1_000L);
        UUID destructionId = UUID.randomUUID();
        database.preparePermanentDestruction(
                destructionId,
                SERVICE.value(),
                "restoration:destruction",
                treasury.value(),
                78L,
                "Out-of-Cycle Restoration",
                NOW.toEpochMilli());
        database.markPermanentDestructionExternalApplied(destructionId, NOW.toEpochMilli());
        database.commitPermanentDestruction(destructionId, NOW.toEpochMilli());
        ledger.release(new ReleaseReservation(
                SERVICE,
                "restoration:release",
                reservation.reservationId(),
                "Release destroyed Restoration share"));
        return new FiscalEvidence(reservation.reservationId(), payment.transactionId(), destructionId);
    }

    private record FiscalEvidence(
            UUID reservationId, UUID publicFundPaymentId, UUID destructionOperationId) {}

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-restoration.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4bdce546-d5bc-4129-8f21-3e83047f8da5"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
