package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryMaintenanceAssessmentProcessorTest {
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-01T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-territory");
    private static final NationId NATION =
            new NationId(UUID.fromString("209bb856-c9e5-4b3d-b6ce-e3230076d895"));
    private static final UUID TEAM =
            UUID.fromString("c43877ce-0b55-48a4-a77d-bbce8b393153");

    @TempDir Path temporaryDirectory;

    @Test
    void persistedClaimSnapshotReplaysAcrossRestartIndependentOfInputOrder() {
        TerritoryMaintenanceAssessmentBatch first;
        try (CivicDatabase database = database()) {
            registerNation(database);
            first = processor(database).assess(request(List.of(ordinaryClaim(), capitalClaim())));
            assertEquals(
                    List.of(TerritoryMaintenancePriority.CAPITAL, TerritoryMaintenancePriority.ORDINARY),
                    first.assessments().stream().map(TerritoryFiscalAssessment::priority).toList());
        }

        try (CivicDatabase database = database()) {
            TerritoryMaintenanceAssessmentBatch replay =
                    processor(database).assess(request(List.of(capitalClaim(), ordinaryClaim())));
            assertEquals(first.cycle().cycleId(), replay.cycle().cycleId());
            assertEquals(
                    first.assessments().stream().map(TerritoryFiscalAssessment::assessmentId).toList(),
                    replay.assessments().stream().map(TerritoryFiscalAssessment::assessmentId).toList());
            assertEquals(
                    List.of(TerritoryFiscalValidity.PENDING, TerritoryFiscalValidity.PENDING),
                    replay.assessments().stream().map(TerritoryFiscalAssessment::validity).toList());
        }
    }

    @Test
    void changedClaimSnapshotConflictsAndDuplicateTargetFailsBeforePersistence() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceAssessmentProcessor processor = processor(database);
            processor.assess(request(List.of(capitalClaim())));
            TerritoryMaintenanceClaimSnapshot changed = new TerritoryMaintenanceClaimSnapshot(
                    NATION,
                    TEAM,
                    "minecraft:overworld",
                    0,
                    0,
                    101L,
                    TerritoryMaintenancePriority.CAPITAL);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> processor.assess(request(List.of(changed))));
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> processor.assess(request(List.of(capitalClaim(), ordinaryClaim()))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> processor.assess(new AssessTerritoryMaintenanceCycle(
                            SERVICE,
                            "duplicate-cycle",
                            START.plusSeconds(1),
                            END.plusSeconds(1),
                            List.of(ordinaryClaim(), ordinaryClaim()),
                            "Automatic maintenance assessment")));
            assertEquals(
                    null,
                    database.territoryMaintenanceCycle(SERVICE.value(), "duplicate-cycle:cycle"));
        }
    }

    @Test
    void persistedSnapshotRecoversAssessmentsWithoutReadingLiveClaimsAgain() {
        UUID cycleId;
        List<TerritoryMaintenanceClaimSnapshot> claims =
                List.of(capitalClaim(), ordinaryClaim());
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle cycle = registry.openCycle(
                    new OpenTerritoryMaintenanceCycle(
                            SERVICE,
                            "crash-window:cycle",
                            START,
                            END));
            cycleId = cycle.cycleId();
            registry.registerAssessmentBatch(
                    cycleId,
                    SERVICE,
                    "crash-window:batch",
                    claims,
                    TerritoryMaintenanceAssessmentProcessor.snapshotSha256(claims));
            assertEquals(List.of(), registry.pendingCandidates(cycleId, NATION));
        }

        try (CivicDatabase database = database()) {
            TerritoryMaintenanceAssessmentBatch recovered = processor(database)
                    .recover(SERVICE, "crash-window", "Recovered automatic assessment")
                    .orElseThrow();
            assertEquals(cycleId, recovered.cycle().cycleId());
            assertEquals(
                    List.of(100L, 50L),
                    recovered.assessments().stream()
                            .map(assessment -> assessment.maintenanceDue().minorUnits())
                            .toList());
            assertEquals(
                    List.of(TerritoryMaintenancePriority.CAPITAL, TerritoryMaintenancePriority.ORDINARY),
                    recovered.assessments().stream()
                            .map(TerritoryFiscalAssessment::priority)
                            .toList());
        }
    }

    @Test
    void directReplayFailsClosedWhenPersistedClaimSnapshotIsMissing() throws Exception {
        Path databaseFile = databaseFile();
        try (CivicDatabase database = database()) {
            registerNation(database);
            processor(database).assess(request(List.of(capitalClaim())));
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM territory_maintenance_assessment_claim");
        }

        try (CivicDatabase database = database()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> processor(database).assess(request(List.of(capitalClaim()))));
        }
    }

    private AssessTerritoryMaintenanceCycle request(
            List<TerritoryMaintenanceClaimSnapshot> claims) {
        return new AssessTerritoryMaintenanceCycle(
                SERVICE,
                "maintenance-2026-09",
                START,
                END,
                claims,
                "Automatic maintenance assessment");
    }

    private TerritoryMaintenanceClaimSnapshot capitalClaim() {
        return new TerritoryMaintenanceClaimSnapshot(
                NATION,
                TEAM,
                "minecraft:overworld",
                0,
                0,
                100L,
                TerritoryMaintenancePriority.CAPITAL);
    }

    private TerritoryMaintenanceClaimSnapshot ordinaryClaim() {
        return new TerritoryMaintenanceClaimSnapshot(
                NATION,
                TEAM,
                "minecraft:overworld",
                9,
                0,
                50L,
                TerritoryMaintenancePriority.ORDINARY);
    }

    private TerritoryMaintenanceAssessmentProcessor processor(CivicDatabase database) {
        return new TerritoryMaintenanceAssessmentProcessor(
                new TerritoryMaintenanceRegistry(database));
    }

    private void registerNation(CivicDatabase database) {
        database.registerNation(NATION.value(), "test", "nation", TEAM, START.minusSeconds(1).toEpochMilli());
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                databaseFile(),
                new DatabaseIdentity(
                        UUID.fromString("2a80643c-ecb4-4510-8f34-c32c05a34aef"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private Path databaseFile() {
        return temporaryDirectory.resolve("automatic-maintenance.sqlite3");
    }
}
