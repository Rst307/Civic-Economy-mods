package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV79MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v78DatabaseAddsEmptyProductionIndustryAssignmentHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v78.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("1d679ea7-347f-44bb-82fd-218e1c8743d5"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v78 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX production_industry_assignment_current");
            statement.execute("DROP TABLE production_industry_assignment");
            statement.execute("PRAGMA user_version = 78");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(91, migrated.schemaVersion());
            assertNull(migrated.currentProductionIndustryAssignment(
                    "6.0.6", "create:milling/wheat", Long.MAX_VALUE));

            StoredProductionIndustryAssignment scheduled =
                    migrated.scheduleProductionIndustryAssignment(
                            UUID.fromString("d4b58693-1e31-4077-a008-e83775d14b15"),
                            "migration",
                            "initial-production-industry-assignment",
                            "civic-admin-console:migration",
                            "6.0.6",
                            "create:milling/wheat",
                            "food-processing",
                            100L,
                            "Prove migrated assignment history is writable",
                            90L);

            assertNotNull(scheduled);
            assertEquals(
                    "food-processing",
                    migrated.currentProductionIndustryAssignment(
                                    "6.0.6", "create:milling/wheat", 100L)
                            .industryId());
        }
    }
}
