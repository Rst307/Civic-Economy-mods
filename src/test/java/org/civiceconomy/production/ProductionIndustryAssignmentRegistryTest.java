package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionIndustryAssignmentRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant EFFECTIVE_AT = NOW.plusSeconds(60L);
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("production-industry-test");

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactRecipeAssignmentIsSelectedAtEvidenceTimeAfterRestart() {
        Path file = temporaryDirectory.resolve("production-industry.sqlite3");
        UUID assignmentId;
        try (CivicDatabase database = database(file)) {
            ProductionIndustryAssignmentRegistry registry = registry(database);
            ProductionIndustryAssignmentVersion scheduled = registry.schedule(
                    new ScheduleProductionIndustryAssignment(
                            SERVICE,
                            "assign-milling",
                            "civic-admin-console:test",
                            "6.0.6",
                            "create:milling/wheat",
                            new ProductionIndustryId("food-processing"),
                            EFFECTIVE_AT,
                            "Classify trusted milling production"));

            assignmentId = scheduled.assignmentId();
            assertTrue(registry.assignment(
                            "6.0.6", "create:milling/wheat", EFFECTIVE_AT.minusMillis(1L))
                    .isEmpty());
        }

        try (CivicDatabase reopened = database(file)) {
            ProductionIndustryAssignmentVersion selected = registry(reopened)
                    .assignment("6.0.6", "create:milling/wheat", EFFECTIVE_AT)
                    .orElseThrow();

            assertEquals(assignmentId, selected.assignmentId());
            assertEquals(new ProductionIndustryId("food-processing"), selected.industryId());
            assertEquals(EFFECTIVE_AT, selected.effectiveAt());
            assertEquals("civic-admin-console:test", selected.actorIdentity());
            assertEquals("Classify trusted milling production", selected.reason());
        }
    }

    @Test
    void requestReplayIsImmutable() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("production-industry-replay.sqlite3"))) {
            ProductionIndustryAssignmentRegistry registry = registry(database);
            ScheduleProductionIndustryAssignment original =
                    new ScheduleProductionIndustryAssignment(
                            SERVICE,
                            "immutable-assignment",
                            "civic-admin-console:test",
                            "6.0.6",
                            "create:milling/wheat",
                            new ProductionIndustryId("food-processing"),
                            EFFECTIVE_AT,
                            "Classify trusted milling production");

            UUID assignmentId = registry.schedule(original).assignmentId();

            assertEquals(assignmentId, registry.schedule(original).assignmentId());
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> registry.schedule(new ScheduleProductionIndustryAssignment(
                            SERVICE,
                            "immutable-assignment",
                            "civic-admin-console:test",
                            "6.0.6",
                            "create:milling/wheat",
                            new ProductionIndustryId("bulk-materials"),
                            EFFECTIVE_AT,
                            "Classify trusted milling production")));
        }
    }

    @Test
    void laterAssignmentCannotRewriteEarlierEvidenceOrAnotherCreateVersion() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("production-industry-history.sqlite3"))) {
            ProductionIndustryAssignmentRegistry registry = registry(database);
            registry.schedule(new ScheduleProductionIndustryAssignment(
                    SERVICE,
                    "original-industry",
                    "civic-admin-console:test",
                    "6.0.6",
                    "create:milling/wheat",
                    new ProductionIndustryId("food-processing"),
                    EFFECTIVE_AT,
                    "Original trusted classification"));
            Instant replacementAt = EFFECTIVE_AT.plusSeconds(60L);
            registry.schedule(new ScheduleProductionIndustryAssignment(
                    SERVICE,
                    "replacement-industry",
                    "civic-admin-console:test",
                    "6.0.6",
                    "create:milling/wheat",
                    new ProductionIndustryId("bulk-materials"),
                    replacementAt,
                    "Future trusted reclassification"));

            assertEquals(
                    new ProductionIndustryId("food-processing"),
                    registry.assignment("6.0.6", "create:milling/wheat", EFFECTIVE_AT)
                            .orElseThrow()
                            .industryId());
            assertEquals(
                    new ProductionIndustryId("bulk-materials"),
                    registry.assignment("6.0.6", "create:milling/wheat", replacementAt)
                            .orElseThrow()
                            .industryId());
            assertTrue(registry.assignment(
                            "6.0.7", "create:milling/wheat", replacementAt)
                    .isEmpty());
        }
    }

    private ProductionIndustryAssignmentRegistry registry(CivicDatabase database) {
        return new ProductionIndustryAssignmentRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database(Path file) {
        return CivicDatabase.open(
                file,
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
