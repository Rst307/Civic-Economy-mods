package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryForceLoadEnforcementRegistryTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("civiceconomy-territory");
    private static final NationId NATION = new NationId(
            UUID.fromString("96e1f219-441a-4b5c-b7a5-6f00bc300a03"));
    private static final UUID TEAM = UUID.fromString("53dc3799-cbbc-4052-a9e0-5fa28e85f608");
    private static final UUID POLICY = UUID.fromString("34e79d03-e98b-45bc-a7b7-65524a51d3f4");

    @TempDir Path temporaryDirectory;

    @Test
    void durableEnforcementDerivesExactSuspendedAssessmentAndRecoversEachState() {
        UUID assessmentId;
        UUID enforcementId;
        try (CivicDatabase database = database()) {
            assessmentId = suspendedAssessment(database).assessmentId();
            TerritoryForceLoadEnforcementRegistry registry =
                    new TerritoryForceLoadEnforcementRegistry(
                            database,
                            Clock.fixed(Instant.ofEpochMilli(4_000L), ZoneOffset.UTC));

            TerritoryForceLoadEnforcement prepared = registry.prepare(
                    SERVICE,
                    "force-load:" + assessmentId,
                    assessmentId,
                    "Disable force-load for suspended Territory");

            enforcementId = prepared.enforcementId();
            assertEquals(TerritoryForceLoadEnforcementState.PREPARED, prepared.state());
            assertEquals(TEAM, prepared.ftbTeamId());
            assertEquals(
                    new TerritoryClaimPosition("minecraft:overworld", 4, 5),
                    prepared.position());
            assertEquals(Instant.ofEpochMilli(21_603_000L), prepared.notBefore());
            assertEquals(List.of(), registry.incomplete());
            assertThrows(
                    IllegalStateException.class,
                    () -> database.markTerritoryForceLoadExternalApplied(
                            enforcementId, 21_602_999L));
            assertEquals(
                    TerritoryForceLoadEnforcementState.PREPARED,
                    registry.find(enforcementId).orElseThrow().state());
            assertThrows(IllegalStateException.class, () -> registry.commit(enforcementId));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.markExternalApplied(enforcementId));
            assertThrows(
                    SecurityException.class,
                    () -> registry.prepare(
                            new ServiceIdentity("impostor-service"),
                            "impostor-force-load",
                            assessmentId,
                            "Impostor enforcement"));
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> registry.prepare(
                            SERVICE,
                            "force-load:" + assessmentId,
                            assessmentId,
                            "Changed reason"));
        }

        try (CivicDatabase database = database()) {
            TerritoryForceLoadEnforcementRegistry registry =
                    new TerritoryForceLoadEnforcementRegistry(
                            database,
                            Clock.fixed(
                                    Instant.ofEpochMilli(21_603_000L),
                                    ZoneOffset.UTC));
            assertEquals(
                    TerritoryForceLoadEnforcementState.PREPARED,
                    registry.find(enforcementId).orElseThrow().state());
            assertEquals(1, registry.incomplete().size());
            assertEquals(
                    TerritoryForceLoadEnforcementState.EXTERNAL_APPLIED,
                    registry.markExternalApplied(enforcementId).state());
        }

        try (CivicDatabase database = database()) {
            TerritoryForceLoadEnforcementRegistry registry =
                    new TerritoryForceLoadEnforcementRegistry(database);
            assertEquals(
                    TerritoryForceLoadEnforcementState.EXTERNAL_APPLIED,
                    registry.find(enforcementId).orElseThrow().state());
            assertEquals(
                    TerritoryForceLoadEnforcementState.CIVIC_COMMITTED,
                    registry.commit(enforcementId).state());
            assertEquals(
                    TerritoryForceLoadEnforcementState.CIVIC_COMMITTED,
                    registry.commit(enforcementId).state());
        }
    }

    @Test
    void restrictionUsesLatestConcludedAssessmentAndReleasesAfterEffectiveRestoration() {
        try (CivicDatabase database = database()) {
            TerritoryFiscalAssessment suspended = suspendedAssessment(database);
            new TerritoryForceLoadEnforcementRegistry(database).prepare(
                    SERVICE,
                    "force-load:" + suspended.assessmentId(),
                    suspended.assessmentId(),
                    "Disable force-load for suspended Territory");

            assertEquals(
                    List.of(),
                    new TerritoryForceLoadRestrictionRegistry(
                                    database,
                                    Clock.fixed(
                                            Instant.ofEpochMilli(21_602_999L),
                                            ZoneOffset.UTC))
                            .active());
            TerritoryForceLoadRestriction restriction =
                    new TerritoryForceLoadRestrictionRegistry(
                                    database,
                                    Clock.fixed(
                                            Instant.ofEpochMilli(21_603_000L),
                                            ZoneOffset.UTC))
                            .active()
                            .getFirst();
            assertEquals(NATION, restriction.nationId());
            assertEquals(TEAM, restriction.ftbTeamId());
            assertEquals(
                    new TerritoryClaimPosition("minecraft:overworld", 4, 5),
                    restriction.position());

            TerritoryMaintenanceRegistry maintenance = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle restoredCycle = maintenance.openCycle(
                    new OpenTerritoryMaintenanceCycle(
                            SERVICE,
                            "restored-cycle",
                            Instant.ofEpochMilli(4_000L),
                            Instant.ofEpochMilli(5_000L)));
            maintenance.assess(new AssessTerritoryFiscalValidity(
                    SERVICE,
                    "restored-assessment",
                    restoredCycle.cycleId(),
                    NATION,
                    TEAM,
                    "minecraft:overworld",
                    4,
                    5,
                    0L,
                    TerritoryMaintenancePriority.ORDINARY,
                    "Effective restoration fixture"));
            maintenance.settleZeroCostAssessments(new SuspendTerritoryMaintenance(
                    SERVICE,
                    "restored-settlement",
                    restoredCycle.cycleId(),
                    NATION,
                    "Effective restoration fixture"));

            assertEquals(
                    List.of(),
                    new TerritoryForceLoadRestrictionRegistry(
                                    database,
                                    Clock.fixed(
                                            Instant.ofEpochMilli(21_603_000L),
                                            ZoneOffset.UTC))
                            .active());
        }
    }

    private TerritoryFiscalAssessment suspendedAssessment(CivicDatabase database) {
        database.registerNation(NATION.value(), "test", "nation", TEAM, 1_000L);
        database.scheduleTerritoryMaintenancePolicy(
                POLICY,
                SERVICE.value(),
                "force-load-policy",
                "operator:test",
                Duration.ofDays(7).toMillis(),
                10L,
                15_000,
                5L,
                Duration.ofHours(6).toMillis(),
                10L,
                Duration.ofDays(7).toMillis(),
                6_000,
                1_000L,
                "Six-hour Force-load grace",
                500L);
        TerritoryMaintenanceRegistry maintenance = new TerritoryMaintenanceRegistry(database);
        TerritoryMaintenanceCycle cycle = maintenance.openCycle(
                new OpenTerritoryMaintenanceCycle(
                        SERVICE,
                        "automatic-maintenance:" + POLICY + ":2000:cycle",
                        Instant.ofEpochMilli(2_000L),
                        Instant.ofEpochMilli(3_000L)));
        TerritoryFiscalAssessment assessment = maintenance.assess(
                new AssessTerritoryFiscalValidity(
                        SERVICE,
                        "force-load-assessment",
                        cycle.cycleId(),
                        NATION,
                        TEAM,
                        "minecraft:overworld",
                        4,
                        5,
                        10L,
                        TerritoryMaintenancePriority.ORDINARY,
                        "Force-loaded Territory"));
        maintenance.suspend(new SuspendTerritoryMaintenance(
                SERVICE,
                "force-load-settlement",
                cycle.cycleId(),
                NATION,
                "No maintenance funds"));
        return assessment;
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("force-load.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("994ec99a-5d03-4ba3-a589-e3f9c74d36dc"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
