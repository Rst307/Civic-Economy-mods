package org.civiceconomy.platform.neoforge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredDatabaseRestoreOperation;

final class DatabaseRestoreStartup {
    private static final Consumer<DatabaseRestoreActivationPoint> NO_FAULT = ignored -> {};

    private DatabaseRestoreStartup() {}

    static Optional<DatabaseRestoreActivation> activate(
            Path databaseDirectory, DatabaseIdentity identity, Clock clock) {
        return activate(databaseDirectory, identity, clock, NO_FAULT);
    }

    static Optional<DatabaseRestoreActivation> activate(
            Path databaseDirectory,
            DatabaseIdentity identity,
            Clock clock,
            Consumer<DatabaseRestoreActivationPoint> fault) {
        Path directory = Objects.requireNonNull(databaseDirectory, "databaseDirectory")
                .toAbsolutePath()
                .normalize();
        Path pendingManifest = directory.resolve("restore/pending.properties");
        if (!Files.isRegularFile(pendingManifest)) {
            return Optional.empty();
        }
        DatabaseRestoreManifest manifest = DatabaseRestoreManifest.read(pendingManifest);
        try {
            Path source = owned(directory.resolve("backups"), manifest.sourceFileName());
            DatabaseRestoreFiles.requireEvidence(
                    source, manifest.sourceSizeBytes(), manifest.sourceSha256());
            CivicDatabase.validateBackup(source, identity);
            if ("STAGED".equals(manifest.phase())) {
                manifest = prepareReady(directory, identity, clock, pendingManifest, manifest);
            }
            return Optional.of(activateReady(
                    directory, identity, clock, pendingManifest, manifest, fault));
        } catch (RuntimeException failure) {
            auditFailure(directory, identity, manifest, clock, failure);
            throw failure;
        }
    }

    private static void auditFailure(
            Path directory,
            DatabaseIdentity identity,
            DatabaseRestoreManifest manifest,
            Clock clock,
            RuntimeException failure) {
        Path live = directory.resolve("civic.sqlite3");
        Path archived = workDirectory(directory)
                .resolve(manifest.operationId() + ".previous.sqlite3");
        RuntimeException auditFailure = null;
        for (Path databaseFile : new Path[] {live, archived}) {
            if (!Files.isRegularFile(databaseFile)) {
                continue;
            }
            try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
                if (database.databaseRestoreOperation(manifest.operationId()) != null) {
                    database.recordDatabaseRestoreFailure(
                            manifest.operationId(),
                            "civic-restore-startup",
                            failure.toString(),
                            clock.millis());
                    return;
                }
            } catch (RuntimeException audit) {
                if (auditFailure == null) {
                    auditFailure = audit;
                } else {
                    auditFailure.addSuppressed(audit);
                }
            }
        }
        if (auditFailure != null) {
            failure.addSuppressed(auditFailure);
        }
    }

    private static DatabaseRestoreManifest prepareReady(
            Path directory,
            DatabaseIdentity identity,
            Clock clock,
            Path pendingManifest,
            DatabaseRestoreManifest staged) {
        UUID rollbackOperationId = UUID.nameUUIDFromBytes(
                ("civic-restore-rollback:" + staged.operationId())
                        .getBytes(StandardCharsets.UTF_8));
        String rollbackFileName = "civic-rollback-" + staged.operationId() + ".sqlite3";
        Path rollback = owned(directory.resolve("backups"), rollbackFileName);
        if (!Files.isRegularFile(rollback)) {
            Path live = directory.resolve("civic.sqlite3");
            if (!Files.isRegularFile(live)) {
                throw new IllegalStateException(
                        "Cannot prepare rollback because the current Civic database is missing");
            }
            Path pendingRollback = rollback.resolveSibling(rollback.getFileName() + ".pending");
            DatabaseRestoreFiles.deleteIfExists(pendingRollback);
            try (CivicDatabase current = CivicDatabase.open(live, identity)) {
                current.backup(pendingRollback);
            }
            deleteSidecars(live);
            CivicDatabase.validateBackup(pendingRollback, identity);
            DatabaseRestoreFiles.move(pendingRollback, rollback);
        } else {
            CivicDatabase.validateBackup(rollback, identity);
        }
        long rollbackSize = DatabaseRestoreFiles.size(rollback);
        String rollbackSha = DatabaseRestoreFiles.sha256(rollback);
        DatabaseRestoreManifest ready = staged.ready(
                rollbackOperationId,
                rollbackFileName,
                rollbackSize,
                rollbackSha);
        prepareCandidate(directory, identity, clock, ready);
        ready.write(pendingManifest);
        return ready;
    }

    private static DatabaseRestoreActivation activateReady(
            Path directory,
            DatabaseIdentity identity,
            Clock clock,
            Path pendingManifest,
            DatabaseRestoreManifest ready,
            Consumer<DatabaseRestoreActivationPoint> fault) {
        if (!"READY".equals(ready.phase())) {
            throw new IllegalArgumentException("Database restore is not ready for activation");
        }
        Path rollback = owned(directory.resolve("backups"), ready.rollbackFileName());
        DatabaseRestoreFiles.requireEvidence(
                rollback, ready.rollbackSizeBytes(), ready.rollbackSha256());
        CivicDatabase.validateBackup(rollback, identity);
        Path live = directory.resolve("civic.sqlite3");
        Path archived = workDirectory(directory)
                .resolve(ready.operationId() + ".previous.sqlite3");
        Path candidate = candidateFile(directory, ready.operationId());

        if (Files.isRegularFile(live)
                && hasActivationToken(live, identity, ready.operationId())) {
            return finalizeActivation(directory, pendingManifest, archived, ready);
        }

        if (!Files.isRegularFile(candidate)
                || !hasActivationToken(candidate, identity, ready.operationId())) {
            deleteDatabase(candidate);
            prepareCandidate(directory, identity, clock, ready);
        }
        if (Files.isRegularFile(live)) {
            if (Files.exists(archived)) {
                throw new IllegalStateException(
                        "Restore work archive already exists while the live database is present");
            }
            deleteSidecars(live);
            DatabaseRestoreFiles.move(live, archived);
            fault.accept(DatabaseRestoreActivationPoint.AFTER_CURRENT_ARCHIVED);
        } else if (!Files.isRegularFile(archived)) {
            throw new IllegalStateException(
                    "Neither the live database nor its restore work archive exists");
        }

        deleteSidecars(candidate);
        DatabaseRestoreFiles.move(candidate, live);
        fault.accept(DatabaseRestoreActivationPoint.AFTER_CANDIDATE_ACTIVATED);
        if (!hasActivationToken(live, identity, ready.operationId())) {
            throw new IllegalStateException(
                    "Activated Civic database lacks the expected restore token");
        }
        return finalizeActivation(directory, pendingManifest, archived, ready);
    }

    private static void prepareCandidate(
            Path directory,
            DatabaseIdentity identity,
            Clock clock,
            DatabaseRestoreManifest ready) {
        Path source = owned(directory.resolve("backups"), ready.sourceFileName());
        DatabaseRestoreFiles.requireEvidence(
                source, ready.sourceSizeBytes(), ready.sourceSha256());
        CivicDatabase.validateBackup(source, identity);
        Path rollback = owned(directory.resolve("backups"), ready.rollbackFileName());
        DatabaseRestoreFiles.requireEvidence(
                rollback, ready.rollbackSizeBytes(), ready.rollbackSha256());
        Path candidate = candidateFile(directory, ready.operationId());
        deleteDatabase(candidate);
        CivicDatabase.restoreBackup(source, candidate, identity);
        try (CivicDatabase restored = CivicDatabase.open(candidate, identity)) {
            restored.prepareDatabaseBackupOperation(
                    ready.rollbackBackupOperationId(),
                    "civic-restore-lifecycle",
                    "rollback-" + ready.operationId(),
                    ready.rollbackFileName(),
                    "Rollback snapshot for restore " + ready.operationId(),
                    clock.millis());
            restored.commitDatabaseBackupOperation(
                    ready.rollbackBackupOperationId(),
                    ready.rollbackSizeBytes(),
                    ready.rollbackSha256(),
                    clock.millis());
            restored.recordDatabaseRestoreActivation(
                    ready.operationId(),
                    ready.administratorIdentity(),
                    ready.requestId(),
                    ready.sourceBackupOperationId(),
                    ready.sourceFileName(),
                    ready.sourceSizeBytes(),
                    ready.sourceSha256(),
                    ready.rollbackBackupOperationId(),
                    ready.rollbackFileName(),
                    ready.rollbackSizeBytes(),
                    ready.rollbackSha256(),
                    ready.reason(),
                    ready.stagedAtEpochMillis(),
                    clock.millis());
        }
        deleteSidecars(candidate);
        CivicDatabase.validateBackup(candidate, identity);
    }

    private static boolean hasActivationToken(
            Path databaseFile, DatabaseIdentity identity, UUID operationId) {
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            StoredDatabaseRestoreOperation operation =
                    database.databaseRestoreOperation(operationId);
            return operation != null && "ACTIVATED".equals(operation.state());
        }
    }

    private static DatabaseRestoreActivation finalizeActivation(
            Path directory,
            Path pendingManifest,
            Path archived,
            DatabaseRestoreManifest manifest) {
        deleteDatabase(archived);
        Path history = directory.resolve("restore/history")
                .resolve(manifest.operationId() + "-activated.properties");
        if (Files.isRegularFile(pendingManifest)) {
            if (Files.exists(history)) {
                DatabaseRestoreFiles.deleteIfExists(pendingManifest);
            } else {
                DatabaseRestoreManifest.archive(pendingManifest, history);
            }
        }
        return new DatabaseRestoreActivation(
                manifest.operationId(),
                manifest.rollbackBackupOperationId(),
                manifest.rollbackFileName());
    }

    private static Path candidateFile(Path directory, UUID operationId) {
        return workDirectory(directory).resolve(operationId + ".candidate.sqlite3");
    }

    private static Path workDirectory(Path directory) {
        return directory.resolve("restore/work");
    }

    private static Path owned(Path directory, String fileName) {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Path resolved = normalizedDirectory.resolve(fileName).normalize();
        if (!resolved.getParent().equals(normalizedDirectory)) {
            throw new IllegalArgumentException("Database file escaped its owned directory");
        }
        return resolved;
    }

    private static void deleteDatabase(Path databaseFile) {
        DatabaseRestoreFiles.deleteIfExists(databaseFile);
        deleteSidecars(databaseFile);
    }

    private static void deleteSidecars(Path databaseFile) {
        Path parent = databaseFile.getParent();
        String fileName = databaseFile.getFileName().toString();
        DatabaseRestoreFiles.deleteIfExists(parent.resolve(fileName + "-wal"));
        DatabaseRestoreFiles.deleteIfExists(parent.resolve(fileName + "-shm"));
    }
}
