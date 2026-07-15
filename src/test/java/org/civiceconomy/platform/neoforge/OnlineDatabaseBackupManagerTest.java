package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredDatabaseBackupAuditEntry;
import org.civiceconomy.persistence.StoredDatabaseBackupOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnlineDatabaseBackupManagerTest {
    private static final DatabaseIdentity IDENTITY = new DatabaseIdentity(
            UUID.fromString("be6a3d94-107e-4c7a-bc00-e063489ccb54"),
            "0.1.0-probe",
            "1.21-2.3.0.5",
            "2101.1.10",
            "2101.1.20");

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsValidatedIdempotentBackupWithDurableAudit() throws Exception {
        Path backupDirectory = temporaryDirectory.resolve("backups");
        try (CivicDatabase database = open("live.sqlite3")) {
            OnlineDatabaseBackupManager manager = manager(database, backupDirectory, 3, 1_000L);

            StoredDatabaseBackupOperation created =
                    manager.create("civic-admin-console:Server", "manual-1", "Before upgrade");
            StoredDatabaseBackupOperation replay =
                    manager.create("civic-admin-console:Server", "manual-1", "Before upgrade");

            assertEquals(created, replay);
            assertEquals("COMMITTED", created.state());
            Path published = backupDirectory.resolve(created.fileName());
            assertTrue(Files.isRegularFile(published));
            assertTrue(created.sizeBytes() > 0L);
            assertEquals(64, created.sha256().length());
            CivicDatabase.validateBackup(published, IDENTITY);
            assertEquals(
                    List.of("PREPARED", "COMMITTED"),
                    database.databaseBackupAudit(created.operationId()).stream()
                            .map(StoredDatabaseBackupAuditEntry::action)
                            .toList());
        }
    }

    @Test
    void changedReasonCannotReuseAnAdministratorRequestId() {
        try (CivicDatabase database = open("conflict.sqlite3")) {
            OnlineDatabaseBackupManager manager =
                    manager(database, temporaryDirectory.resolve("conflict-backups"), 3, 2_000L);
            manager.create("civic-admin-player:one", "same-request", "First reason");

            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.create(
                            "civic-admin-player:one", "same-request", "Changed reason"));
        }
    }

    @Test
    void successfulPublicationRotatesOnlyBackupsBeyondTheRetentionLimit() {
        Path backupDirectory = temporaryDirectory.resolve("rotating");
        try (CivicDatabase database = open("rotation.sqlite3")) {
            StoredDatabaseBackupOperation first =
                    manager(database, backupDirectory, 2, 1_000L)
                            .create("lifecycle", "scheduled-1", "Scheduled online backup");
            StoredDatabaseBackupOperation second =
                    manager(database, backupDirectory, 2, 2_000L)
                            .create("lifecycle", "scheduled-2", "Scheduled online backup");
            StoredDatabaseBackupOperation third =
                    manager(database, backupDirectory, 2, 3_000L)
                            .create("lifecycle", "scheduled-3", "Scheduled online backup");

            assertFalse(Files.exists(backupDirectory.resolve(first.fileName())));
            assertTrue(Files.isRegularFile(backupDirectory.resolve(second.fileName())));
            assertTrue(Files.isRegularFile(backupDirectory.resolve(third.fileName())));
            assertEquals("RETIRED", database.databaseBackupOperation(first.operationId()).state());
            assertEquals(
                    List.of("PREPARED", "COMMITTED", "RETIRED"),
                    database.databaseBackupAudit(first.operationId()).stream()
                            .map(StoredDatabaseBackupAuditEntry::action)
                            .toList());
        }
    }

    @Test
    void failedNewSnapshotDoesNotRotateTheLastGoodBackupAndIsRecoverable() {
        Path backupDirectory = temporaryDirectory.resolve("failure");
        try (CivicDatabase database = open("failure.sqlite3")) {
            StoredDatabaseBackupOperation good =
                    manager(database, backupDirectory, 1, 1_000L)
                            .create("lifecycle", "scheduled-good", "Scheduled online backup");
            OnlineDatabaseBackupManager failing = new OnlineDatabaseBackupManager(
                    database,
                    backupDirectory,
                    Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC),
                    1,
                    (ignored, destination) -> {
                        throw new IllegalStateException("injected snapshot failure");
                    });

            assertThrows(
                    IllegalStateException.class,
                    () -> failing.create(
                            "lifecycle", "scheduled-failed", "Scheduled online backup"));

            assertTrue(Files.isRegularFile(backupDirectory.resolve(good.fileName())));
            StoredDatabaseBackupOperation pending =
                    database.databaseBackupOperation("lifecycle", "scheduled-failed");
            assertEquals("PREPARED", pending.state());
            assertEquals(
                    List.of("PREPARED", "FAILED"),
                    database.databaseBackupAudit(pending.operationId()).stream()
                            .map(StoredDatabaseBackupAuditEntry::action)
                            .toList());

            OnlineDatabaseBackupManager recovered = manager(database, backupDirectory, 1, 3_000L);
            assertEquals(1, recovered.recoverPending().size());
            assertEquals(
                    "COMMITTED",
                    database.databaseBackupOperation(pending.operationId()).state());
            assertFalse(Files.exists(backupDirectory.resolve(good.fileName())));
        }
    }

    @Test
    void publishedFileIsCommittedDuringStartupRecoveryWithoutAnotherSnapshot() throws Exception {
        Path backupDirectory = temporaryDirectory.resolve("published-crash");
        Files.createDirectories(backupDirectory);
        try (CivicDatabase database = open("published-crash.sqlite3")) {
            StoredDatabaseBackupOperation prepared = database.prepareDatabaseBackupOperation(
                    UUID.fromString("60678803-f183-418c-b5b4-842487a006cb"),
                    "lifecycle",
                    "crash-window",
                    "civic-1000-60678803-f183-418c-b5b4-842487a006cb.sqlite3",
                    "Crash recovery",
                    1_000L);
            Path pending = backupDirectory.resolve(prepared.fileName() + ".pending");
            Path published = backupDirectory.resolve(prepared.fileName());
            database.backup(pending);
            Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE);
            AtomicReference<Path> unexpectedSnapshot = new AtomicReference<>();
            OnlineDatabaseBackupManager recovering = new OnlineDatabaseBackupManager(
                    database,
                    backupDirectory,
                    Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC),
                    3,
                    (ignored, destination) -> unexpectedSnapshot.set(destination));

            assertEquals(List.of(prepared.operationId()), recovering.recoverPending().stream()
                    .map(StoredDatabaseBackupOperation::operationId)
                    .toList());
            assertEquals(null, unexpectedSnapshot.get());
            assertEquals(
                    "COMMITTED",
                    database.databaseBackupOperation(prepared.operationId()).state());
        }
    }

    @Test
    void incompletePublishedFileIsReplacedDuringRecovery() throws Exception {
        Path backupDirectory = temporaryDirectory.resolve("partial-published");
        Files.createDirectories(backupDirectory);
        try (CivicDatabase database = open("partial-published.sqlite3")) {
            StoredDatabaseBackupOperation prepared = database.prepareDatabaseBackupOperation(
                    UUID.fromString("c834c6ea-f937-42a5-885d-93ff3e3758bc"),
                    "lifecycle",
                    "partial-published-window",
                    "civic-1000-c834c6ea-f937-42a5-885d-93ff3e3758bc.sqlite3",
                    "Crash recovery",
                    1_000L);
            Path published = backupDirectory.resolve(prepared.fileName());
            Files.writeString(published, "incomplete SQLite move");

            StoredDatabaseBackupOperation recovered =
                    manager(database, backupDirectory, 3, 2_000L)
                            .recoverPending()
                            .getFirst();

            assertEquals("COMMITTED", recovered.state());
            CivicDatabase.validateBackup(published, IDENTITY);
            assertTrue(Files.size(published) > "incomplete SQLite move".length());
        }
    }

    @Test
    void managerWorkCanBeSerializedOnTheDedicatedSqliteExecutor() throws Exception {
        Path backupDirectory = temporaryDirectory.resolve("async");
        AtomicReference<String> snapshotThread = new AtomicReference<>();
        CivicDatabase database = open("async.sqlite3");
        try (AsyncOnlineTimeWriter writer = new AsyncOnlineTimeWriter(database)) {
            OnlineDatabaseBackupManager manager = new OnlineDatabaseBackupManager(
                    database,
                    backupDirectory,
                    Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC),
                    3,
                    (live, destination) -> {
                        snapshotThread.set(Thread.currentThread().getName());
                        live.backup(destination);
                    });

            writer.submitDatabase(ignored -> manager.create(
                            "lifecycle", "async-request", "Asynchronous backup"))
                    .get(5, TimeUnit.SECONDS);

            assertTrue(snapshotThread.get().startsWith("Civic-Economy-SQLite"));
        }
    }

    private OnlineDatabaseBackupManager manager(
            CivicDatabase database, Path backupDirectory, int retention, long now) {
        return new OnlineDatabaseBackupManager(
                database,
                backupDirectory,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
                retention);
    }

    private CivicDatabase open(String fileName) {
        return CivicDatabase.open(temporaryDirectory.resolve(fileName), IDENTITY);
    }
}
