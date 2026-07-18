package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV68MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v67DatabaseAddsEmptyRegisteredFacilityTablesWithoutRewritingPriorFacts()
            throws Exception {
        Path file = temporaryDirectory.resolve("schema-v67.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID teamId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        try (CivicDatabase database = CivicDatabase.open(file, identity)) {
            database.registerNation(nationId, "migration", "nation", teamId, 1L);
        }
        downgradeToV67(file);

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(74, migrated.schemaVersion());
            assertEquals(teamId, migrated.nation(nationId).ftbTeamId());
            assertEquals(0, migrated.registeredFacilityClaims(
                            UUID.fromString("33333333-3333-3333-3333-333333333333"))
                    .size());
        }
    }

    private static void downgradeToV67(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX registered_facility_nation_state");
            statement.execute("DROP TABLE registered_facility_claim");
            statement.execute("DROP TABLE registered_facility");
            statement.execute("PRAGMA user_version = 67");
        }
    }
}
