package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV73MigrationTest {
    private static final UUID NATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FACILITY_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ACTOR_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TempDir Path temporaryDirectory;

    @Test
    void v72FacilityFactsSurviveAuditedTerritoryStateMigration() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v72.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase database = CivicDatabase.open(file, identity)) {
            database.registerNation(NATION_ID, "migration", "nation", TEAM_ID, 1L);
            database.registerFacility(
                    FACILITY_ID,
                    "migration",
                    "facility",
                    NATION_ID,
                    TEAM_ID,
                    "minecraft:overworld",
                    1,
                    70,
                    1,
                    ACTOR_ID,
                    "Existing v72 Registered Facility",
                    2L,
                    List.of(new StoredFacilityClaim("minecraft:overworld", 0, 0)));
        }
        downgradeToV72(file);

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(82, migrated.schemaVersion());
            assertEquals(NATION_ID, migrated.registeredFacility(FACILITY_ID).nationId());
            assertEquals(1, migrated.registeredFacilityClaims(FACILITY_ID).size());
            assertEquals(0, migrated.registeredFacilityStateTransitions(FACILITY_ID).size());

            migrated.transitionRegisteredFacilityState(
                    UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    FACILITY_ID,
                    "migration",
                    "ACTIVE",
                    "PAUSED_TERRITORY",
                    "Prove migrated transition history is writable",
                    3L);

            assertEquals("PAUSED_TERRITORY",
                    migrated.registeredFacility(FACILITY_ID).state());
            assertEquals(1,
                    migrated.registeredFacilityStateTransitions(FACILITY_ID).size());
        }
    }

    private static void downgradeToV72(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("UPDATE registered_facility SET state = 'ACTIVE'");
            statement.execute("DROP INDEX registered_facility_transition_history");
            statement.execute("DROP TABLE registered_facility_state_transition");
            statement.execute("PRAGMA user_version = 72");
        }
    }
}
