package org.civiceconomy.platform.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredDatabaseBackupOperation;

final class OnlineDatabaseBackupManager {
    private final CivicDatabase database;
    private final Path backupDirectory;
    private final Clock clock;
    private final int retention;
    private final BackupSnapshotWriter snapshotWriter;

    OnlineDatabaseBackupManager(
            CivicDatabase database, Path backupDirectory, Clock clock, int retention) {
        this(database, backupDirectory, clock, retention, CivicDatabase::backup);
    }

    OnlineDatabaseBackupManager(
            CivicDatabase database,
            Path backupDirectory,
            Clock clock,
            int retention,
            BackupSnapshotWriter snapshotWriter) {
        this.database = Objects.requireNonNull(database, "database");
        this.backupDirectory = Objects.requireNonNull(backupDirectory, "backupDirectory")
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention < 1) {
            throw new IllegalArgumentException("Database backup retention must be positive");
        }
        this.retention = retention;
        this.snapshotWriter = Objects.requireNonNull(snapshotWriter, "snapshotWriter");
    }

    StoredDatabaseBackupOperation create(
            String administratorIdentity, String requestId, String reason) {
        requireText(administratorIdentity, "administratorIdentity");
        requireText(requestId, "requestId");
        requireText(reason, "reason");
        StoredDatabaseBackupOperation replay =
                database.databaseBackupOperation(administratorIdentity, requestId);
        if (replay != null) {
            if (!replay.reason().equals(reason)) {
                throw new IllegalArgumentException(
                        "Database backup request replay changed its reason");
            }
            return "PREPARED".equals(replay.state()) ? publish(replay) : replay;
        }

        long now = clock.millis();
        UUID operationId = UUID.randomUUID();
        String fileName = "civic-" + now + "-" + operationId + ".sqlite3";
        StoredDatabaseBackupOperation prepared = database.prepareDatabaseBackupOperation(
                operationId,
                administratorIdentity,
                requestId,
                fileName,
                reason,
                now);
        return publish(prepared);
    }

    List<StoredDatabaseBackupOperation> recoverPending() {
        List<StoredDatabaseBackupOperation> recovered = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (StoredDatabaseBackupOperation pending : database.pendingDatabaseBackupOperations()) {
            try {
                recovered.add(publish(pending));
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return List.copyOf(recovered);
    }

    private StoredDatabaseBackupOperation publish(StoredDatabaseBackupOperation operation) {
        Path published = resolveFile(operation.fileName());
        Path pending = resolveFile(operation.fileName() + ".pending");
        try {
            Files.createDirectories(backupDirectory);
            if (Files.isRegularFile(published)) {
                try {
                    CivicDatabase.validateBackup(published, database.identity());
                } catch (RuntimeException invalidPublishedFile) {
                    database.recordDatabaseBackupFailure(
                            operation.operationId(),
                            "Discarded invalid published snapshot: " + invalidPublishedFile,
                            clock.millis());
                    Files.delete(published);
                    preparePendingSnapshot(operation, pending);
                    moveIntoPlace(pending, published);
                }
            } else {
                preparePendingSnapshot(operation, pending);
                moveIntoPlace(pending, published);
            }
            long size = Files.size(published);
            String sha256 = sha256(published);
            StoredDatabaseBackupOperation committed = database.commitDatabaseBackupOperation(
                    operation.operationId(), size, sha256, clock.millis());
            rotateCommittedBackups();
            return committed;
        } catch (IOException | RuntimeException failure) {
            try {
                database.recordDatabaseBackupFailure(
                        operation.operationId(), failure.toString(), clock.millis());
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Unable to publish Civic database backup " + operation.fileName(), failure);
        }
    }

    private void preparePendingSnapshot(
            StoredDatabaseBackupOperation operation, Path pending) throws IOException {
        if (Files.exists(pending)) {
            try {
                CivicDatabase.validateBackup(pending, database.identity());
                return;
            } catch (RuntimeException invalid) {
                database.recordDatabaseBackupFailure(
                        operation.operationId(),
                        "Discarded invalid pending snapshot: " + invalid,
                        clock.millis());
                Files.delete(pending);
            }
        }
        snapshotWriter.write(database, pending);
        CivicDatabase.validateBackup(pending, database.identity());
    }

    private void rotateCommittedBackups() {
        List<StoredDatabaseBackupOperation> committed = database.committedDatabaseBackupOperations();
        var stagedRestore = database.pendingDatabaseRestoreOperation();
        UUID pinnedOperationId = stagedRestore == null
                ? null
                : stagedRestore.sourceBackupOperationId();
        List<StoredDatabaseBackupOperation> removable = committed.stream()
                .filter(operation -> !operation.operationId().equals(pinnedOperationId))
                .toList();
        int removeCount = removable.size() - retention;
        for (int index = 0; index < removeCount; index++) {
            StoredDatabaseBackupOperation old = removable.get(index);
            try {
                Files.deleteIfExists(resolveFile(old.fileName()));
                Files.deleteIfExists(resolveFile(old.fileName() + ".pending"));
                database.retireDatabaseBackupOperation(
                        old.operationId(), "Retention limit " + retention, clock.millis());
            } catch (IOException | RuntimeException failure) {
                database.recordDatabaseBackupFailure(
                        old.operationId(), "Rotation deferred: " + failure, clock.millis());
            }
        }
    }

    private Path resolveFile(String fileName) {
        Path resolved = backupDirectory.resolve(fileName).normalize();
        if (!resolved.getParent().equals(backupDirectory)) {
            throw new IllegalArgumentException("Backup file escaped its owned directory");
        }
        return resolved;
    }

    private static void moveIntoPlace(Path pending, Path published) throws IOException {
        try {
            Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, published);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @FunctionalInterface
    interface BackupSnapshotWriter {
        void write(CivicDatabase database, Path destination);
    }
}
