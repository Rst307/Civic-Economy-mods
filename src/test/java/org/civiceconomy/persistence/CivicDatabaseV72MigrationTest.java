package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV72MigrationTest {
    private static final UUID NATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OLD_GRANT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OLD_REVOCATION_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID NEW_GRANT_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void v71PermissionHistorySurvivesFacilityAccountingPermissionMigration()
            throws Exception {
        Path file = temporaryDirectory.resolve("schema-v71.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase database = CivicDatabase.open(file, identity)) {
            database.registerNation(
                    NATION_ID,
                    "migration-test",
                    "register-nation",
                    UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    NOW.minusSeconds(60L).toEpochMilli());
            database.grantNationFiscalPermission(
                    OLD_GRANT_ID,
                    "migration-test",
                    "grant-old",
                    NATION_ID,
                    ACTOR_ID,
                    ACTOR_ID,
                    "MANAGE_TERRITORY_FINANCE",
                    "Existing v71 permission",
                    NOW.toEpochMilli());
            database.revokeNationFiscalPermission(
                    OLD_REVOCATION_ID,
                    OLD_GRANT_ID,
                    "migration-test",
                    "revoke-old",
                    NATION_ID,
                    ACTOR_ID,
                    "Existing v71 revocation",
                    NOW.plusSeconds(1L).toEpochMilli());
        }
        downgradeToV71(file);

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(93, migrated.schemaVersion());
            assertNotNull(migrated.nationFiscalPermissionGrant(OLD_GRANT_ID));
            assertNotNull(migrated.nationFiscalPermissionRevocation(OLD_GRANT_ID));
            migrated.grantNationFiscalPermission(
                    NEW_GRANT_ID,
                    "migration-test",
                    "grant-facility-accounting",
                    NATION_ID,
                    ACTOR_ID,
                    ACTOR_ID,
                    "MANAGE_FACILITY_ACCOUNTING",
                    "Manage exact Facility accounting boundaries",
                    NOW.plusSeconds(2L).toEpochMilli());
            assertEquals(
                    "MANAGE_FACILITY_ACCOUNTING",
                    migrated.nationFiscalPermissionGrant(NEW_GRANT_ID).permission());
        }
    }

    private static void downgradeToV71(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("DROP INDEX nation_fiscal_permission_grant_player");
            statement.execute("ALTER TABLE nation_fiscal_permission_revocation RENAME TO nation_fiscal_permission_revocation_v72");
            statement.execute("ALTER TABLE nation_fiscal_permission_grant RENAME TO nation_fiscal_permission_grant_v72");
            statement.execute("""
                    CREATE TABLE nation_fiscal_permission_grant (
                        grant_id TEXT PRIMARY KEY,
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                        actor_player_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        permission TEXT NOT NULL CHECK (permission IN (
                            'VIEW_ACCOUNT', 'VIEW_LEDGER', 'DRAFT_BUDGET',
                            'APPROVE_BUDGET', 'INITIATE_PAYMENT', 'APPROVE_PAYMENT',
                            'MANAGE_WITHDRAWAL', 'MANAGE_TERRITORY_FINANCE',
                            'MANAGE_ISSUANCE', 'MANAGE_FISCAL_ROLES',
                            'MANAGE_APPROVAL_POLICY', 'MANAGE_PUBLIC_POLICY',
                            'MANAGE_RECOVERY'
                        )),
                        reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                        granted_at_epoch_millis INTEGER NOT NULL
                            CHECK (granted_at_epoch_millis >= 0),
                        UNIQUE (service_identity, request_id)
                    )
                    """);
            statement.execute("INSERT INTO nation_fiscal_permission_grant SELECT * FROM nation_fiscal_permission_grant_v72");
            statement.execute("""
                    CREATE INDEX nation_fiscal_permission_grant_player
                    ON nation_fiscal_permission_grant (nation_id, player_id)
                    """);
            statement.execute("""
                    CREATE TABLE nation_fiscal_permission_revocation (
                        revocation_id TEXT PRIMARY KEY,
                        grant_id TEXT NOT NULL UNIQUE
                            REFERENCES nation_fiscal_permission_grant(grant_id),
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                        actor_player_id TEXT NOT NULL,
                        reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                        revoked_at_epoch_millis INTEGER NOT NULL
                            CHECK (revoked_at_epoch_millis >= 0),
                        UNIQUE (service_identity, request_id)
                    )
                    """);
            statement.execute("INSERT INTO nation_fiscal_permission_revocation SELECT * FROM nation_fiscal_permission_revocation_v72");
            statement.execute("DROP TABLE nation_fiscal_permission_revocation_v72");
            statement.execute("DROP TABLE nation_fiscal_permission_grant_v72");
            statement.execute("PRAGMA user_version = 71");
        }
    }
}
