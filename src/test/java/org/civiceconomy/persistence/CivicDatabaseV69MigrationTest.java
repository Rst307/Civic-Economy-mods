package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV69MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v68DatabaseAddsEmptyFacilityAccountingInterfaceWithoutRewritingFacility()
            throws Exception {
        Path file = temporaryDirectory.resolve("schema-v68.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID teamId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID facilityId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        try (CivicDatabase database = CivicDatabase.open(file, identity)) {
            database.registerNation(nationId, "migration", "nation", teamId, 1L);
            database.registerFacility(
                    facilityId,
                    "migration",
                    "facility",
                    nationId,
                    teamId,
                    "minecraft:overworld",
                    1,
                    70,
                    1,
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    "Existing v68 Facility",
                    2L,
                    List.of(new StoredFacilityClaim("minecraft:overworld", 0, 0)));
        }
        downgradeToV68(file);

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(94, migrated.schemaVersion());
            assertEquals(nationId, migrated.registeredFacility(facilityId).nationId());
            assertEquals(null, migrated.facilityAccountingInterface(facilityId));
        }
    }

    private static void downgradeToV68(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE facility_accounting_interface");
            statement.execute("PRAGMA user_version = 68");
        }
    }
}
