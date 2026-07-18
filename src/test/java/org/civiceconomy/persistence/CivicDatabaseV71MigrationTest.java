package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV71MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v70DatabaseAddsEmptyBaselineTablesWithoutRewritingProductionHistory()
            throws Exception {
        Path file = temporaryDirectory.resolve("schema-v70.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID teamId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID facilityId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID interfaceId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID completionId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID receiptId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        try (CivicDatabase database = CivicDatabase.open(file, identity)) {
            database.registerNation(nationId, "migration", "nation", teamId, 1L);
            database.registerFacility(
                    facilityId, "migration", "facility", nationId, teamId,
                    "minecraft:overworld", 1, 70, 1,
                    UUID.fromString("77777777-7777-7777-7777-777777777777"),
                    "Existing v70 Facility", 2L,
                    List.of(new StoredFacilityClaim("minecraft:overworld", 0, 0)));
            database.registerFacilityAccountingInterface(
                    interfaceId, "migration", "interface", facilityId,
                    "minecraft:overworld", 4, 71, 4,
                    UUID.fromString("88888888-8888-8888-8888-888888888888"),
                    "Existing v70 Interface", 3L);
            database.recordFacilityProductionObservation(
                    new StoredCreateRecipeCompletion(
                            completionId, "6.0.6", "MILLSTONE", "create:milling/wheat",
                            "minecraft:overworld", 2, 70, 2, 4L),
                    List.of(change(completionId, "INPUT", 0, "minecraft:wheat")),
                    List.of(change(completionId, "OUTPUT", 0, "create:wheat_flour")),
                    new StoredFacilityAccountingReceipt(
                            receiptId, interfaceId, "minecraft:overworld", 4, 71, 4, 5L),
                    List.of(change(receiptId, "RECEIPT", 4, "create:wheat_flour")),
                    new StoredFacilityProductionDecision(
                            completionId, facilityId, interfaceId, receiptId,
                            "FACILITY_BASELINING", "Existing v70 decision", 6L));
        }
        downgradeToV70(file);

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(83, migrated.schemaVersion());
            assertEquals("FACILITY_BASELINING",
                    migrated.facilityProductionDecision(completionId).decision());
            assertNull(migrated.facilityAccountingBaseline(facilityId));
        }
    }

    private static StoredProductionInventoryChange change(
            UUID sourceId, String role, int slot, String itemId) {
        return new StoredProductionInventoryChange(
                sourceId, role, slot, itemId, "components:{}", 1);
    }

    private static void downgradeToV70(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE facility_accounting_baseline_activation");
            statement.execute("DROP TABLE facility_accounting_baseline_inventory");
            statement.execute("DROP TABLE facility_accounting_baseline_machine");
            statement.execute("DROP TABLE facility_accounting_baseline");
            statement.execute("PRAGMA user_version = 70");
        }
    }
}
