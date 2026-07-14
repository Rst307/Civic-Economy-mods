package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryMaintenanceRegistryTest {
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-01T00:00:00Z");
    private static final NationId NATION_ID = NationId.create();
    private static final UUID TEAM_ID = UUID.randomUUID();

    @TempDir Path temporaryDirectory;

    @Test
    void exactCommittedSettlementActivatesPendingAssessmentAcrossRestart() {
        UUID cycleId;
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"),
                    "maintenance-2026-08",
                    START,
                    END));
            cycleId = cycle.cycleId();
            TerritoryFiscalAssessment assessment = registry.assess(
                    new AssessTerritoryFiscalValidity(
                            new ServiceIdentity("civiceconomy-territory"),
                            "assess-overworld-4-7",
                            cycleId,
                            NATION_ID,
                            TEAM_ID,
                            "minecraft:overworld",
                            4,
                            7,
                            250L,
                            "Cycle assessment"));

            assertEquals(TerritoryFiscalValidity.PENDING, assessment.validity());
            assertFalse(registry.isEffective(
                    cycleId, NATION_ID, TEAM_ID, "minecraft:overworld", 4, 7));
            assertThrows(IllegalStateException.class, () -> registry.confirmSettlement(
                    new ConfirmTerritoryMaintenanceSettlement(
                            new ServiceIdentity("civiceconomy-territory"),
                            "settle-cycle",
                            cycleId,
                            NATION_ID,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "Maintenance funded")));
            assertEquals(TerritoryFiscalValidity.PENDING, registry.assessment(
                    cycleId, NATION_ID, TEAM_ID, "minecraft:overworld", 4, 7).validity());

            SettlementEvidence evidence = committedSettlementEvidence(database, cycleId);
            TerritoryMaintenanceSettlement settlement = registry.confirmSettlement(
                    new ConfirmTerritoryMaintenanceSettlement(
                            new ServiceIdentity("civiceconomy-territory"),
                            "settle-cycle",
                            cycleId,
                            NATION_ID,
                            evidence.reservationId(),
                            evidence.publicFundPaymentId(),
                            evidence.destructionOperationId(),
                            "Maintenance funded"));
            assertEquals(TerritoryFiscalValidity.EFFECTIVE, settlement.validity());

            TerritoryFiscalAssessment replay = registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"),
                    "assess-overworld-4-7",
                    cycleId,
                    NATION_ID,
                    TEAM_ID,
                    "minecraft:overworld",
                    4,
                    7,
                    250L,
                    "Cycle assessment"));
            assertEquals(assessment.assessmentId(), replay.assessmentId());
            assertEquals(TerritoryFiscalValidity.EFFECTIVE, replay.validity());
        }

        try (CivicDatabase database = database()) {
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            assertTrue(registry.isEffective(
                    cycleId, NATION_ID, TEAM_ID, "minecraft:overworld", 4, 7));
            assertFalse(registry.isEffective(
                    cycleId, NATION_ID, UUID.randomUUID(), "minecraft:overworld", 4, 7));
            assertFalse(registry.isEffective(
                    cycleId, NATION_ID, TEAM_ID, "minecraft:overworld", 5, 7));
        }
    }

    private SettlementEvidence committedSettlementEvidence(
            CivicDatabase database, UUID cycleId) {
        String service = "civiceconomy-territory";
        String treasury = "nation:" + NATION_ID.value() + ":treasury";
        var reservation = database.reserve(
                service, "maintenance-reserve", treasury, 250L, "Maintenance " + cycleId);
        var payment = database.preparePayment(
                service,
                "maintenance-public-fund",
                reservation.reservationId(),
                "system:territory:public-maintenance-fund",
                100L);
        database.markExternalApplied(payment.transactionId());
        database.commitPayment(payment.transactionId(), reservation.reservationId());
        database.confirmMonetarySupplyChange(
                UUID.randomUUID(), service, "seed-issuance", "ISSUANCE", 250L,
                "mint:maintenance-settlement", "Fixture issuance", START.toEpochMilli(), 1_000L);
        var destruction = database.preparePermanentDestruction(
                UUID.randomUUID(), service, "maintenance-destruction", treasury, 150L,
                "Maintenance " + cycleId, START.toEpochMilli());
        database.commitPermanentDestruction(
                destruction.operationId(), START.plusSeconds(1).toEpochMilli());
        database.releaseReservation(
                UUID.randomUUID(), service, "maintenance-release", reservation.reservationId(),
                "Release destroyed share", START.plusSeconds(2).toEpochMilli());
        return new SettlementEvidence(
                reservation.reservationId(), payment.transactionId(), destruction.operationId());
    }

    @Test
    void explicitNonPaymentSettlementSuspendsEveryPendingAssessment() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"), "cycle-a", START, END));
            assertThrows(IllegalStateException.class, () -> registry.openCycle(
                    new OpenTerritoryMaintenanceCycle(
                            new ServiceIdentity("civiceconomy-territory"),
                            "cycle-b",
                            START.plusSeconds(1),
                            END.plusSeconds(1))));
            registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"), "assessment", cycle.cycleId(),
                    NATION_ID, TEAM_ID, "minecraft:overworld", 1, 2, 100L,
                    "Cycle assessment"));
            assertThrows(IllegalStateException.class, () -> registry.suspend(
                    new SuspendTerritoryMaintenance(
                            new ServiceIdentity("impostor-territory"),
                            "impostor-suspend",
                            cycle.cycleId(),
                            NATION_ID,
                            "Cross-identity suspension")));
            assertEquals(
                    TerritoryFiscalValidity.PENDING,
                    registry.assessment(
                                    cycle.cycleId(),
                                    NATION_ID,
                                    TEAM_ID,
                                    "minecraft:overworld",
                                    1,
                                    2)
                            .validity());
            TerritoryMaintenanceSettlement suspended = registry.suspend(
                    new SuspendTerritoryMaintenance(
                            new ServiceIdentity("civiceconomy-territory"),
                            "suspend-cycle",
                            cycle.cycleId(),
                            NATION_ID,
                            "Insufficient maintenance funds"));
            assertEquals(TerritoryFiscalValidity.SUSPENDED, suspended.validity());
            assertTrue(suspended.reservationId().isEmpty());
            assertTrue(suspended.publicFundPaymentId().isEmpty());
            assertTrue(suspended.destructionOperationId().isEmpty());
            assertEquals(suspended, registry.suspend(new SuspendTerritoryMaintenance(
                    new ServiceIdentity("civiceconomy-territory"),
                    "suspend-cycle",
                    cycle.cycleId(),
                    NATION_ID,
                    "Insufficient maintenance funds")));
            assertFalse(registry.isEffective(
                    cycle.cycleId(), NATION_ID, TEAM_ID, "minecraft:overworld", 1, 2));
            assertThrows(org.civiceconomy.fiscal.IdempotencyConflictException.class, () ->
                    registry.assess(new AssessTerritoryFiscalValidity(
                            new ServiceIdentity("civiceconomy-territory"), "assessment", cycle.cycleId(),
                            NATION_ID, TEAM_ID, "minecraft:overworld", 1, 2, 100L,
                            "Changed")));
        }
    }

    @Test
    void rejectsSettlementWhenReservationContainsAnUnreportedPayment() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"), "cycle-extra", START, END));
            registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"),
                    "assessment-extra",
                    cycle.cycleId(),
                    NATION_ID,
                    TEAM_ID,
                    "minecraft:overworld",
                    8,
                    9,
                    250L,
                    "Cycle assessment"));
            String service = "civiceconomy-territory";
            String treasury = "nation:" + NATION_ID.value() + ":treasury";
            var reservation = database.reserve(
                    service, "extra-reserve", treasury, 250L, "Maintenance " + cycle.cycleId());
            var publicFund = database.preparePayment(
                    service,
                    "extra-public-fund",
                    reservation.reservationId(),
                    "system:territory:public-maintenance-fund",
                    99L);
            database.markExternalApplied(publicFund.transactionId());
            database.commitPayment(publicFund.transactionId(), reservation.reservationId());
            var extra = database.preparePayment(
                    "civiceconomy-territory",
                    "unreported-payment",
                    reservation.reservationId(),
                    "player:" + UUID.randomUUID(),
                    1L);
            database.markExternalApplied(extra.transactionId());
            database.commitPayment(extra.transactionId(), reservation.reservationId());
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(), service, "extra-seed-issuance", "ISSUANCE", 250L,
                    "mint:extra-maintenance", "Fixture issuance", START.toEpochMilli(), 1_000L);
            var destruction = database.preparePermanentDestruction(
                    UUID.randomUUID(), service, "extra-destruction", treasury, 151L,
                    "Maintenance " + cycle.cycleId(), START.toEpochMilli());
            database.commitPermanentDestruction(
                    destruction.operationId(), START.plusSeconds(1).toEpochMilli());
            database.releaseReservation(
                    UUID.randomUUID(), service, "extra-release", reservation.reservationId(),
                    "Release destroyed share", START.plusSeconds(2).toEpochMilli());

            assertThrows(IllegalStateException.class, () -> registry.confirmSettlement(
                    new ConfirmTerritoryMaintenanceSettlement(
                            new ServiceIdentity("civiceconomy-territory"),
                            "settle-extra",
                            cycle.cycleId(),
                            NATION_ID,
                            reservation.reservationId(),
                            publicFund.transactionId(),
                            destruction.operationId(),
                            "Maintenance funded")));
            assertEquals(
                    TerritoryFiscalValidity.PENDING,
                    registry.assessment(
                                    cycle.cycleId(),
                                    NATION_ID,
                                    TEAM_ID,
                                    "minecraft:overworld",
                                    8,
                                    9)
                            .validity());
        }
    }

    @Test
    void persistsPriorityAndListsPendingCandidatesDeterministically() {
        UUID cycleId;
        UUID capitalAssessmentId;
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"), "cycle-priority", START, END));
            cycleId = cycle.cycleId();
            registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"),
                    "ordinary-assessment",
                    cycleId,
                    NATION_ID,
                    TEAM_ID,
                    "minecraft:overworld",
                    9,
                    0,
                    50L,
                    TerritoryMaintenancePriority.ORDINARY,
                    "Ordinary claim"));
            TerritoryFiscalAssessment capital = registry.assess(
                    new AssessTerritoryFiscalValidity(
                            new ServiceIdentity("civiceconomy-territory"),
                            "capital-assessment",
                            cycleId,
                            NATION_ID,
                            TEAM_ID,
                            "minecraft:overworld",
                            0,
                            0,
                            100L,
                            TerritoryMaintenancePriority.CAPITAL,
                            "Capital claim"));
            capitalAssessmentId = capital.assessmentId();
        }

        try (CivicDatabase database = database()) {
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            assertEquals(
                    List.of(
                            new TerritoryMaintenanceCandidate(
                                    capitalAssessmentId,
                                    TerritoryMaintenancePriority.CAPITAL,
                                    "minecraft:overworld",
                                    0,
                                    0,
                                    org.civiceconomy.fiscal.MoneyAmount.ofMinorUnits(100L)),
                            new TerritoryMaintenanceCandidate(
                                    registry.assessment(
                                                    cycleId,
                                                    NATION_ID,
                                                    TEAM_ID,
                                                    "minecraft:overworld",
                                                    9,
                                                    0)
                                            .assessmentId(),
                                    TerritoryMaintenancePriority.ORDINARY,
                                    "minecraft:overworld",
                                    9,
                                    0,
                                    org.civiceconomy.fiscal.MoneyAmount.ofMinorUnits(50L))),
                    registry.pendingCandidates(cycleId, NATION_ID));
        }
    }

    private void registerNation(CivicDatabase database) {
        database.registerNation(NATION_ID.value(), "test", "nation", TEAM_ID, START.minusSeconds(1).toEpochMilli());
    }

    private CivicDatabase database() {
        return CivicDatabase.open(temporaryDirectory.resolve("maintenance.sqlite3"),
                new DatabaseIdentity(UUID.fromString("f71a1a1f-70fc-4d4d-a359-e47b22f85ba9"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }

    private record SettlementEvidence(
            UUID reservationId, UUID publicFundPaymentId, UUID destructionOperationId) {}
}
