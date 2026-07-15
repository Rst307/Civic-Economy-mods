package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseBackupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void onlineBackupCapturesAConsistentPointInTime() {
        DatabaseIdentity identity = identity("4b617458-7f03-4fd2-a94e-4dc37ecbd682");
        Path liveFile = temporaryDirectory.resolve("live.sqlite3");
        Path backupFile = temporaryDirectory.resolve("backup.sqlite3");
        AccountId treasury = new AccountId("nation:aurora:treasury");

        try (CivicDatabase live = CivicDatabase.open(liveFile, identity)) {
            FiscalLedger ledger = ledger(live, new ServiceIdentity("backup-test"));
            ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("backup-test"),
                    "before-backup",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Snapshot fixture"));

            live.backup(backupFile);

            ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("backup-test"),
                    "after-backup",
                    treasury,
                    MoneyAmount.ofMinorUnits(200),
                    "Post-snapshot mutation"));
            assertEquals(
                    MoneyAmount.ofMinorUnits(500),
                    ledger.reservedBalance(new ServiceIdentity("backup-test"), treasury));
        }

        try (CivicDatabase backup = CivicDatabase.open(backupFile, identity)) {
            assertEquals(identity, backup.identity());
            assertEquals(35, backup.schemaVersion());
            assertEquals(
                    MoneyAmount.ofMinorUnits(300),
                    ledger(backup, new ServiceIdentity("backup-test"))
                            .reservedBalance(new ServiceIdentity("backup-test"), treasury));
        }
    }

    @Test
    void validatedRestoreCreatesAUsableDatabaseAtANewPath() {
        DatabaseIdentity identity = identity("4b617458-7f03-4fd2-a94e-4dc37ecbd682");
        Path liveFile = temporaryDirectory.resolve("restore-source.sqlite3");
        Path backupFile = temporaryDirectory.resolve("restore-backup.sqlite3");
        Path restoredFile = temporaryDirectory.resolve("restored/civic.sqlite3");
        AccountId treasury = new AccountId("nation:aurora:treasury");

        try (CivicDatabase live = CivicDatabase.open(liveFile, identity)) {
            ledger(live, new ServiceIdentity("restore-test")).reserve(new ReserveFunds(
                    new ServiceIdentity("restore-test"),
                    "restored-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(450),
                    "Restore fixture"));
            live.backup(backupFile);
        }

        CivicDatabase.restoreBackup(backupFile, restoredFile, identity);

        try (CivicDatabase restored = CivicDatabase.open(restoredFile, identity)) {
            assertEquals(identity, restored.identity());
            assertEquals(35, restored.schemaVersion());
            assertEquals(
                    MoneyAmount.ofMinorUnits(450),
                    ledger(restored, new ServiceIdentity("restore-test"))
                            .reservedBalance(new ServiceIdentity("restore-test"), treasury));
        }
    }

    @Test
    void mismatchedBackupIdentityIsRejectedBeforePublishingTheDestination() {
        DatabaseIdentity sourceIdentity = identity("4b617458-7f03-4fd2-a94e-4dc37ecbd682");
        Path liveFile = temporaryDirectory.resolve("foreign-source.sqlite3");
        Path backupFile = temporaryDirectory.resolve("foreign-backup.sqlite3");
        Path restoredFile = temporaryDirectory.resolve("foreign-restored.sqlite3");
        try (CivicDatabase live = CivicDatabase.open(liveFile, sourceIdentity)) {
            live.backup(backupFile);
        }

        DatabaseIdentity anotherWorld = identity("05a95c0a-c0c2-4280-b453-427a62494a2d");

        assertThrows(
                DatabaseIdentityMismatchException.class,
                () -> CivicDatabase.restoreBackup(backupFile, restoredFile, anotherWorld));
        assertFalse(java.nio.file.Files.exists(restoredFile));
    }

    @Test
    void unsupportedBackupSchemaIsRejectedBeforePublishingTheDestination() throws Exception {
        DatabaseIdentity identity = identity("4b617458-7f03-4fd2-a94e-4dc37ecbd682");
        Path liveFile = temporaryDirectory.resolve("unsupported-source.sqlite3");
        Path backupFile = temporaryDirectory.resolve("unsupported-backup.sqlite3");
        Path restoredFile = temporaryDirectory.resolve("unsupported-restored.sqlite3");
        try (CivicDatabase live = CivicDatabase.open(liveFile, identity)) {
            live.backup(backupFile);
        }
        try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupFile);
                Statement statement = backup.createStatement()) {
            statement.execute("PRAGMA user_version = 36");
        }

        assertThrows(
                UnsupportedDatabaseVersionException.class,
                () -> CivicDatabase.restoreBackup(backupFile, restoredFile, identity));
        assertFalse(java.nio.file.Files.exists(restoredFile));
    }

    private static FiscalLedger ledger(
            CivicDatabase database, ServiceIdentity serviceIdentity) {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        authorize(database, serviceIdentity, treasury);
        return FiscalLedger.authorized(
                database,
                ignored -> MoneyAmount.ofMinorUnits(1_000),
                FiscalTestSessions.open(database, serviceIdentity, "civiceconomy-tests"));
    }

    private static void authorize(
            CivicDatabase database, ServiceIdentity serviceIdentity, AccountId accountId) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                serviceIdentity, "civiceconomy-tests", "Backup test " + serviceIdentity.value()));
        for (FiscalCapability capability :
                new FiscalCapability[] {FiscalCapability.RESERVE_FUNDS, FiscalCapability.READ_ACCOUNT}) {
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("civiceconomy-test-admin"),
                    "grant-" + serviceIdentity.value() + "-" + capability,
                    serviceIdentity,
                    capability,
                    accountId,
                    "Backup/restore integration fixture"));
        }
    }

    private static DatabaseIdentity identity(String worldId) {
        return new DatabaseIdentity(
                UUID.fromString(worldId),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
