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
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ReserveFunds;
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
            FiscalLedger ledger = ledger(live);
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
            assertEquals(MoneyAmount.ofMinorUnits(500), ledger.reservedBalance(treasury));
        }

        try (CivicDatabase backup = CivicDatabase.open(backupFile, identity)) {
            assertEquals(identity, backup.identity());
            assertEquals(12, backup.schemaVersion());
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger(backup).reservedBalance(treasury));
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
            ledger(live).reserve(new ReserveFunds(
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
            assertEquals(12, restored.schemaVersion());
            assertEquals(MoneyAmount.ofMinorUnits(450), ledger(restored).reservedBalance(treasury));
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
            statement.execute("PRAGMA user_version = 13");
        }

        assertThrows(
                UnsupportedDatabaseVersionException.class,
                () -> CivicDatabase.restoreBackup(backupFile, restoredFile, identity));
        assertFalse(java.nio.file.Files.exists(restoredFile));
    }

    private static FiscalLedger ledger(CivicDatabase database) {
        return new FiscalLedger(database, ignored -> MoneyAmount.ofMinorUnits(1_000));
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
