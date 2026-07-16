package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredDatabaseBackupOperation;
import org.civiceconomy.persistence.StoredDatabaseRestoreOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseRestoreWorkflowTest {
    private static final DatabaseIdentity IDENTITY = new DatabaseIdentity(
            UUID.fromString("cc704fca-0197-4ae4-9356-6147c2600cf2"),
            "0.1.0-probe",
            "1.21-2.3.0.5",
            "2101.1.10",
            "2101.1.20");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("restore-test");
    private static final AccountId TREASURY = new AccountId("nation:restore:treasury");

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagesOneExactCommittedBackupIdempotentlyAndCanCancelBeforeRestart() {
        Path databaseDirectory = temporaryDirectory.resolve("stage");
        createDirectory(databaseDirectory);
        Path databaseFile = databaseDirectory.resolve("civic.sqlite3");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, IDENTITY)) {
            StoredDatabaseBackupOperation source = backup(database, databaseDirectory, 1_000L);
            OnlineDatabaseRestoreManager manager = restoreManager(database, databaseDirectory, 2_000L);

            StoredDatabaseRestoreOperation staged = manager.stage(
                    "civic-admin-console:Server",
                    "stage-restore",
                    source.operationId(),
                    "Restore known-good economy state");
            StoredDatabaseRestoreOperation replay = manager.stage(
                    "civic-admin-console:Server",
                    "stage-restore",
                    source.operationId(),
                    "Restore known-good economy state");

            assertEquals(staged, replay);
            assertEquals("STAGED", staged.state());
            assertEquals(source.operationId(), staged.sourceBackupOperationId());
            assertEquals(source.sha256(), staged.sourceSha256());
            assertTrue(Files.isRegularFile(
                    databaseDirectory.resolve("restore/pending.properties")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.stage(
                            "civic-admin-console:Server",
                            "stage-restore",
                            source.operationId(),
                            "Changed reason"));
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.stage(
                            "civic-admin-console:Server",
                            "another-request",
                            source.operationId(),
                            "Second active restore"));

            StoredDatabaseRestoreOperation cancelled = manager.cancel(
                    "civic-admin-console:Server",
                    "cancel-stage",
                    staged.operationId(),
                    "Operator withdrew restore");
            assertEquals("CANCELLED", cancelled.state());
            assertFalse(Files.exists(databaseDirectory.resolve("restore/pending.properties")));
            assertTrue(Files.isRegularFile(databaseDirectory
                    .resolve("restore/history")
                    .resolve(staged.operationId() + "-cancelled.properties")));
            try {
                Files.delete(databaseDirectory.resolve("backups").resolve(source.fileName()));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Unable to remove replay source fixture", failure);
            }
            assertEquals(
                    cancelled,
                    manager.stage(
                            "civic-admin-console:Server",
                            "stage-restore",
                            source.operationId(),
                            "Restore known-good economy state"));
        }
    }

    @Test
    void tamperedOrRetiredBackupCannotBeStaged() throws Exception {
        Path databaseDirectory = temporaryDirectory.resolve("reject");
        createDirectory(databaseDirectory);
        Path databaseFile = databaseDirectory.resolve("civic.sqlite3");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, IDENTITY)) {
            StoredDatabaseBackupOperation source = backup(database, databaseDirectory, 1_000L);
            Path sourceFile = databaseDirectory.resolve("backups").resolve(source.fileName());
            Files.writeString(sourceFile, "tampered");

            assertThrows(
                    IllegalStateException.class,
                    () -> restoreManager(database, databaseDirectory, 2_000L)
                            .stage("admin", "tampered", source.operationId(), "Reject tamper"));
        }

        Path retiredDirectory = temporaryDirectory.resolve("retired");
        createDirectory(retiredDirectory);
        try (CivicDatabase database =
                CivicDatabase.open(retiredDirectory.resolve("civic.sqlite3"), IDENTITY)) {
            OnlineDatabaseBackupManager backups = new OnlineDatabaseBackupManager(
                    database, retiredDirectory.resolve("backups"), clock(1_000L), 1);
            StoredDatabaseBackupOperation retired =
                    backups.create("lifecycle", "old", "Old backup");
            backups.create("lifecycle", "new", "New backup");

            assertEquals("RETIRED", database.databaseBackupOperation(retired.operationId()).state());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> restoreManager(database, retiredDirectory, 2_000L)
                            .stage("admin", "retired", retired.operationId(), "Reject retired"));
        }
    }

    @Test
    void stagedSourceIsPinnedOutsideNormalBackupRetention() {
        Path databaseDirectory = temporaryDirectory.resolve("pinned");
        createDirectory(databaseDirectory);
        try (CivicDatabase database =
                CivicDatabase.open(databaseDirectory.resolve("civic.sqlite3"), IDENTITY)) {
            OnlineDatabaseBackupManager backups = new OnlineDatabaseBackupManager(
                    database, databaseDirectory.resolve("backups"), clock(1_000L), 1);
            StoredDatabaseBackupOperation source =
                    backups.create("admin", "restore-source", "Restore source");
            restoreManager(database, databaseDirectory, 2_000L)
                    .stage("admin", "pin-source", source.operationId(), "Pin restore source");

            StoredDatabaseBackupOperation newer =
                    backups.create("admin", "newer-backup", "Newer lifecycle backup");

            assertEquals("COMMITTED", database.databaseBackupOperation(source.operationId()).state());
            assertEquals("COMMITTED", database.databaseBackupOperation(newer.operationId()).state());
            assertTrue(Files.isRegularFile(
                    databaseDirectory.resolve("backups").resolve(source.fileName())));
        }
    }

    @Test
    void startupValidationFailureLeavesLiveDatabaseUntouchedAndAuditsFailure() throws Exception {
        Path databaseDirectory = temporaryDirectory.resolve("startup-reject");
        createDirectory(databaseDirectory);
        Path databaseFile = databaseDirectory.resolve("civic.sqlite3");
        UUID restoreOperationId;
        Path sourceFile;
        try (CivicDatabase database = CivicDatabase.open(databaseFile, IDENTITY)) {
            reserve(database, "live-state", 500L);
            StoredDatabaseBackupOperation source = backup(database, databaseDirectory, 1_000L);
            restoreOperationId = restoreManager(database, databaseDirectory, 2_000L)
                    .stage("admin", "reject-at-startup", source.operationId(), "Reject tamper")
                    .operationId();
            sourceFile = databaseDirectory.resolve("backups").resolve(source.fileName());
        }
        Files.writeString(sourceFile, "tampered after staging");

        assertThrows(
                IllegalStateException.class,
                () -> DatabaseRestoreStartup.activate(
                        databaseDirectory, IDENTITY, clock(3_000L)));

        try (CivicDatabase unchanged = CivicDatabase.open(databaseFile, IDENTITY)) {
            assertEquals(500L, reserved(unchanged));
            assertEquals(
                    "STAGED",
                    unchanged.databaseRestoreOperation(restoreOperationId).state());
            assertEquals(
                    java.util.List.of("STAGED", "FAILED"),
                    unchanged.databaseRestoreAudit(restoreOperationId).stream()
                            .map(entry -> entry.action())
                            .toList());
        }
    }

    @Test
    void nextStartupActivatesStagedSnapshotAndRegistersUsableRollbackBackup() {
        Path databaseDirectory = temporaryDirectory.resolve("activate");
        createDirectory(databaseDirectory);
        Path databaseFile = databaseDirectory.resolve("civic.sqlite3");
        UUID restoreOperationId;
        try (CivicDatabase database = CivicDatabase.open(databaseFile, IDENTITY)) {
            reserve(database, "before-backup", 300L);
            StoredDatabaseBackupOperation source = backup(database, databaseDirectory, 1_000L);
            reserve(database, "after-backup", 200L);
            assertEquals(500L, reserved(database));
            restoreOperationId = restoreManager(database, databaseDirectory, 2_000L)
                    .stage("admin", "activate", source.operationId(), "Activate snapshot")
                    .operationId();
        }

        DatabaseRestoreActivation activation = DatabaseRestoreStartup.activate(
                        databaseDirectory, IDENTITY, clock(3_000L))
                .orElseThrow();

        assertEquals(restoreOperationId, activation.operationId());
        assertTrue(Files.isRegularFile(databaseDirectory
                .resolve("restore/history")
                .resolve(restoreOperationId + "-activated.properties")));
        assertFalse(Files.exists(databaseDirectory.resolve("restore/pending.properties")));
        try (CivicDatabase restored = CivicDatabase.open(databaseFile, IDENTITY)) {
            assertEquals(64, restored.schemaVersion());
            assertEquals(300L, reserved(restored));
            StoredDatabaseRestoreOperation operation =
                    restored.databaseRestoreOperation(restoreOperationId);
            assertEquals("ACTIVATED", operation.state());
            assertEquals(
                    java.util.List.of("STAGED", "ACTIVATED"),
                    restored.databaseRestoreAudit(restoreOperationId).stream()
                            .map(entry -> entry.action())
                            .toList());
            assertNotNull(operation.rollbackBackupOperationId());
            StoredDatabaseBackupOperation rollback = restored.databaseBackupOperation(
                    operation.rollbackBackupOperationId());
            assertEquals("COMMITTED", rollback.state());
            assertEquals(operation.rollbackFileName(), rollback.fileName());
            Path rollbackFile = databaseDirectory.resolve("backups").resolve(rollback.fileName());
            CivicDatabase.validateBackup(rollbackFile, IDENTITY);
            try (CivicDatabase rollbackDatabase = CivicDatabase.open(rollbackFile, IDENTITY)) {
                assertEquals(500L, reserved(rollbackDatabase));
            }
        }
    }

    @Test
    void crashAfterCurrentDatabaseIsArchivedCompletesOnNextStartup() {
        RestoreFixture fixture = stagedFixture("archive-crash");

        assertThrows(
                InjectedRestoreCrash.class,
                () -> DatabaseRestoreStartup.activate(
                        fixture.databaseDirectory(),
                        IDENTITY,
                        clock(3_000L),
                        point -> {
                            if (point == DatabaseRestoreActivationPoint.AFTER_CURRENT_ARCHIVED) {
                                throw new InjectedRestoreCrash();
                            }
                        }));
        assertFalse(Files.exists(fixture.databaseDirectory().resolve("civic.sqlite3")));

        DatabaseRestoreStartup.activate(fixture.databaseDirectory(), IDENTITY, clock(4_000L))
                .orElseThrow();
        try (CivicDatabase restored = CivicDatabase.open(
                fixture.databaseDirectory().resolve("civic.sqlite3"), IDENTITY)) {
            assertEquals(300L, reserved(restored));
            assertEquals(
                    "ACTIVATED",
                    restored.databaseRestoreOperation(fixture.restoreOperationId()).state());
        }
    }

    @Test
    void crashAfterCandidateMoveIsRecognizedByDurableRestoreToken() {
        RestoreFixture fixture = stagedFixture("activation-crash");

        assertThrows(
                InjectedRestoreCrash.class,
                () -> DatabaseRestoreStartup.activate(
                        fixture.databaseDirectory(),
                        IDENTITY,
                        clock(3_000L),
                        point -> {
                            if (point == DatabaseRestoreActivationPoint.AFTER_CANDIDATE_ACTIVATED) {
                                throw new InjectedRestoreCrash();
                            }
                        }));
        assertTrue(Files.isRegularFile(fixture.databaseDirectory().resolve("civic.sqlite3")));

        DatabaseRestoreActivation recovered = DatabaseRestoreStartup.activate(
                        fixture.databaseDirectory(), IDENTITY, clock(4_000L))
                .orElseThrow();
        assertEquals(fixture.restoreOperationId(), recovered.operationId());
        try (CivicDatabase restored = CivicDatabase.open(
                fixture.databaseDirectory().resolve("civic.sqlite3"), IDENTITY)) {
            assertEquals(300L, reserved(restored));
        }
    }

    private RestoreFixture stagedFixture(String name) {
        Path databaseDirectory = temporaryDirectory.resolve(name);
        createDirectory(databaseDirectory);
        Path databaseFile = databaseDirectory.resolve("civic.sqlite3");
        UUID operationId;
        try (CivicDatabase database = CivicDatabase.open(databaseFile, IDENTITY)) {
            reserve(database, "before-backup", 300L);
            StoredDatabaseBackupOperation source = backup(database, databaseDirectory, 1_000L);
            reserve(database, "after-backup", 200L);
            operationId = restoreManager(database, databaseDirectory, 2_000L)
                    .stage("admin", "activate-" + name, source.operationId(), "Crash fixture")
                    .operationId();
        }
        return new RestoreFixture(databaseDirectory, operationId);
    }

    private StoredDatabaseBackupOperation backup(
            CivicDatabase database, Path databaseDirectory, long now) {
        return new OnlineDatabaseBackupManager(
                        database, databaseDirectory.resolve("backups"), clock(now), 8)
                .create("admin", "backup-" + UUID.randomUUID(), "Restore source");
    }

    private OnlineDatabaseRestoreManager restoreManager(
            CivicDatabase database, Path databaseDirectory, long now) {
        return new OnlineDatabaseRestoreManager(database, databaseDirectory, clock(now));
    }

    private static void reserve(CivicDatabase database, String requestId, long amount) {
        FiscalLedger ledger = ledger(database);
        ledger.reserve(new ReserveFunds(
                SERVICE,
                requestId,
                TREASURY,
                MoneyAmount.ofMinorUnits(amount),
                "Restore workflow fixture"));
    }

    private static long reserved(CivicDatabase database) {
        return ledger(database).reservedBalance(SERVICE, TREASURY).minorUnits();
    }

    private static FiscalLedger ledger(CivicDatabase database) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        if (database.fiscalService(SERVICE.value()) == null) {
            authorization.register(new RegisterFiscalService(
                    SERVICE, "civiceconomy-tests", "Restore workflow test"));
            for (FiscalCapability capability :
                    new FiscalCapability[] {
                        FiscalCapability.RESERVE_FUNDS, FiscalCapability.READ_ACCOUNT
                    }) {
                authorization.grant(new GrantFiscalCapability(
                        new ServiceIdentity("restore-test-admin"),
                        "grant-" + capability,
                        SERVICE,
                        capability,
                        TREASURY,
                        "Restore workflow fixture"));
            }
        }
        return FiscalLedger.authorized(
                database,
                ignored -> MoneyAmount.ofMinorUnits(10_000L),
                FiscalTestSessions.open(database, SERVICE, "civiceconomy-tests"));
    }

    private static Clock clock(long epochMillis) {
        return Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to create restore test directory", failure);
        }
    }

    private record RestoreFixture(Path databaseDirectory, UUID restoreOperationId) {}

    private static final class InjectedRestoreCrash extends RuntimeException {}
}
