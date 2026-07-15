package org.civiceconomy.persistence;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.territory.TerritoryMaintenanceCandidate;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenancePriorityDecision;
import org.civiceconomy.territory.TerritoryMaintenancePriorityPolicy;
import org.sqlite.SQLiteConnection;

public final class CivicDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 50;
    private static final long TERRITORY_FORCE_LOAD_GRACE_MILLIS = 86_400_000L;

    private final Connection connection;

    private CivicDatabase(Connection connection) {
        this.connection = connection;
    }

    public static CivicDatabase open(Path databaseFile, DatabaseIdentity identity) {
        loadSqliteDriver();
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            CivicDatabase database = new CivicDatabase(connection);
            try {
                database.configure();
                database.requireSupportedVersion();
                database.initialize(identity);
                DatabaseIdentity storedIdentity = database.identity();
                if (!storedIdentity.equals(identity)) {
                    throw new DatabaseIdentityMismatchException(identity, storedIdentity);
                }
                return database;
            } catch (RuntimeException | SQLException failure) {
                connection.close();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open Civic database " + databaseFile, failure);
        }
    }

    private static void loadSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC", true, CivicDatabase.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The pinned SQLite JDBC driver is unavailable", failure);
        }
    }

    public String journalMode() {
        return queryText("PRAGMA journal_mode");
    }

    public int schemaVersion() {
        return queryInteger("PRAGMA user_version");
    }

    public DatabaseIdentity identity() {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT * FROM civic_identity WHERE singleton = 1")) {
            if (!result.next()) {
                throw new IllegalStateException("Civic database identity is missing");
            }
            return new DatabaseIdentity(
                    UUID.fromString(result.getString("world_id")),
                    result.getString("civic_version"),
                    result.getString("lc_version"),
                    result.getString("ftb_teams_version"),
                    result.getString("ftb_chunks_version"));
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Civic database identity", failure);
        }
    }

    public synchronized StoredFiscalService registerFiscalService(
            String serviceIdentity,
            String ownerModId,
            String displayName,
            String administratorIdentity,
            String requestId,
            String reason,
            long registeredAtEpochMillis) {
        StoredFiscalService replay = fiscalServiceRegistration(administratorIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredFiscalService existing = fiscalService(serviceIdentity);
        if (existing != null) {
            return existing;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_service (
                    service_identity, owner_mod_id, display_name, registered_at_epoch_millis
                ) VALUES (?, ?, ?, ?)
                """);
                    PreparedStatement audit = connection.prepareStatement("""
                INSERT INTO fiscal_service_registration_audit (
                    service_identity, administrator_identity, request_id, reason,
                    registered_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
                insert.setString(1, serviceIdentity);
                insert.setString(2, ownerModId);
                insert.setString(3, displayName);
                insert.setLong(4, registeredAtEpochMillis);
                insert.executeUpdate();
                audit.setString(1, serviceIdentity);
                audit.setString(2, administratorIdentity);
                audit.setString(3, requestId);
                audit.setString(4, reason);
                audit.setLong(5, registeredAtEpochMillis);
                audit.executeUpdate();
            }
            connection.commit();
            return fiscalService(serviceIdentity);
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Unable to register fiscal service " + serviceIdentity, failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to restore Civic database auto-commit", failure);
            }
        }
    }

    public synchronized StoredFiscalService fiscalService(String serviceIdentity) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT s.*, a.administrator_identity, a.request_id, a.reason
                FROM fiscal_service s
                JOIN fiscal_service_registration_audit a
                  ON a.service_identity = s.service_identity
                WHERE s.service_identity = ?
                """)) {
            query.setString(1, serviceIdentity);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredFiscalService(
                        result.getString("service_identity"),
                        result.getString("owner_mod_id"),
                        result.getString("display_name"),
                        result.getString("administrator_identity"),
                        result.getString("request_id"),
                        result.getString("reason"),
                        result.getLong("registered_at_epoch_millis"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal service " + serviceIdentity, failure);
        }
    }

    public synchronized StoredFiscalService fiscalServiceRegistration(
            String administratorIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT s.*, a.administrator_identity, a.request_id, a.reason
                FROM fiscal_service_registration_audit a
                JOIN fiscal_service s ON s.service_identity = a.service_identity
                WHERE a.administrator_identity = ? AND a.request_id = ?
                """)) {
            query.setString(1, administratorIdentity);
            query.setString(2, requestId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredFiscalService(
                        result.getString("service_identity"),
                        result.getString("owner_mod_id"),
                        result.getString("display_name"),
                        result.getString("administrator_identity"),
                        result.getString("request_id"),
                        result.getString("reason"),
                        result.getLong("registered_at_epoch_millis"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal service registration request", failure);
        }
    }

    public synchronized List<StoredFiscalService> fiscalServices() {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT s.*, a.administrator_identity, a.request_id, a.reason
                FROM fiscal_service s
                JOIN fiscal_service_registration_audit a
                  ON a.service_identity = s.service_identity
                ORDER BY s.service_identity
                """)) {
            List<StoredFiscalService> services = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    services.add(new StoredFiscalService(
                            result.getString("service_identity"),
                            result.getString("owner_mod_id"),
                            result.getString("display_name"),
                            result.getString("administrator_identity"),
                            result.getString("request_id"),
                            result.getString("reason"),
                            result.getLong("registered_at_epoch_millis")));
                }
            }
            return List.copyOf(services);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list fiscal services", failure);
        }
    }

    public synchronized boolean hasFiscalCapability(
            String serviceIdentity, String capability, String accountId) {
        return fiscalCapabilityGrant(serviceIdentity, capability, accountId) != null;
    }

    public synchronized StoredFiscalCapabilityGrant grantFiscalCapability(
            UUID grantId,
            String administratorIdentity,
            String requestId,
            String serviceIdentity,
            String capability,
            String accountId,
            String reason,
            long grantedAtEpochMillis) {
        StoredFiscalCapabilityGrant replay = fiscalCapabilityGrant(administratorIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredFiscalCapabilityGrant existing =
                fiscalCapabilityGrant(serviceIdentity, capability, accountId);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_service_grant (
                    grant_id, administrator_identity, request_id, service_identity,
                    capability, account_id, reason, granted_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, grantId.toString());
            insert.setString(2, administratorIdentity);
            insert.setString(3, requestId);
            insert.setString(4, serviceIdentity);
            insert.setString(5, capability);
            insert.setString(6, accountId);
            insert.setString(7, reason);
            insert.setLong(8, grantedAtEpochMillis);
            insert.executeUpdate();
            return fiscalCapabilityGrant(administratorIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to grant " + capability + " to fiscal service " + serviceIdentity,
                    failure);
        }
    }

    public synchronized StoredFiscalCapabilityGrant fiscalCapabilityGrant(
            String administratorIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM fiscal_service_grant
                WHERE administrator_identity = ? AND request_id = ?
                """)) {
            query.setString(1, administratorIdentity);
            query.setString(2, requestId);
            return readFiscalCapabilityGrant(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal capability grant request", failure);
        }
    }

    public synchronized StoredFiscalCapabilityGrant fiscalCapabilityGrant(
            String serviceIdentity, String capability, String accountId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT g.* FROM fiscal_service_grant g
                LEFT JOIN fiscal_service_grant_revocation r ON r.grant_id = g.grant_id
                WHERE g.service_identity = ? AND g.capability = ? AND g.account_id = ?
                  AND r.grant_id IS NULL
                ORDER BY g.granted_at_epoch_millis DESC, g.rowid DESC
                LIMIT 1
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, capability);
            query.setString(3, accountId);
            return readFiscalCapabilityGrant(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal capability scope", failure);
        }
    }

    public synchronized StoredFiscalCapabilityGrant fiscalCapabilityGrant(UUID grantId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM fiscal_service_grant WHERE grant_id = ?
                """)) {
            query.setString(1, grantId.toString());
            return readFiscalCapabilityGrant(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal capability grant " + grantId, failure);
        }
    }

    public synchronized List<StoredFiscalCapabilityGrant> fiscalCapabilityGrants(
            String serviceIdentity) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM fiscal_service_grant
                WHERE service_identity = ?
                ORDER BY granted_at_epoch_millis, rowid
                """)) {
            query.setString(1, serviceIdentity);
            List<StoredFiscalCapabilityGrant> grants = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    grants.add(new StoredFiscalCapabilityGrant(
                            UUID.fromString(result.getString("grant_id")),
                            result.getString("administrator_identity"),
                            result.getString("request_id"),
                            result.getString("service_identity"),
                            result.getString("capability"),
                            result.getString("account_id"),
                            result.getString("reason"),
                            result.getLong("granted_at_epoch_millis")));
                }
            }
            return List.copyOf(grants);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to list fiscal grants for " + serviceIdentity, failure);
        }
    }

    public synchronized StoredFiscalCapabilityRevocation revokeFiscalCapability(
            UUID revocationId,
            UUID grantId,
            String administratorIdentity,
            String requestId,
            String reason,
            long revokedAtEpochMillis) {
        StoredFiscalCapabilityRevocation replay =
                fiscalCapabilityRevocation(administratorIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredFiscalCapabilityRevocation existing = fiscalCapabilityRevocation(grantId);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_service_grant_revocation (
                    revocation_id, grant_id, administrator_identity, request_id,
                    reason, revoked_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, revocationId.toString());
            insert.setString(2, grantId.toString());
            insert.setString(3, administratorIdentity);
            insert.setString(4, requestId);
            insert.setString(5, reason);
            insert.setLong(6, revokedAtEpochMillis);
            insert.executeUpdate();
            return fiscalCapabilityRevocation(administratorIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to revoke fiscal capability grant " + grantId, failure);
        }
    }

    public synchronized StoredFiscalCapabilityRevocation fiscalCapabilityRevocation(
            String administratorIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM fiscal_service_grant_revocation
                WHERE administrator_identity = ? AND request_id = ?
                """)) {
            query.setString(1, administratorIdentity);
            query.setString(2, requestId);
            return readFiscalCapabilityRevocation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal revocation request", failure);
        }
    }

    public synchronized StoredFiscalCapabilityRevocation fiscalCapabilityRevocation(UUID grantId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM fiscal_service_grant_revocation WHERE grant_id = ?
                """)) {
            query.setString(1, grantId.toString());
            return readFiscalCapabilityRevocation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal grant revocation " + grantId, failure);
        }
    }

    public synchronized StoredFiscalServiceStateChange changeFiscalServiceState(
            UUID changeId,
            String administratorIdentity,
            String requestId,
            String serviceIdentity,
            String state,
            String reason,
            long changedAtEpochMillis) {
        StoredFiscalServiceStateChange replay =
                fiscalServiceStateChange(administratorIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_service_state_change (
                    change_id, administrator_identity, request_id, service_identity,
                    state, reason, changed_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, changeId.toString());
            insert.setString(2, administratorIdentity);
            insert.setString(3, requestId);
            insert.setString(4, serviceIdentity);
            insert.setString(5, state);
            insert.setString(6, reason);
            insert.setLong(7, changedAtEpochMillis);
            insert.executeUpdate();
            return fiscalServiceStateChange(administratorIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to change fiscal service state for " + serviceIdentity, failure);
        }
    }

    public synchronized StoredFiscalServiceStateChange fiscalServiceStateChange(
            String administratorIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM fiscal_service_state_change
                WHERE administrator_identity = ? AND request_id = ?
                """)) {
            query.setString(1, administratorIdentity);
            query.setString(2, requestId);
            return readFiscalServiceStateChange(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal service state request", failure);
        }
    }

    public synchronized boolean isFiscalServiceEnabled(String serviceIdentity) {
        return "ENABLED".equals(fiscalServiceState(serviceIdentity));
    }

    public synchronized String fiscalServiceState(String serviceIdentity) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT state FROM fiscal_service_state_change
                WHERE service_identity = ?
                ORDER BY changed_at_epoch_millis DESC, rowid DESC
                LIMIT 1
                """)) {
            query.setString(1, serviceIdentity);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString("state") : "ENABLED";
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal service state " + serviceIdentity, failure);
        }
    }

    public synchronized void backup(Path backupFile) {
        Path destination = backupFile.toAbsolutePath();
        if (Files.exists(destination)) {
            throw new IllegalArgumentException("Backup destination already exists: " + destination);
        }
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            int result = sqlite.getDatabase().backup("main", destination.toString(), null);
            if (result != 0) {
                throw new IllegalStateException("SQLite backup failed with result code " + result);
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("Unable to create Civic database backup " + destination, failure);
        }
    }

    public synchronized StoredDatabaseBackupOperation prepareDatabaseBackupOperation(
            UUID operationId,
            String administratorIdentity,
            String requestId,
            String fileName,
            String reason,
            long preparedAtEpochMillis) {
        StoredDatabaseBackupOperation replay =
                databaseBackupOperation(administratorIdentity, requestId);
        if (replay != null) {
            if (!replay.fileName().equals(fileName) || !replay.reason().equals(reason)) {
                throw new IllegalArgumentException(
                        "Database backup request replay changed its immutable payload");
            }
            return replay;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO database_backup_operation (
                            operation_id, administrator_identity, request_id,
                            file_name, reason, state, prepared_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?)
                        """);
                    PreparedStatement audit = connection.prepareStatement("""
                        INSERT INTO database_backup_audit (
                            audit_id, operation_id, action, detail,
                            recorded_at_epoch_millis
                        ) VALUES (?, ?, 'PREPARED', ?, ?)
                        """)) {
                insert.setString(1, operationId.toString());
                insert.setString(2, administratorIdentity);
                insert.setString(3, requestId);
                insert.setString(4, fileName);
                insert.setString(5, reason);
                insert.setLong(6, preparedAtEpochMillis);
                insert.executeUpdate();
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, operationId.toString());
                audit.setString(3, reason);
                audit.setLong(4, preparedAtEpochMillis);
                audit.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return databaseBackupOperation(operationId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to prepare database backup", failure);
        }
    }

    public synchronized StoredDatabaseBackupOperation commitDatabaseBackupOperation(
            UUID operationId, long sizeBytes, String sha256, long committedAtEpochMillis) {
        StoredDatabaseBackupOperation existing = requireDatabaseBackupOperation(operationId);
        if (!"PREPARED".equals(existing.state())) {
            if (existing.sizeBytes() != sizeBytes || !sha256.equals(existing.sha256())) {
                throw new IllegalArgumentException(
                        "Database backup commit replay changed its immutable file evidence");
            }
            return existing;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE database_backup_operation
                        SET state = 'COMMITTED', size_bytes = ?, sha256 = ?,
                            committed_at_epoch_millis = ?
                        WHERE operation_id = ? AND state = 'PREPARED'
                        """);
                    PreparedStatement audit = connection.prepareStatement("""
                        INSERT INTO database_backup_audit (
                            audit_id, operation_id, action, detail,
                            recorded_at_epoch_millis
                        ) VALUES (?, ?, 'COMMITTED', ?, ?)
                        """)) {
                update.setLong(1, sizeBytes);
                update.setString(2, sha256);
                update.setLong(3, committedAtEpochMillis);
                update.setString(4, operationId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Database backup state changed before commit " + operationId);
                }
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, operationId.toString());
                audit.setString(3, "size=" + sizeBytes + " sha256=" + sha256);
                audit.setLong(4, committedAtEpochMillis);
                audit.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return databaseBackupOperation(operationId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to commit database backup " + operationId, failure);
        }
    }

    public synchronized StoredDatabaseBackupOperation retireDatabaseBackupOperation(
            UUID operationId, String detail, long retiredAtEpochMillis) {
        StoredDatabaseBackupOperation existing = requireDatabaseBackupOperation(operationId);
        if ("RETIRED".equals(existing.state())) {
            return existing;
        }
        if (!"COMMITTED".equals(existing.state())) {
            throw new IllegalStateException("Only a committed database backup may be retired");
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE database_backup_operation
                        SET state = 'RETIRED', retired_at_epoch_millis = ?
                        WHERE operation_id = ? AND state = 'COMMITTED'
                        """);
                    PreparedStatement audit = connection.prepareStatement("""
                        INSERT INTO database_backup_audit (
                            audit_id, operation_id, action, detail,
                            recorded_at_epoch_millis
                        ) VALUES (?, ?, 'RETIRED', ?, ?)
                        """)) {
                update.setLong(1, retiredAtEpochMillis);
                update.setString(2, operationId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Database backup state changed before retirement " + operationId);
                }
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, operationId.toString());
                audit.setString(3, detail);
                audit.setLong(4, retiredAtEpochMillis);
                audit.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return databaseBackupOperation(operationId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to retire database backup " + operationId, failure);
        }
    }

    public synchronized void recordDatabaseBackupFailure(
            UUID operationId, String detail, long recordedAtEpochMillis) {
        requireDatabaseBackupOperation(operationId);
        try (PreparedStatement audit = connection.prepareStatement("""
                INSERT INTO database_backup_audit (
                    audit_id, operation_id, action, detail, recorded_at_epoch_millis
                ) VALUES (?, ?, 'FAILED', ?, ?)
                """)) {
            audit.setString(1, UUID.randomUUID().toString());
            audit.setString(2, operationId.toString());
            audit.setString(3, detail);
            audit.setLong(4, recordedAtEpochMillis);
            audit.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to audit database backup failure", failure);
        }
    }

    public synchronized StoredDatabaseBackupOperation databaseBackupOperation(
            String administratorIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_backup_operation
                WHERE administrator_identity = ? AND request_id = ?
                """)) {
            query.setString(1, administratorIdentity);
            query.setString(2, requestId);
            return readDatabaseBackupOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read database backup request", failure);
        }
    }

    public synchronized StoredDatabaseBackupOperation databaseBackupOperation(UUID operationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_backup_operation WHERE operation_id = ?
                """)) {
            query.setString(1, operationId.toString());
            return readDatabaseBackupOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read database backup " + operationId, failure);
        }
    }

    public synchronized List<StoredDatabaseBackupOperation> pendingDatabaseBackupOperations() {
        return databaseBackupOperations("PREPARED", true);
    }

    public synchronized List<StoredDatabaseBackupOperation> committedDatabaseBackupOperations() {
        return databaseBackupOperations("COMMITTED", true);
    }

    public synchronized List<StoredDatabaseBackupOperation> databaseBackupOperations() {
        return databaseBackupOperations(null, false);
    }

    public synchronized List<StoredDatabaseBackupAuditEntry> databaseBackupAudit(UUID operationId) {
        List<StoredDatabaseBackupAuditEntry> entries = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_backup_audit
                WHERE operation_id = ?
                ORDER BY recorded_at_epoch_millis, rowid
                """)) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    entries.add(new StoredDatabaseBackupAuditEntry(
                            UUID.fromString(result.getString("audit_id")),
                            UUID.fromString(result.getString("operation_id")),
                            result.getString("action"),
                            result.getString("detail"),
                            result.getLong("recorded_at_epoch_millis")));
                }
            }
            return List.copyOf(entries);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read database backup audit", failure);
        }
    }

    public synchronized StoredDatabaseRestoreOperation prepareDatabaseRestoreOperation(
            UUID operationId,
            String administratorIdentity,
            String requestId,
            UUID sourceBackupOperationId,
            String sourceFileName,
            long sourceSizeBytes,
            String sourceSha256,
            String reason,
            long stagedAtEpochMillis) {
        StoredDatabaseRestoreOperation replay =
                databaseRestoreOperation(administratorIdentity, requestId);
        if (replay != null) {
            if (!replay.sourceBackupOperationId().equals(sourceBackupOperationId)
                    || !replay.sourceFileName().equals(sourceFileName)
                    || replay.sourceSizeBytes() != sourceSizeBytes
                    || !replay.sourceSha256().equals(sourceSha256)
                    || !replay.reason().equals(reason)) {
                throw new IllegalArgumentException(
                        "Database restore request replay changed its immutable payload");
            }
            return replay;
        }
        if (pendingDatabaseRestoreOperation() != null) {
            throw new IllegalStateException("Another database restore is already staged");
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO database_restore_operation (
                            operation_id, administrator_identity, request_id,
                            source_backup_operation_id, source_file_name,
                            source_size_bytes, source_sha256, state, reason,
                            staged_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'STAGED', ?, ?)
                        """);
                    PreparedStatement audit = connection.prepareStatement("""
                        INSERT INTO database_restore_audit (
                            audit_id, operation_id, action, actor_identity,
                            detail, recorded_at_epoch_millis
                        ) VALUES (?, ?, 'STAGED', ?, ?, ?)
                        """)) {
                insert.setString(1, operationId.toString());
                insert.setString(2, administratorIdentity);
                insert.setString(3, requestId);
                insert.setString(4, sourceBackupOperationId.toString());
                insert.setString(5, sourceFileName);
                insert.setLong(6, sourceSizeBytes);
                insert.setString(7, sourceSha256);
                insert.setString(8, reason);
                insert.setLong(9, stagedAtEpochMillis);
                insert.executeUpdate();
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, operationId.toString());
                audit.setString(3, administratorIdentity);
                audit.setString(4, reason);
                audit.setLong(5, stagedAtEpochMillis);
                audit.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return databaseRestoreOperation(operationId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to stage database restore", failure);
        }
    }

    public synchronized StoredDatabaseRestoreOperation cancelDatabaseRestoreOperation(
            UUID operationId,
            String administratorIdentity,
            String requestId,
            String reason,
            long cancelledAtEpochMillis) {
        StoredDatabaseRestoreOperation cancellationReplay =
                databaseRestoreCancellation(administratorIdentity, requestId);
        if (cancellationReplay != null) {
            if (!cancellationReplay.operationId().equals(operationId)
                    || !reason.equals(cancellationReplay.cancellationReason())) {
                throw new IllegalArgumentException(
                        "Database restore cancellation replay changed its immutable payload");
            }
            return cancellationReplay;
        }
        StoredDatabaseRestoreOperation existing = requireDatabaseRestoreOperation(operationId);
        if (!"STAGED".equals(existing.state())) {
            throw new IllegalStateException("Only a staged database restore may be cancelled");
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE database_restore_operation
                        SET state = 'CANCELLED',
                            cancellation_administrator_identity = ?,
                            cancellation_request_id = ?, cancellation_reason = ?,
                            cancelled_at_epoch_millis = ?
                        WHERE operation_id = ? AND state = 'STAGED'
                        """);
                    PreparedStatement audit = connection.prepareStatement("""
                        INSERT INTO database_restore_audit (
                            audit_id, operation_id, action, actor_identity,
                            detail, recorded_at_epoch_millis
                        ) VALUES (?, ?, 'CANCELLED', ?, ?, ?)
                        """)) {
                update.setString(1, administratorIdentity);
                update.setString(2, requestId);
                update.setString(3, reason);
                update.setLong(4, cancelledAtEpochMillis);
                update.setString(5, operationId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Database restore state changed before cancellation " + operationId);
                }
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, operationId.toString());
                audit.setString(3, administratorIdentity);
                audit.setString(4, reason);
                audit.setLong(5, cancelledAtEpochMillis);
                audit.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return databaseRestoreOperation(operationId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to cancel database restore " + operationId, failure);
        }
    }

    public synchronized StoredDatabaseRestoreOperation recordDatabaseRestoreActivation(
            UUID operationId,
            String administratorIdentity,
            String requestId,
            UUID sourceBackupOperationId,
            String sourceFileName,
            long sourceSizeBytes,
            String sourceSha256,
            UUID rollbackBackupOperationId,
            String rollbackFileName,
            long rollbackSizeBytes,
            String rollbackSha256,
            String reason,
            long stagedAtEpochMillis,
            long activatedAtEpochMillis) {
        StoredDatabaseRestoreOperation existing = databaseRestoreOperation(operationId);
        if (existing != null && "ACTIVATED".equals(existing.state())) {
            requireRestoreActivationPayload(
                    existing,
                    administratorIdentity,
                    requestId,
                    sourceBackupOperationId,
                    sourceFileName,
                    sourceSizeBytes,
                    sourceSha256,
                    rollbackBackupOperationId,
                    rollbackFileName,
                    rollbackSizeBytes,
                    rollbackSha256,
                    reason,
                    stagedAtEpochMillis);
            return existing;
        }
        if (existing != null && !"STAGED".equals(existing.state())) {
            throw new IllegalStateException(
                    "Database restore cannot activate from " + existing.state());
        }
        try {
            connection.setAutoCommit(false);
            if (existing == null) {
                try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO database_restore_operation (
                                operation_id, administrator_identity, request_id,
                                source_backup_operation_id, source_file_name,
                                source_size_bytes, source_sha256,
                                rollback_backup_operation_id, rollback_file_name,
                                rollback_size_bytes, rollback_sha256,
                                state, reason, staged_at_epoch_millis,
                                activated_at_epoch_millis
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                      'ACTIVATED', ?, ?, ?)
                            """);
                        PreparedStatement stagedAudit = connection.prepareStatement("""
                            INSERT INTO database_restore_audit (
                                audit_id, operation_id, action, actor_identity,
                                detail, recorded_at_epoch_millis
                            ) VALUES (?, ?, 'STAGED', ?, ?, ?)
                            """)) {
                    bindRestoreActivation(
                            insert,
                            operationId,
                            administratorIdentity,
                            requestId,
                            sourceBackupOperationId,
                            sourceFileName,
                            sourceSizeBytes,
                            sourceSha256,
                            rollbackBackupOperationId,
                            rollbackFileName,
                            rollbackSizeBytes,
                            rollbackSha256,
                            reason,
                            stagedAtEpochMillis,
                            activatedAtEpochMillis);
                    insert.executeUpdate();
                    stagedAudit.setString(1, UUID.randomUUID().toString());
                    stagedAudit.setString(2, operationId.toString());
                    stagedAudit.setString(3, administratorIdentity);
                    stagedAudit.setString(4, reason);
                    stagedAudit.setLong(5, stagedAtEpochMillis);
                    stagedAudit.executeUpdate();
                }
            } else {
                requireRestoreActivationPayload(
                        existing,
                        administratorIdentity,
                        requestId,
                        sourceBackupOperationId,
                        sourceFileName,
                        sourceSizeBytes,
                        sourceSha256,
                        null,
                        null,
                        0L,
                        null,
                        reason,
                        stagedAtEpochMillis);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE database_restore_operation
                        SET rollback_backup_operation_id = ?, rollback_file_name = ?,
                            rollback_size_bytes = ?, rollback_sha256 = ?,
                            state = 'ACTIVATED', activated_at_epoch_millis = ?
                        WHERE operation_id = ? AND state = 'STAGED'
                        """)) {
                    update.setString(1, rollbackBackupOperationId.toString());
                    update.setString(2, rollbackFileName);
                    update.setLong(3, rollbackSizeBytes);
                    update.setString(4, rollbackSha256);
                    update.setLong(5, activatedAtEpochMillis);
                    update.setString(6, operationId.toString());
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "Database restore state changed before activation " + operationId);
                    }
                }
            }
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO database_restore_audit (
                        audit_id, operation_id, action, actor_identity,
                        detail, recorded_at_epoch_millis
                    ) VALUES (?, ?, 'ACTIVATED', 'civic-restore-startup', ?, ?)
                    """)) {
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, operationId.toString());
                audit.setString(3, "rollback=" + rollbackFileName);
                audit.setLong(4, activatedAtEpochMillis);
                audit.executeUpdate();
            }
            connection.commit();
            return databaseRestoreOperation(operationId);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException(
                    "Unable to record database restore activation " + operationId, failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException(
                        "Unable to restore database auto-commit after activation", failure);
            }
        }
    }

    public synchronized StoredDatabaseRestoreOperation databaseRestoreOperation(UUID operationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_restore_operation WHERE operation_id = ?
                """)) {
            query.setString(1, operationId.toString());
            return readDatabaseRestoreOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read database restore " + operationId, failure);
        }
    }

    public synchronized StoredDatabaseRestoreOperation databaseRestoreOperation(
            String administratorIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_restore_operation
                WHERE administrator_identity = ? AND request_id = ?
                """)) {
            query.setString(1, administratorIdentity);
            query.setString(2, requestId);
            return readDatabaseRestoreOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read database restore request", failure);
        }
    }

    public synchronized StoredDatabaseRestoreOperation pendingDatabaseRestoreOperation() {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_restore_operation
                WHERE state = 'STAGED'
                ORDER BY staged_at_epoch_millis, rowid
                LIMIT 1
                """)) {
            return readDatabaseRestoreOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read staged database restore", failure);
        }
    }

    public synchronized List<StoredDatabaseRestoreOperation> databaseRestoreOperations() {
        List<StoredDatabaseRestoreOperation> operations = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_restore_operation
                ORDER BY staged_at_epoch_millis DESC, rowid DESC
                """)) {
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    operations.add(readDatabaseRestoreOperation(result));
                }
            }
            return List.copyOf(operations);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list database restores", failure);
        }
    }

    public synchronized List<StoredDatabaseRestoreAuditEntry> databaseRestoreAudit(
            UUID operationId) {
        List<StoredDatabaseRestoreAuditEntry> entries = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_restore_audit
                WHERE operation_id = ?
                ORDER BY recorded_at_epoch_millis, rowid
                """)) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    entries.add(new StoredDatabaseRestoreAuditEntry(
                            UUID.fromString(result.getString("audit_id")),
                            UUID.fromString(result.getString("operation_id")),
                            result.getString("action"),
                            result.getString("actor_identity"),
                            result.getString("detail"),
                            result.getLong("recorded_at_epoch_millis")));
                }
            }
            return List.copyOf(entries);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read database restore audit", failure);
        }
    }

    public synchronized void recordDatabaseRestoreFailure(
            UUID operationId,
            String actorIdentity,
            String detail,
            long recordedAtEpochMillis) {
        requireDatabaseRestoreOperation(operationId);
        try (PreparedStatement audit = connection.prepareStatement("""
                INSERT INTO database_restore_audit (
                    audit_id, operation_id, action, actor_identity,
                    detail, recorded_at_epoch_millis
                ) VALUES (?, ?, 'FAILED', ?, ?, ?)
                """)) {
            audit.setString(1, UUID.randomUUID().toString());
            audit.setString(2, operationId.toString());
            audit.setString(3, actorIdentity);
            audit.setString(4, detail);
            audit.setLong(5, recordedAtEpochMillis);
            audit.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to audit database restore failure " + operationId, failure);
        }
    }

    public static void restoreBackup(
            Path backupFile, Path destinationFile, DatabaseIdentity expectedIdentity) {
        Path source = backupFile.toAbsolutePath();
        Path destination = destinationFile.toAbsolutePath();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Backup file does not exist: " + source);
        }
        if (Files.exists(destination)) {
            throw new IllegalArgumentException("Restore destination already exists: " + destination);
        }
        validateBackup(source, expectedIdentity);

        Path parent = destination.getParent();
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".restore-" + UUID.randomUUID() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, temporary);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Unable to restore Civic database to " + destination, failure);
        }
    }

    public synchronized StoredNationApplication createNationApplication(
            UUID applicationId,
            String serviceIdentity,
            String requestId,
            UUID ftbTeamId,
            UUID applicantPlayerId,
            long createdAtEpochMillis,
            long expiresAtEpochMillis,
            java.util.Set<UUID> candidatePlayerIds) {
        StoredNationApplication replay = nationApplicationRegistration(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO nation_application (
                    application_id, service_identity, request_id, ftb_team_id,
                    applicant_player_id, created_at_epoch_millis, expires_at_epoch_millis, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """);
                    PreparedStatement candidate = connection.prepareStatement("""
                INSERT INTO nation_application_candidate (
                    application_id, player_id, affiliated_at_epoch_millis
                ) VALUES (?, ?, ?)
                """)) {
                insert.setString(1, applicationId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, ftbTeamId.toString());
                insert.setString(5, applicantPlayerId.toString());
                insert.setLong(6, createdAtEpochMillis);
                insert.setLong(7, expiresAtEpochMillis);
                insert.executeUpdate();
                for (UUID playerId : candidatePlayerIds) {
                    candidate.setString(1, applicationId.toString());
                    candidate.setString(2, playerId.toString());
                    candidate.setLong(3, createdAtEpochMillis);
                    candidate.addBatch();
                }
                candidate.executeBatch();
            }
            connection.commit();
            return nationApplication(applicationId);
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException(
                    "Unable to create Nation Application for FTB Team " + ftbTeamId, failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to restore Civic database auto-commit", failure);
            }
        }
    }

    public synchronized StoredNationApplication nationApplicationRegistration(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readNationApplication(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation Application request", failure);
        }
    }

    public synchronized StoredNationApplication nationApplication(UUID applicationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application WHERE application_id = ?
                """)) {
            query.setString(1, applicationId.toString());
            return readNationApplication(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Nation Application " + applicationId, failure);
        }
    }

    public synchronized StoredNationApplication pendingNationApplicationByFtbTeam(UUID ftbTeamId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application
                WHERE ftb_team_id = ? AND state = 'PENDING'
                """)) {
            query.setString(1, ftbTeamId.toString());
            return readNationApplication(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read pending Nation Application for FTB Team " + ftbTeamId,
                    failure);
        }
    }

    public synchronized List<StoredNationApplication> pendingNationApplicationsExpiringAtOrBefore(
            long expiresAtEpochMillis) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application
                WHERE state = 'PENDING' AND expires_at_epoch_millis <= ?
                ORDER BY expires_at_epoch_millis, application_id
                """)) {
            query.setLong(1, expiresAtEpochMillis);
            try (ResultSet result = query.executeQuery()) {
                List<StoredNationApplication> applications = new ArrayList<>();
                while (result.next()) {
                    applications.add(storedNationApplication(result));
                }
                return List.copyOf(applications);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read due Nation Applications at " + expiresAtEpochMillis,
                    failure);
        }
    }

    public synchronized List<StoredNationApplicationCandidate> nationApplicationCandidates(
            UUID applicationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application_candidate
                WHERE application_id = ?
                ORDER BY affiliated_at_epoch_millis, player_id
                """)) {
            query.setString(1, applicationId.toString());
            List<StoredNationApplicationCandidate> candidates = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    candidates.add(new StoredNationApplicationCandidate(
                            UUID.fromString(result.getString("application_id")),
                            UUID.fromString(result.getString("player_id")),
                            result.getLong("affiliated_at_epoch_millis"),
                            result.getObject("ended_at_epoch_millis") == null
                                    ? null
                                    : result.getLong("ended_at_epoch_millis")));
                }
            }
            return List.copyOf(candidates);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to list Nation Application candidates " + applicationId, failure);
        }
    }

    public synchronized List<StoredNationApplicationEvidence> claimNationApplicationEvidence(
            UUID applicationId,
            long windowStartEpochMillis,
            long observedUntilEpochMillis,
            long claimedAtEpochMillis) {
        StoredNationApplication application = nationApplication(applicationId);
        if (application == null) {
            throw new IllegalArgumentException("Unknown Nation Application " + applicationId);
        }
        if (!"PENDING".equals(application.state())) {
            throw new IllegalStateException(
                    "Nation Application " + applicationId + " is not PENDING");
        }
        long applicationEnd = Math.min(
                observedUntilEpochMillis, application.expiresAtEpochMillis());
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement intervals = connection.prepareStatement("""
                    SELECT oi.interval_id, oi.player_id,
                           oi.started_at_epoch_millis, oi.ended_at_epoch_millis,
                           candidate.affiliated_at_epoch_millis,
                           candidate.ended_at_epoch_millis
                    FROM nation_application_candidate candidate
                    JOIN online_time_interval oi ON oi.player_id = candidate.player_id
                    WHERE candidate.application_id = ?
                    ORDER BY oi.started_at_epoch_millis, oi.interval_id
                    """);
                    PreparedStatement claim = connection.prepareStatement("""
                    INSERT INTO nation_application_evidence (
                        interval_id, application_id, player_id,
                        attributed_start_epoch_millis, attributed_end_epoch_millis,
                        claimed_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(interval_id) DO NOTHING
                    """)) {
                intervals.setString(1, applicationId.toString());
                try (ResultSet result = intervals.executeQuery()) {
                    while (result.next()) {
                        long attributedStart = Math.max(
                                Math.max(windowStartEpochMillis,
                                        application.createdAtEpochMillis()),
                                Math.max(result.getLong("started_at_epoch_millis"),
                                        result.getLong("affiliated_at_epoch_millis")));
                        long candidateEnd = result.getObject("ended_at_epoch_millis") == null
                                ? applicationEnd
                                : Math.min(applicationEnd,
                                        result.getLong("ended_at_epoch_millis"));
                        long attributedEnd = Math.min(
                                result.getLong("ended_at_epoch_millis"), candidateEnd);
                        if (attributedEnd <= attributedStart) {
                            continue;
                        }
                        claim.setString(1, result.getString("interval_id"));
                        claim.setString(2, applicationId.toString());
                        claim.setString(3, result.getString("player_id"));
                        claim.setLong(4, attributedStart);
                        claim.setLong(5, attributedEnd);
                        claim.setLong(6, claimedAtEpochMillis);
                        claim.addBatch();
                    }
                }
                claim.executeBatch();
            }
            connection.commit();
            return nationApplicationEvidence(applicationId);
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException(
                    "Unable to claim evidence for Nation Application " + applicationId,
                    failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to restore Civic database auto-commit", failure);
            }
        }
    }

    public synchronized List<StoredNationApplicationEvidence> nationApplicationEvidence(
            UUID applicationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT application_id, player_id,
                       SUM(attributed_end_epoch_millis - attributed_start_epoch_millis)
                           AS attributed_millis
                FROM nation_application_evidence
                WHERE application_id = ?
                GROUP BY application_id, player_id
                ORDER BY player_id
                """)) {
            query.setString(1, applicationId.toString());
            List<StoredNationApplicationEvidence> evidence = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    evidence.add(new StoredNationApplicationEvidence(
                            UUID.fromString(result.getString("application_id")),
                            UUID.fromString(result.getString("player_id")),
                            result.getLong("attributed_millis")));
                }
            }
            return List.copyOf(evidence);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read evidence for Nation Application " + applicationId,
                    failure);
        }
    }

    public synchronized StoredNationApplicationTransition nationApplicationTransitionRegistration(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application_transition
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readNationApplicationTransition(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation Application transition", failure);
        }
    }

    public synchronized StoredNationApplicationTransition transitionNationApplication(
            UUID transitionId,
            UUID applicationId,
            String serviceIdentity,
            String requestId,
            UUID actorPlayerId,
            Long observationWindowMillis,
            String toState,
            String reason,
            long effectiveAtEpochMillis,
            long transitionedAtEpochMillis) {
        StoredNationApplicationTransition replay =
                nationApplicationTransitionRegistration(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement transition = connection.prepareStatement("""
                    INSERT INTO nation_application_transition (
                        transition_id, application_id, service_identity, request_id,
                        actor_player_id, observation_window_millis, from_state, to_state,
                        reason, effective_at_epoch_millis, transitioned_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                    """);
                    PreparedStatement closeCandidates = connection.prepareStatement("""
                    UPDATE nation_application_candidate
                    SET ended_at_epoch_millis = ?
                    WHERE application_id = ? AND ended_at_epoch_millis IS NULL
                    """);
                    PreparedStatement updateApplication = connection.prepareStatement("""
                    UPDATE nation_application SET state = ?
                    WHERE application_id = ? AND state = 'PENDING'
                    """)) {
                transition.setString(1, transitionId.toString());
                transition.setString(2, applicationId.toString());
                transition.setString(3, serviceIdentity);
                transition.setString(4, requestId);
                transition.setString(5, actorPlayerId == null ? null : actorPlayerId.toString());
                if (observationWindowMillis == null) {
                    transition.setNull(6, java.sql.Types.BIGINT);
                } else {
                    transition.setLong(6, observationWindowMillis);
                }
                transition.setString(7, toState);
                transition.setString(8, reason);
                transition.setLong(9, effectiveAtEpochMillis);
                transition.setLong(10, transitionedAtEpochMillis);
                transition.executeUpdate();

                closeCandidates.setLong(1, effectiveAtEpochMillis);
                closeCandidates.setString(2, applicationId.toString());
                closeCandidates.executeUpdate();

                updateApplication.setString(1, toState);
                updateApplication.setString(2, applicationId.toString());
                if (updateApplication.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Nation Application " + applicationId + " is not PENDING");
                }
            }
            connection.commit();
            return nationApplicationTransitionRegistration(serviceIdentity, requestId);
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException(
                    "Unable to transition Nation Application " + applicationId, failure);
        } catch (RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to restore Civic database auto-commit", failure);
            }
        }
    }

    public synchronized StoredNationActivation nationActivationRegistration(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application_activation
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readNationActivation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation activation request", failure);
        }
    }

    public synchronized StoredNationActivation prepareNationActivation(
            UUID applicationId,
            String serviceIdentity,
            String requestId,
            UUID nationId,
            UUID ftbTeamId,
            String treasuryAccountId,
            String capitalDimensionId,
            int capitalChunkX,
            int capitalChunkZ,
            String reason,
            int minimumEffectiveCandidates,
            boolean minimumCandidateBypassAllowed,
            long observationWindowMillis,
            long preparedAtEpochMillis) {
        StoredNationActivation replay = nationActivationRegistration(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO nation_application_activation (
                    application_id, service_identity, request_id, nation_id, ftb_team_id,
                    treasury_account_id, capital_dimension_id, capital_chunk_x,
                    capital_chunk_z, reason, minimum_effective_candidates,
                    minimum_candidate_bypass_allowed, observation_window_millis,
                    state, prepared_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """)) {
            insert.setString(1, applicationId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, nationId.toString());
            insert.setString(5, ftbTeamId.toString());
            insert.setString(6, treasuryAccountId);
            insert.setString(7, capitalDimensionId);
            insert.setInt(8, capitalChunkX);
            insert.setInt(9, capitalChunkZ);
            insert.setString(10, reason);
            insert.setInt(11, minimumEffectiveCandidates);
            insert.setInt(12, minimumCandidateBypassAllowed ? 1 : 0);
            insert.setLong(13, observationWindowMillis);
            insert.setLong(14, preparedAtEpochMillis);
            insert.executeUpdate();
            return nationActivation(applicationId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to prepare Nation activation " + applicationId, failure);
        }
    }

    public synchronized StoredNationActivation markNationTreasuryProvisioned(
            UUID applicationId, long provisionedAtEpochMillis) {
        StoredNationActivation activation = nationActivation(applicationId);
        if (activation == null) {
            throw new IllegalArgumentException("Unknown Nation activation " + applicationId);
        }
        if (!"PREPARED".equals(activation.state())) {
            return activation;
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE nation_application_activation
                SET state = 'TREASURY_PROVISIONED', treasury_provisioned_at_epoch_millis = ?
                WHERE application_id = ? AND state = 'PREPARED'
                """)) {
            update.setLong(1, provisionedAtEpochMillis);
            update.setString(2, applicationId.toString());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Nation activation state changed before Treasury provision was recorded "
                                + applicationId);
            }
            return nationActivation(applicationId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to record National Treasury provision " + applicationId, failure);
        }
    }

    public synchronized StoredNationActivation commitNationActivation(
            UUID applicationId, long committedAtEpochMillis) {
        StoredNationActivation activation = nationActivation(applicationId);
        if (activation == null) {
            throw new IllegalArgumentException("Unknown Nation activation " + applicationId);
        }
        if ("COMMITTED".equals(activation.state())) {
            return activation;
        }
        if (!"TREASURY_PROVISIONED".equals(activation.state())) {
            throw new IllegalStateException(
                    "National Treasury is not provisioned for application " + applicationId);
        }
        StoredNationApplication application = nationApplication(applicationId);
        List<StoredNationApplicationCandidate> candidates =
                nationApplicationCandidates(applicationId);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement nation = connection.prepareStatement("""
                    INSERT INTO nation_registry (
                        nation_id, service_identity, request_id, ftb_team_id,
                        registered_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?)
                    """);
                    PreparedStatement citizenship = connection.prepareStatement("""
                    INSERT INTO citizenship_period (
                        citizenship_id, player_id, nation_id, joined_at_epoch_millis,
                        join_service_identity, join_request_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """);
                    PreparedStatement capital = connection.prepareStatement("""
                    INSERT INTO nation_capital (
                        nation_id, dimension_id, chunk_x, chunk_z,
                        established_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?)
                    """);
                    PreparedStatement transition = connection.prepareStatement("""
                    INSERT INTO nation_application_transition (
                        transition_id, application_id, service_identity, request_id,
                        actor_player_id, observation_window_millis, from_state, to_state,
                        reason, effective_at_epoch_millis, transitioned_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 'ACTIVATED', ?, ?, ?)
                    """);
                    PreparedStatement closeCandidates = connection.prepareStatement("""
                    UPDATE nation_application_candidate
                    SET ended_at_epoch_millis = ?
                    WHERE application_id = ? AND ended_at_epoch_millis IS NULL
                    """);
                    PreparedStatement updateApplication = connection.prepareStatement("""
                    UPDATE nation_application SET state = 'ACTIVATED'
                    WHERE application_id = ? AND state = 'PENDING'
                    """);
                    PreparedStatement updateActivation = connection.prepareStatement("""
                    UPDATE nation_application_activation
                    SET state = 'COMMITTED', committed_at_epoch_millis = ?
                    WHERE application_id = ? AND state = 'TREASURY_PROVISIONED'
                    """)) {
                nation.setString(1, activation.nationId().toString());
                nation.setString(2, activation.serviceIdentity());
                nation.setString(3, activation.requestId());
                nation.setString(4, activation.ftbTeamId().toString());
                nation.setLong(5, committedAtEpochMillis);
                nation.executeUpdate();

                for (StoredNationApplicationCandidate candidate : candidates) {
                    if (candidate.endedAtEpochMillis() != null) {
                        continue;
                    }
                    citizenship.setString(1, UUID.randomUUID().toString());
                    citizenship.setString(2, candidate.playerId().toString());
                    citizenship.setString(3, activation.nationId().toString());
                    citizenship.setLong(4, committedAtEpochMillis);
                    citizenship.setString(5, activation.serviceIdentity());
                    citizenship.setString(
                            6, activation.requestId() + ":citizenship:" + candidate.playerId());
                    citizenship.addBatch();
                }
                citizenship.executeBatch();

                capital.setString(1, activation.nationId().toString());
                capital.setString(2, activation.capitalDimensionId());
                capital.setInt(3, activation.capitalChunkX());
                capital.setInt(4, activation.capitalChunkZ());
                capital.setLong(5, committedAtEpochMillis);
                capital.executeUpdate();

                transition.setString(1, UUID.randomUUID().toString());
                transition.setString(2, applicationId.toString());
                transition.setString(3, activation.serviceIdentity());
                transition.setString(4, activation.requestId());
                transition.setString(5, application.applicantPlayerId().toString());
                transition.setLong(6, activation.observationWindowMillis());
                transition.setString(7, activation.reason());
                transition.setLong(8, committedAtEpochMillis);
                transition.setLong(9, committedAtEpochMillis);
                transition.executeUpdate();

                closeCandidates.setLong(1, committedAtEpochMillis);
                closeCandidates.setString(2, applicationId.toString());
                closeCandidates.executeUpdate();

                updateApplication.setString(1, applicationId.toString());
                if (updateApplication.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Nation Application state changed before activation " + applicationId);
                }
                updateActivation.setLong(1, committedAtEpochMillis);
                updateActivation.setString(2, applicationId.toString());
                if (updateActivation.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Nation activation state changed before commit " + applicationId);
                }
            }
            connection.commit();
            return nationActivation(applicationId);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException(
                    "Unable to commit Nation activation " + applicationId, failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to restore Civic database auto-commit", failure);
            }
        }
    }

    private StoredNationActivation nationActivation(UUID applicationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_application_activation WHERE application_id = ?
                """)) {
            query.setString(1, applicationId.toString());
            return readNationActivation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Nation activation " + applicationId, failure);
        }
    }

    public synchronized StoredNation registerNation(
            UUID nationId,
            String serviceIdentity,
            String requestId,
            UUID ftbTeamId,
            long registeredAtEpochMillis) {
        StoredNation replay = nationRegistration(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredNation bound = nationByFtbTeam(ftbTeamId);
        if (bound != null) {
            throw new IllegalStateException(
                    "FTB Team " + ftbTeamId + " is already bound to Nation " + bound.nationId());
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO nation_registry (
                    nation_id, service_identity, request_id, ftb_team_id, registered_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, nationId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, ftbTeamId.toString());
            insert.setLong(5, registeredAtEpochMillis);
            insert.executeUpdate();
            return nation(nationId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to register Nation " + nationId, failure);
        }
    }

    public synchronized StoredNation nationRegistration(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_registry WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readNation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation registration request", failure);
        }
    }

    public synchronized StoredNation nation(UUID nationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_registry WHERE nation_id = ?
                """)) {
            query.setString(1, nationId.toString());
            return readNation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation " + nationId, failure);
        }
    }

    public synchronized StoredNation nationByFtbTeam(UUID ftbTeamId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_registry WHERE ftb_team_id = ?
                """)) {
            query.setString(1, ftbTeamId.toString());
            return readNation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read FTB Team binding " + ftbTeamId, failure);
        }
    }

    public synchronized List<StoredNation> registeredNations() {
        List<StoredNation> nations = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_registry
                ORDER BY registered_at_epoch_millis, nation_id
                """)) {
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    nations.add(readNation(result));
                }
            }
            return List.copyOf(nations);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list registered Nations", failure);
        }
    }

    public synchronized StoredNationCapital nationCapital(UUID nationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_capital WHERE nation_id = ?
                """)) {
            query.setString(1, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredNationCapital(
                        UUID.fromString(result.getString("nation_id")),
                        result.getString("dimension_id"),
                        result.getInt("chunk_x"),
                        result.getInt("chunk_z"),
                        result.getLong("established_at_epoch_millis"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation Capital " + nationId, failure);
        }
    }

    public synchronized StoredCitizenship joinCitizenship(
            UUID citizenshipId,
            String serviceIdentity,
            String requestId,
            UUID playerId,
            UUID nationId,
            long joinedAtEpochMillis) {
        StoredCitizenship replay = citizenshipJoin(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO citizenship_period (
                    citizenship_id, player_id, nation_id, joined_at_epoch_millis,
                    join_service_identity, join_request_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, citizenshipId.toString());
            insert.setString(2, playerId.toString());
            insert.setString(3, nationId.toString());
            insert.setLong(4, joinedAtEpochMillis);
            insert.setString(5, serviceIdentity);
            insert.setString(6, requestId);
            insert.executeUpdate();
            return citizenship(citizenshipId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to join Citizenship for player " + playerId, failure);
        }
    }

    public synchronized StoredCitizenship citizenshipJoin(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_period
                WHERE join_service_identity = ? AND join_request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readCitizenship(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Citizenship join request", failure);
        }
    }

    public synchronized StoredCitizenship currentCitizenship(UUID playerId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_period
                WHERE player_id = ? AND ended_at_epoch_millis IS NULL
                """)) {
            query.setString(1, playerId.toString());
            return readCitizenship(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read current Citizenship for player " + playerId, failure);
        }
    }

    public synchronized List<StoredCitizenship> currentCitizenships(UUID nationId) {
        List<StoredCitizenship> current = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_period
                WHERE nation_id = ? AND ended_at_epoch_millis IS NULL
                ORDER BY joined_at_epoch_millis, citizenship_id
                """)) {
            query.setString(1, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    current.add(readCitizenship(result));
                }
            }
            return List.copyOf(current);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read current Citizenships for Nation " + nationId, failure);
        }
    }

    public synchronized StoredCitizenshipCorrectionGrace startCitizenshipCorrectionGrace(
            UUID graceId,
            UUID citizenshipId,
            UUID playerId,
            UUID nationId,
            UUID ftbTeamId,
            String serviceIdentity,
            String requestId,
            String reason,
            long startedAtEpochMillis,
            long deadlineEpochMillis) {
        StoredCitizenshipCorrectionGrace replay =
                citizenshipCorrectionGraceStart(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO citizenship_correction_grace (
                    grace_id, citizenship_id, player_id, nation_id, ftb_team_id,
                    start_service_identity, start_request_id, reason,
                    started_at_epoch_millis, deadline_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, graceId.toString());
            insert.setString(2, citizenshipId.toString());
            insert.setString(3, playerId.toString());
            insert.setString(4, nationId.toString());
            insert.setString(5, ftbTeamId.toString());
            insert.setString(6, serviceIdentity);
            insert.setString(7, requestId);
            insert.setString(8, reason);
            insert.setLong(9, startedAtEpochMillis);
            insert.setLong(10, deadlineEpochMillis);
            insert.executeUpdate();
            return citizenshipCorrectionGrace(graceId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to start Citizenship Correction Grace for " + citizenshipId,
                    failure);
        }
    }

    public synchronized StoredCitizenshipCorrectionGrace citizenshipCorrectionGraceStart(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_correction_grace
                WHERE start_service_identity = ? AND start_request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readCitizenshipCorrectionGrace(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Citizenship Correction Grace start request", failure);
        }
    }

    public synchronized StoredCitizenshipCorrectionGrace citizenshipCorrectionGraceResolution(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_correction_grace
                WHERE resolution_service_identity = ? AND resolution_request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readCitizenshipCorrectionGrace(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Citizenship Correction Grace resolution request", failure);
        }
    }

    public synchronized StoredCitizenshipCorrectionGrace resolveCitizenshipCorrectionGrace(
            UUID graceId,
            String serviceIdentity,
            String requestId,
            String resolution,
            String reason,
            long resolvedAtEpochMillis) {
        StoredCitizenshipCorrectionGrace replay =
                citizenshipCorrectionGraceResolution(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE citizenship_correction_grace
                SET resolution = ?, resolution_service_identity = ?,
                    resolution_request_id = ?, resolution_reason = ?,
                    resolved_at_epoch_millis = ?
                WHERE grace_id = ? AND resolution IS NULL
                """)) {
            update.setString(1, resolution);
            update.setString(2, serviceIdentity);
            update.setString(3, requestId);
            update.setString(4, reason);
            update.setLong(5, resolvedAtEpochMillis);
            update.setString(6, graceId.toString());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Citizenship Correction Grace is not active " + graceId);
            }
            return citizenshipCorrectionGrace(graceId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to resolve Citizenship Correction Grace " + graceId, failure);
        }
    }

    public synchronized StoredCitizenshipCorrectionGrace activeCitizenshipCorrectionGraceByPlayer(
            UUID playerId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_correction_grace
                WHERE player_id = ? AND resolution IS NULL
                """)) {
            query.setString(1, playerId.toString());
            return readCitizenshipCorrectionGrace(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read active Citizenship Correction Grace for player " + playerId,
                    failure);
        }
    }

    public synchronized StoredCitizenshipCorrectionGrace activeCitizenshipCorrectionGraceByCitizenship(
            UUID citizenshipId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_correction_grace
                WHERE citizenship_id = ? AND resolution IS NULL
                """)) {
            query.setString(1, citizenshipId.toString());
            return readCitizenshipCorrectionGrace(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read active Citizenship Correction Grace for Citizenship "
                            + citizenshipId,
                    failure);
        }
    }

    public synchronized List<StoredCitizenshipCorrectionGrace> activeCitizenshipCorrectionGracesByNation(
            UUID nationId) {
        List<StoredCitizenshipCorrectionGrace> active = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_correction_grace
                WHERE nation_id = ? AND resolution IS NULL
                ORDER BY started_at_epoch_millis, grace_id
                """)) {
            query.setString(1, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    active.add(readCitizenshipCorrectionGrace(result));
                }
            }
            return List.copyOf(active);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read active Citizenship Correction Graces for Nation " + nationId,
                    failure);
        }
    }

    public synchronized List<StoredCitizenshipCorrectionGrace> citizenshipCorrectionGraceHistory(
            UUID playerId) {
        List<StoredCitizenshipCorrectionGrace> history = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_correction_grace
                WHERE player_id = ?
                ORDER BY started_at_epoch_millis, grace_id
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    history.add(readCitizenshipCorrectionGrace(result));
                }
            }
            return List.copyOf(history);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Citizenship Correction Grace history for player " + playerId,
                    failure);
        }
    }

    public synchronized StoredCitizenshipCorrectionGrace citizenshipCorrectionGrace(UUID graceId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_correction_grace WHERE grace_id = ?
                """)) {
            query.setString(1, graceId.toString());
            return readCitizenshipCorrectionGrace(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Citizenship Correction Grace " + graceId, failure);
        }
    }

    public synchronized StoredCitizenship leaveCitizenship(
            UUID citizenshipId,
            String serviceIdentity,
            String requestId,
            long endedAtEpochMillis) {
        StoredCitizenship replay = citizenshipLeave(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE citizenship_period
                SET ended_at_epoch_millis = ?, leave_service_identity = ?, leave_request_id = ?
                WHERE citizenship_id = ? AND ended_at_epoch_millis IS NULL
                """)) {
            update.setLong(1, endedAtEpochMillis);
            update.setString(2, serviceIdentity);
            update.setString(3, requestId);
            update.setString(4, citizenshipId.toString());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Citizenship is not active: " + citizenshipId);
            }
            return citizenship(citizenshipId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to leave Citizenship " + citizenshipId, failure);
        }
    }

    public synchronized StoredCitizenship citizenshipLeave(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_period
                WHERE leave_service_identity = ? AND leave_request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readCitizenship(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Citizenship leave request", failure);
        }
    }

    public synchronized List<StoredCitizenship> citizenshipHistory(UUID playerId) {
        List<StoredCitizenship> history = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_period
                WHERE player_id = ?
                ORDER BY joined_at_epoch_millis, citizenship_id
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    history.add(readCitizenship(result));
                }
            }
            return List.copyOf(history);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Citizenship history for player " + playerId, failure);
        }
    }

    public synchronized List<UUID> citizenshipPlayersByNation(UUID nationId) {
        List<UUID> players = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT DISTINCT player_id
                FROM citizenship_period
                WHERE nation_id = ?
                ORDER BY player_id
                """)) {
            query.setString(1, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    players.add(UUID.fromString(result.getString("player_id")));
                }
            }
            return List.copyOf(players);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Citizenship players for Nation " + nationId, failure);
        }
    }

    public synchronized StoredNationFiscalPermissionGrant nationFiscalPermissionGrant(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_fiscal_permission_grant
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readNationFiscalPermissionGrant(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation Fiscal Permission grant", failure);
        }
    }

    public synchronized StoredTerritoryFreeAllocationPolicy territoryFreeAllocationPolicy(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_free_allocation_policy
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryFreeAllocationPolicy(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Free Allocation policy", failure);
        }
    }

    public synchronized StoredTerritoryMaintenancePolicy territoryMaintenancePolicy(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_policy
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryMaintenancePolicy(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance policy request", failure);
        }
    }

    public synchronized StoredTerritoryMaintenancePolicy territoryMaintenancePolicy(
            UUID policyId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_policy WHERE policy_id = ?
                """)) {
            query.setString(1, policyId.toString());
            return readTerritoryMaintenancePolicy(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance policy ID", failure);
        }
    }

    public synchronized StoredTerritoryExpansionPricingPolicy territoryExpansionPricingPolicy(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_expansion_pricing_policy
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryExpansionPricingPolicy(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Expansion pricing policy", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceCycle territoryMaintenanceCycle(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_cycle
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryMaintenanceCycle(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Maintenance Cycle", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceCycle territoryMaintenanceCycle(UUID cycleId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_cycle WHERE cycle_id = ?
                """)) {
            query.setString(1, cycleId.toString());
            return readTerritoryMaintenanceCycle(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Maintenance Cycle ID", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceCycle latestTerritoryMaintenanceCycle() {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_cycle
                ORDER BY ends_at_epoch_millis DESC, cycle_id DESC
                LIMIT 1
                """)) {
            return readTerritoryMaintenanceCycle(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read latest Territory Maintenance Cycle", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceCycle territoryMaintenanceCycleAt(
            long atEpochMillis) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_cycle
                WHERE starts_at_epoch_millis <= ? AND ends_at_epoch_millis > ?
                LIMIT 1
                """)) {
            query.setLong(1, atEpochMillis);
            query.setLong(2, atEpochMillis);
            return readTerritoryMaintenanceCycle(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read active Territory Maintenance Cycle", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceCycle openTerritoryMaintenanceCycle(
            UUID cycleId,
            String serviceIdentity,
            String requestId,
            long startsAtEpochMillis,
            long endsAtEpochMillis,
            long openedAtEpochMillis) {
        StoredTerritoryMaintenanceCycle replay = territoryMaintenanceCycle(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_maintenance_cycle (
                    cycle_id, service_identity, request_id,
                    starts_at_epoch_millis, ends_at_epoch_millis, opened_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, cycleId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setLong(4, startsAtEpochMillis);
            insert.setLong(5, endsAtEpochMillis);
            insert.setLong(6, openedAtEpochMillis);
            insert.executeUpdate();
            return territoryMaintenanceCycle(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open Territory Maintenance Cycle", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceAssessmentBatch
            territoryMaintenanceAssessmentBatch(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_assessment_batch
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryMaintenanceAssessmentBatch(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance Assessment Batch", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceAssessmentBatch
            territoryMaintenanceAssessmentBatch(UUID cycleId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_assessment_batch
                WHERE cycle_id = ?
                """)) {
            query.setString(1, cycleId.toString());
            return readTerritoryMaintenanceAssessmentBatch(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance Assessment Batch by Cycle", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceAssessmentBatch
            registerTerritoryMaintenanceAssessmentBatch(
                    UUID cycleId,
                    String serviceIdentity,
                    String requestId,
                    int claimCount,
                    String snapshotSha256,
                    long recordedAtEpochMillis) {
        if (claimCount != 0) {
            throw new IllegalArgumentException(
                    "Non-empty Territory Maintenance batches require persisted Claim Snapshots");
        }
        return registerTerritoryMaintenanceAssessmentBatch(
                cycleId,
                serviceIdentity,
                requestId,
                List.of(),
                snapshotSha256,
                recordedAtEpochMillis);
    }

    public synchronized StoredTerritoryMaintenanceAssessmentBatch
            registerTerritoryMaintenanceAssessmentBatch(
                    UUID cycleId,
                    String serviceIdentity,
                    String requestId,
                    List<StoredTerritoryMaintenanceAssessmentClaim> claims,
                    String snapshotSha256,
                    long recordedAtEpochMillis) {
        StoredTerritoryMaintenanceAssessmentBatch replay =
                territoryMaintenanceAssessmentBatch(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_maintenance_assessment_batch (
                    cycle_id, service_identity, request_id, claim_count,
                    snapshot_sha256, recorded_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """);
                    PreparedStatement claimInsert = connection.prepareStatement("""
                INSERT INTO territory_maintenance_assessment_claim (
                    cycle_id, ordinal, nation_id, ftb_team_id, dimension_id,
                    chunk_x, chunk_z, maintenance_due_minor_units,
                    restoration_fee_minor_units, restoration_eligibility,
                    restoration_cooldown_ends_at_epoch_millis, priority
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                insert.setString(1, cycleId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setInt(4, claims.size());
                insert.setString(5, snapshotSha256);
                insert.setLong(6, recordedAtEpochMillis);
                insert.executeUpdate();
                for (int index = 0; index < claims.size(); index++) {
                    StoredTerritoryMaintenanceAssessmentClaim claim = claims.get(index);
                    if (!claim.cycleId().equals(cycleId) || claim.ordinal() != index) {
                        throw new IllegalArgumentException(
                                "Territory Maintenance Claim Snapshot order is invalid");
                    }
                    claimInsert.setString(1, cycleId.toString());
                    claimInsert.setInt(2, index);
                    claimInsert.setString(3, claim.nationId().toString());
                    claimInsert.setString(4, claim.ftbTeamId().toString());
                    claimInsert.setString(5, claim.dimensionId());
                    claimInsert.setInt(6, claim.chunkX());
                    claimInsert.setInt(7, claim.chunkZ());
                    claimInsert.setLong(8, claim.maintenanceDueMinorUnits());
                    claimInsert.setLong(9, claim.restorationFeeMinorUnits());
                    claimInsert.setString(10, claim.restorationEligibility());
                    if (claim.restorationCooldownEndsAtEpochMillis() == null) {
                        claimInsert.setNull(11, java.sql.Types.INTEGER);
                    } else {
                        claimInsert.setLong(11, claim.restorationCooldownEndsAtEpochMillis());
                    }
                    claimInsert.setString(12, claim.priority());
                    claimInsert.addBatch();
                }
                claimInsert.executeBatch();
            }
            connection.commit();
            return territoryMaintenanceAssessmentBatch(serviceIdentity, requestId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(
                            "Unable to register Territory Maintenance Assessment Batch", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Territory Maintenance Assessment Batch registration", primaryFailure);
        }
    }

    public synchronized List<StoredTerritoryMaintenanceAssessmentClaim>
            territoryMaintenanceAssessmentClaims(UUID cycleId) {
        List<StoredTerritoryMaintenanceAssessmentClaim> claims = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_assessment_claim
                WHERE cycle_id = ? ORDER BY ordinal
                """)) {
            query.setString(1, cycleId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    claims.add(new StoredTerritoryMaintenanceAssessmentClaim(
                            UUID.fromString(result.getString("cycle_id")),
                            result.getInt("ordinal"),
                            UUID.fromString(result.getString("nation_id")),
                            UUID.fromString(result.getString("ftb_team_id")),
                            result.getString("dimension_id"),
                            result.getInt("chunk_x"),
                            result.getInt("chunk_z"),
                            result.getLong("maintenance_due_minor_units"),
                            result.getLong("restoration_fee_minor_units"),
                            result.getString("restoration_eligibility"),
                            result.getObject("restoration_cooldown_ends_at_epoch_millis") == null
                                    ? null
                                    : result.getLong("restoration_cooldown_ends_at_epoch_millis"),
                            result.getString("priority")));
                }
            }
            return List.copyOf(claims);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance Claim Snapshots", failure);
        }
    }

    public synchronized StoredTerritoryFiscalAssessment territoryFiscalAssessment(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_fiscal_assessment
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryFiscalAssessment(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Fiscal Assessment", failure);
        }
    }

    public synchronized StoredTerritoryFiscalAssessment territoryFiscalAssessment(
            UUID cycleId,
            UUID nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_fiscal_assessment
                WHERE cycle_id = ? AND nation_id = ? AND ftb_team_id = ?
                  AND dimension_id = ? AND chunk_x = ? AND chunk_z = ?
                """)) {
            query.setString(1, cycleId.toString());
            query.setString(2, nationId.toString());
            query.setString(3, ftbTeamId.toString());
            query.setString(4, dimensionId);
            query.setInt(5, chunkX);
            query.setInt(6, chunkZ);
            return readTerritoryFiscalAssessment(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Fiscal Assessment target", failure);
        }
    }

    public synchronized StoredTerritoryFiscalAssessment assessTerritoryFiscalValidity(
            UUID assessmentId,
            String serviceIdentity,
            String requestId,
            UUID cycleId,
            UUID nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            long maintenanceDueMinorUnits,
            String priority,
            String reason,
            long assessedAtEpochMillis) {
        return assessTerritoryFiscalValidity(
                assessmentId,
                serviceIdentity,
                requestId,
                cycleId,
                nationId,
                ftbTeamId,
                dimensionId,
                chunkX,
                chunkZ,
                maintenanceDueMinorUnits,
                0L,
                "NOT_REQUIRED",
                null,
                priority,
                reason,
                assessedAtEpochMillis);
    }

    public synchronized StoredTerritoryFiscalAssessment assessTerritoryFiscalValidity(
            UUID assessmentId,
            String serviceIdentity,
            String requestId,
            UUID cycleId,
            UUID nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            long maintenanceDueMinorUnits,
            long restorationFeeMinorUnits,
            String restorationEligibility,
            Long restorationCooldownEndsAtEpochMillis,
            String priority,
            String reason,
            long assessedAtEpochMillis) {
        StoredTerritoryFiscalAssessment replay = territoryFiscalAssessment(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            UUID restorationCreditId = null;
            long restorationCreditApplied = 0L;
            try (PreparedStatement credit = connection.prepareStatement("""
                    SELECT restoration.restoration_id,
                           restoration.remaining_next_cycle_credit_minor_units
                    FROM territory_maintenance_restoration restoration
                    JOIN territory_maintenance_cycle cycle ON cycle.cycle_id = ?
                    WHERE restoration.service_identity = ?
                      AND restoration.nation_id = ?
                      AND restoration.ftb_team_id = ?
                      AND restoration.dimension_id = ?
                      AND restoration.chunk_x = ?
                      AND restoration.chunk_z = ?
                      AND restoration.state = 'CIVIC_COMMITTED'
                      AND restoration.next_full_cycle_starts_at_epoch_millis
                            <= cycle.starts_at_epoch_millis
                      AND restoration.remaining_next_cycle_credit_minor_units > 0
                    ORDER BY restoration.next_full_cycle_starts_at_epoch_millis,
                             restoration.committed_at_epoch_millis,
                             restoration.restoration_id
                    LIMIT 1
                    """)) {
                credit.setString(1, cycleId.toString());
                credit.setString(2, serviceIdentity);
                credit.setString(3, nationId.toString());
                credit.setString(4, ftbTeamId.toString());
                credit.setString(5, dimensionId);
                credit.setInt(6, chunkX);
                credit.setInt(7, chunkZ);
                try (ResultSet result = credit.executeQuery()) {
                    if (result.next()) {
                        restorationCreditId = UUID.fromString(
                                result.getString("restoration_id"));
                        restorationCreditApplied = Math.min(
                                maintenanceDueMinorUnits,
                                result.getLong("remaining_next_cycle_credit_minor_units"));
                    }
                }
            }
            long netMaintenanceDue = Math.subtractExact(
                    maintenanceDueMinorUnits, restorationCreditApplied);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_fiscal_assessment (
                    assessment_id, service_identity, request_id, cycle_id,
                    nation_id, ftb_team_id, dimension_id, chunk_x, chunk_z,
                    maintenance_due_minor_units, restoration_fee_minor_units,
                    restoration_eligibility, restoration_cooldown_ends_at_epoch_millis,
                    priority, validity, reason, assessed_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, assessmentId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, cycleId.toString());
            insert.setString(5, nationId.toString());
            insert.setString(6, ftbTeamId.toString());
            insert.setString(7, dimensionId);
            insert.setInt(8, chunkX);
            insert.setInt(9, chunkZ);
            insert.setLong(10, netMaintenanceDue);
            insert.setLong(11, restorationFeeMinorUnits);
            insert.setString(12, restorationEligibility);
            if (restorationCooldownEndsAtEpochMillis == null) {
                insert.setNull(13, java.sql.Types.INTEGER);
            } else {
                insert.setLong(13, restorationCooldownEndsAtEpochMillis);
            }
            insert.setString(14, priority);
            insert.setString(15, "PENDING");
            insert.setString(16, reason);
            insert.setLong(17, assessedAtEpochMillis);
            insert.executeUpdate();
            if (restorationCreditApplied > 0L) {
                try (PreparedStatement consume = connection.prepareStatement("""
                            UPDATE territory_maintenance_restoration
                            SET remaining_next_cycle_credit_minor_units =
                                    remaining_next_cycle_credit_minor_units - ?
                            WHERE restoration_id = ?
                              AND state = 'CIVIC_COMMITTED'
                              AND remaining_next_cycle_credit_minor_units >= ?
                            """);
                        PreparedStatement audit = connection.prepareStatement("""
                            INSERT INTO territory_maintenance_restoration_credit_application (
                                restoration_id, assessment_id,
                                gross_maintenance_due_minor_units,
                                applied_minor_units, applied_at_epoch_millis
                            ) VALUES (?, ?, ?, ?, ?)
                            """)) {
                    consume.setLong(1, restorationCreditApplied);
                    consume.setString(2, restorationCreditId.toString());
                    consume.setLong(3, restorationCreditApplied);
                    if (consume.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "Territory Restoration Prepayment Credit changed concurrently");
                    }
                    audit.setString(1, restorationCreditId.toString());
                    audit.setString(2, assessmentId.toString());
                    audit.setLong(3, maintenanceDueMinorUnits);
                    audit.setLong(4, restorationCreditApplied);
                    audit.setLong(5, assessedAtEpochMillis);
                    audit.executeUpdate();
                }
            }
            }
            connection.commit();
            return territoryFiscalAssessment(serviceIdentity, requestId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "Unable to persist Territory Fiscal Assessment", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Territory Fiscal Assessment", primaryFailure);
        }
    }

    public synchronized long territoryFiscalAssessmentGrossMaintenanceDueMinorUnits(
            UUID assessmentId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT assessment.maintenance_due_minor_units
                       + COALESCE(application.applied_minor_units, 0)
                FROM territory_fiscal_assessment assessment
                LEFT JOIN territory_maintenance_restoration_credit_application application
                  ON application.assessment_id = assessment.assessment_id
                WHERE assessment.assessment_id = ?
                """)) {
            query.setString(1, assessmentId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException(
                            "Unknown Territory Fiscal Assessment " + assessmentId);
                }
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read gross Territory maintenance due", failure);
        }
    }

    public synchronized long territoryMaintenanceDueMinorUnits(UUID cycleId, UUID nationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(
                    maintenance_due_minor_units + restoration_fee_minor_units
                ), 0)
                FROM territory_fiscal_assessment
                WHERE cycle_id = ? AND nation_id = ?
                """)) {
            query.setString(1, cycleId.toString());
            query.setString(2, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to total Territory maintenance due", failure);
        }
    }

    public synchronized List<StoredTerritoryFiscalAssessment> pendingTerritoryFiscalAssessments(
            UUID cycleId, UUID nationId) {
        List<StoredTerritoryFiscalAssessment> assessments = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_fiscal_assessment
                WHERE cycle_id = ? AND nation_id = ? AND validity = 'PENDING'
                ORDER BY priority, dimension_id, chunk_x, chunk_z, assessment_id
                """)) {
            query.setString(1, cycleId.toString());
            query.setString(2, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    assessments.add(storedTerritoryFiscalAssessment(result));
                }
            }
            return List.copyOf(assessments);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to list pending Territory Fiscal Assessments", failure);
        }
    }

    public synchronized List<StoredTerritoryMaintenanceRestorationHistory>
            territoryMaintenanceRestorationHistory(
                    UUID nationId, UUID ftbTeamId, long beforeEpochMillis) {
        List<StoredTerritoryMaintenanceRestorationHistory> history = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                WITH ranked AS (
                    SELECT assessment.*,
                           cycle.ends_at_epoch_millis,
                           ROW_NUMBER() OVER (
                               PARTITION BY assessment.dimension_id,
                                            assessment.chunk_x,
                                            assessment.chunk_z
                               ORDER BY cycle.ends_at_epoch_millis DESC,
                                        assessment.assessed_at_epoch_millis DESC,
                                        assessment.assessment_id DESC
                           ) AS conclusion_rank
                    FROM territory_fiscal_assessment assessment
                    JOIN territory_maintenance_cycle cycle
                      ON cycle.cycle_id = assessment.cycle_id
                    WHERE assessment.nation_id = ?
                      AND assessment.ftb_team_id = ?
                      AND cycle.ends_at_epoch_millis <= ?
                      AND assessment.validity IN ('EFFECTIVE', 'SUSPENDED')
                )
                SELECT latest.nation_id, latest.ftb_team_id,
                       latest.dimension_id, latest.chunk_x, latest.chunk_z,
                       CASE WHEN EXISTS (
                           SELECT 1
                           FROM territory_maintenance_restoration restoration
                           WHERE restoration.source_suspended_assessment_id =
                                   latest.assessment_id
                             AND restoration.state = 'CIVIC_COMMITTED'
                             AND restoration.committed_at_epoch_millis <= ?
                       ) THEN 'EFFECTIVE' ELSE latest.validity END AS validity,
                       (
                           SELECT MAX(cooldown_ends_at_epoch_millis)
                           FROM (
                               SELECT restored.restoration_cooldown_ends_at_epoch_millis
                                      AS cooldown_ends_at_epoch_millis
                               FROM territory_fiscal_assessment restored
                               JOIN territory_maintenance_cycle restored_cycle
                                 ON restored_cycle.cycle_id = restored.cycle_id
                               WHERE restored.nation_id = latest.nation_id
                                 AND restored.ftb_team_id = latest.ftb_team_id
                                 AND restored.dimension_id = latest.dimension_id
                                 AND restored.chunk_x = latest.chunk_x
                                 AND restored.chunk_z = latest.chunk_z
                                 AND restored_cycle.ends_at_epoch_millis <= ?
                                 AND restored.validity = 'EFFECTIVE'
                                 AND restored.restoration_eligibility = 'ELIGIBLE'
                               UNION ALL
                               SELECT restoration.cooldown_ends_at_epoch_millis
                               FROM territory_maintenance_restoration restoration
                               WHERE restoration.nation_id = latest.nation_id
                                 AND restoration.ftb_team_id = latest.ftb_team_id
                                 AND restoration.dimension_id = latest.dimension_id
                                 AND restoration.chunk_x = latest.chunk_x
                                 AND restoration.chunk_z = latest.chunk_z
                                 AND restoration.state = 'CIVIC_COMMITTED'
                                 AND restoration.committed_at_epoch_millis <= ?
                           )
                       ) AS last_restoration_cooldown
                FROM ranked latest
                WHERE latest.conclusion_rank = 1
                ORDER BY latest.dimension_id, latest.chunk_x, latest.chunk_z
                """)) {
            query.setString(1, nationId.toString());
            query.setString(2, ftbTeamId.toString());
            query.setLong(3, beforeEpochMillis);
            query.setLong(4, beforeEpochMillis);
            query.setLong(5, beforeEpochMillis);
            query.setLong(6, beforeEpochMillis);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    history.add(new StoredTerritoryMaintenanceRestorationHistory(
                            UUID.fromString(result.getString("nation_id")),
                            UUID.fromString(result.getString("ftb_team_id")),
                            result.getString("dimension_id"),
                            result.getInt("chunk_x"),
                            result.getInt("chunk_z"),
                            result.getString("validity"),
                            result.getObject("last_restoration_cooldown") == null
                                    ? null
                                    : result.getLong("last_restoration_cooldown")));
                }
            }
            return List.copyOf(history);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance Restoration history", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceSettlement territoryMaintenanceSettlement(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_settlement
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryMaintenanceSettlement(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Maintenance Settlement", failure);
        }
    }

    public synchronized long territoryMaintenanceSettlementEvidenceAmount(
            String serviceIdentity,
            UUID nationId,
            UUID reservationId,
            UUID publicFundPaymentId,
            UUID destructionOperationId) {
        String treasury = "nation:" + nationId + ":treasury";
        StoredReservation reservation = reservationForSettlement(reservationId);
        StoredPaymentTransaction payment = paymentTransaction(publicFundPaymentId);
        StoredPermanentDestructionOperation destruction = destructionOperationId == null
                ? null
                : permanentDestructionOperation(destructionOperationId);
        if (reservation == null
                || !reservation.serviceIdentity().equals(serviceIdentity)
                || !reservation.sourceAccount().equals(treasury)
                || payment == null
                || !payment.serviceIdentity().equals(serviceIdentity)
                || !payment.reservationId().equals(reservationId)
                || !payment.sourceAccount().equals(treasury)
                || !payment.recipientAccount().equals("system:territory:public-maintenance-fund")
                || !payment.kind().equals("PAYMENT")
                || !payment.state().equals("CIVIC_COMMITTED")
                || payment.refundedMinorUnits() != 0L
                || reservation.settledMinorUnits() != payment.amountMinorUnits()
                || (destruction != null
                        && (!destruction.serviceIdentity().equals(serviceIdentity)
                                || !destruction.sourceAccount().equals(treasury)
                                || !destruction.state().equals("COMMITTED")))) {
            throw new IllegalStateException(
                    "Territory maintenance settlement evidence is not exact and committed");
        }
        long destructionAmount = destruction == null ? 0L : destruction.amountMinorUnits();
        long evidenceAmount = Math.addExact(payment.amountMinorUnits(), destructionAmount);
        if (reservation.amountMinorUnits() != evidenceAmount) {
            throw new IllegalStateException(
                    "Territory maintenance settlement evidence does not close its Reservation");
        }
        String expectedReservationState = destruction == null ? "SETTLED" : "RELEASED";
        if (!reservation.state().equals(expectedReservationState)) {
            throw new IllegalStateException(
                    "Territory maintenance Reservation state does not match settlement effects");
        }
        return evidenceAmount;
    }

    public synchronized StoredTerritoryMaintenanceSettlement confirmTerritoryMaintenanceSettlement(
            UUID settlementId,
            String serviceIdentity,
            String requestId,
            UUID cycleId,
            UUID nationId,
            UUID reservationId,
            UUID publicFundPaymentId,
            UUID destructionOperationId,
            List<UUID> fundedAssessmentIds,
            List<UUID> suspendedAssessmentIds,
            String reason,
            long settledAtEpochMillis) {
        StoredTerritoryMaintenanceSettlement replay =
                territoryMaintenanceSettlement(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            long evidenceAmount = territoryMaintenanceSettlementEvidenceAmount(
                    serviceIdentity,
                    nationId,
                    reservationId,
                    publicFundPaymentId,
                    destructionOperationId);
            List<StoredTerritoryFiscalAssessment> pending =
                    pendingTerritoryFiscalAssessments(cycleId, nationId);
            if (pending.isEmpty()
                    || !territoryMaintenanceOwnedBy(
                            cycleId, nationId, serviceIdentity, pending.size())) {
                throw new IllegalStateException("Territory maintenance has no pending assessment due");
            }
            List<TerritoryMaintenanceCandidate> candidates = pending.stream()
                    .map(assessment -> new TerritoryMaintenanceCandidate(
                            assessment.assessmentId(),
                            TerritoryMaintenancePriority.valueOf(assessment.priority()),
                            assessment.dimensionId(),
                            assessment.chunkX(),
                            assessment.chunkZ(),
                            MoneyAmount.ofMinorUnits(Math.addExact(
                                    assessment.maintenanceDueMinorUnits(),
                                    assessment.restorationFeeMinorUnits())),
                            !assessment.restorationEligibility().equals("COOLDOWN_BLOCKED")))
                    .toList();
            TerritoryMaintenancePriorityDecision expected =
                    new TerritoryMaintenancePriorityPolicy().select(
                            candidates, MoneyAmount.ofMinorUnits(evidenceAmount));
            List<UUID> expectedFunded = expected.funded().stream()
                    .map(TerritoryMaintenanceCandidate::assessmentId)
                    .toList();
            List<UUID> expectedSuspended = expected.suspended().stream()
                    .map(TerritoryMaintenanceCandidate::assessmentId)
                    .toList();
            requireExactTerritorySettlementPartition(
                    fundedAssessmentIds,
                    suspendedAssessmentIds,
                    expectedFunded,
                    expectedSuspended);
            long fundedDue = expected.funded().stream()
                    .mapToLong(candidate -> candidate.maintenanceDue().minorUnits())
                    .reduce(0L, Math::addExact);
            if (fundedDue != evidenceAmount) {
                throw new IllegalStateException(
                        "Territory maintenance evidence must exactly fund the selected assessments");
            }
            String outcome = expectedSuspended.isEmpty() ? "FULLY_FUNDED" : "PARTIALLY_FUNDED";
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO territory_maintenance_settlement (
                            settlement_id, service_identity, request_id, cycle_id, nation_id,
                            reservation_id, public_fund_payment_id, destruction_operation_id,
                            outcome, reason, settled_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                    PreparedStatement detail = connection.prepareStatement("""
                        INSERT INTO territory_maintenance_settlement_assessment (
                            settlement_id, assessment_id, validity
                        ) VALUES (?, ?, ?)
                        """);
                    PreparedStatement update = connection.prepareStatement("""
                        UPDATE territory_fiscal_assessment
                        SET validity = ?
                        WHERE assessment_id = ? AND validity = 'PENDING'
                        """)) {
                insert.setString(1, settlementId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, cycleId.toString());
                insert.setString(5, nationId.toString());
                insert.setString(6, reservationId.toString());
                insert.setString(7, publicFundPaymentId.toString());
                if (destructionOperationId == null) {
                    insert.setNull(8, java.sql.Types.VARCHAR);
                } else {
                    insert.setString(8, destructionOperationId.toString());
                }
                insert.setString(9, outcome);
                insert.setString(10, reason);
                insert.setLong(11, settledAtEpochMillis);
                insert.executeUpdate();
                for (TerritoryMaintenanceCandidate candidate : expected.funded()) {
                    persistTerritorySettlementAssessment(
                            detail, update, settlementId, candidate.assessmentId(), "EFFECTIVE");
                }
                for (TerritoryMaintenanceCandidate candidate : expected.suspended()) {
                    persistTerritorySettlementAssessment(
                            detail, update, settlementId, candidate.assessmentId(), "SUSPENDED");
                }
            }
            connection.commit();
            return territoryMaintenanceSettlement(serviceIdentity, requestId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "Unable to confirm Territory Maintenance Settlement", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Territory Maintenance Settlement confirmation", primaryFailure);
        }
    }

    public synchronized StoredTerritoryMaintenanceSettlement suspendTerritoryMaintenance(
            UUID settlementId,
            String serviceIdentity,
            String requestId,
            UUID cycleId,
            UUID nationId,
            String reason,
            long settledAtEpochMillis) {
        StoredTerritoryMaintenanceSettlement replay =
                territoryMaintenanceSettlement(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            int pendingCount = pendingTerritoryAssessmentCount(cycleId, nationId);
            if (pendingCount == 0
                    || !territoryMaintenanceOwnedBy(
                            cycleId, nationId, serviceIdentity, pendingCount)) {
                throw new IllegalStateException(
                        "Territory maintenance has no pending assessments to suspend");
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO territory_maintenance_settlement (
                            settlement_id, service_identity, request_id, cycle_id, nation_id,
                            outcome, reason, settled_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, 'UNFUNDED', ?, ?)
                        """);
                    PreparedStatement detail = connection.prepareStatement("""
                        INSERT INTO territory_maintenance_settlement_assessment (
                            settlement_id, assessment_id, validity
                        )
                        SELECT ?, assessment_id, 'SUSPENDED'
                        FROM territory_fiscal_assessment
                        WHERE cycle_id = ? AND nation_id = ? AND validity = 'PENDING'
                        """);
                    PreparedStatement update = connection.prepareStatement("""
                        UPDATE territory_fiscal_assessment
                        SET validity = 'SUSPENDED'
                        WHERE cycle_id = ? AND nation_id = ? AND validity = 'PENDING'
                        """)) {
                insert.setString(1, settlementId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, cycleId.toString());
                insert.setString(5, nationId.toString());
                insert.setString(6, reason);
                insert.setLong(7, settledAtEpochMillis);
                insert.executeUpdate();
                detail.setString(1, settlementId.toString());
                detail.setString(2, cycleId.toString());
                detail.setString(3, nationId.toString());
                if (detail.executeUpdate() != pendingCount) {
                    throw new IllegalStateException(
                            "Territory maintenance assessments changed before suspension");
                }
                update.setString(1, cycleId.toString());
                update.setString(2, nationId.toString());
                if (update.executeUpdate() != pendingCount) {
                    throw new IllegalStateException(
                            "Territory maintenance assessments changed before suspension");
                }
            }
            connection.commit();
            return territoryMaintenanceSettlement(serviceIdentity, requestId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "Unable to suspend Territory Maintenance", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Territory Maintenance suspension", primaryFailure);
        }
    }

    public synchronized StoredTerritoryMaintenanceSettlement settleZeroCostTerritoryMaintenance(
            UUID settlementId,
            String serviceIdentity,
            String requestId,
            UUID cycleId,
            UUID nationId,
            String reason,
            long settledAtEpochMillis) {
        StoredTerritoryMaintenanceSettlement replay =
                territoryMaintenanceSettlement(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            List<StoredTerritoryFiscalAssessment> pending =
                    pendingTerritoryFiscalAssessments(cycleId, nationId);
            if (pending.isEmpty()
                    || !territoryMaintenanceOwnedBy(
                            cycleId, nationId, serviceIdentity, pending.size())) {
                throw new IllegalStateException(
                        "Territory maintenance has no pending assessment due");
            }
            List<TerritoryMaintenanceCandidate> candidates = pending.stream()
                    .map(assessment -> new TerritoryMaintenanceCandidate(
                            assessment.assessmentId(),
                            TerritoryMaintenancePriority.valueOf(assessment.priority()),
                            assessment.dimensionId(),
                            assessment.chunkX(),
                            assessment.chunkZ(),
                            MoneyAmount.ofMinorUnits(Math.addExact(
                                    assessment.maintenanceDueMinorUnits(),
                                    assessment.restorationFeeMinorUnits())),
                            !assessment.restorationEligibility().equals("COOLDOWN_BLOCKED")))
                    .toList();
            TerritoryMaintenancePriorityDecision decision =
                    new TerritoryMaintenancePriorityPolicy().select(candidates, MoneyAmount.ZERO);
            if (decision.funded().isEmpty()
                    || !decision.fundedAmount().equals(MoneyAmount.ZERO)) {
                throw new IllegalStateException(
                        "Payment-free Territory Maintenance Settlement requires zero-cost Assessments");
            }
            String outcome = decision.suspended().isEmpty()
                    ? "FULLY_FUNDED"
                    : "PARTIALLY_FUNDED";
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO territory_maintenance_settlement (
                            settlement_id, service_identity, request_id, cycle_id, nation_id,
                            outcome, reason, settled_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                    PreparedStatement detail = connection.prepareStatement("""
                        INSERT INTO territory_maintenance_settlement_assessment (
                            settlement_id, assessment_id, validity
                        ) VALUES (?, ?, ?)
                        """);
                    PreparedStatement update = connection.prepareStatement("""
                        UPDATE territory_fiscal_assessment
                        SET validity = ?
                        WHERE assessment_id = ? AND validity = 'PENDING'
                        """)) {
                insert.setString(1, settlementId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, cycleId.toString());
                insert.setString(5, nationId.toString());
                insert.setString(6, outcome);
                insert.setString(7, reason);
                insert.setLong(8, settledAtEpochMillis);
                insert.executeUpdate();
                for (TerritoryMaintenanceCandidate candidate : decision.funded()) {
                    persistTerritorySettlementAssessment(
                            detail, update, settlementId, candidate.assessmentId(), "EFFECTIVE");
                }
                for (TerritoryMaintenanceCandidate candidate : decision.suspended()) {
                    persistTerritorySettlementAssessment(
                            detail, update, settlementId, candidate.assessmentId(), "SUSPENDED");
                }
            }
            connection.commit();
            return territoryMaintenanceSettlement(serviceIdentity, requestId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "Unable to settle zero-cost Territory Maintenance", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Zero-cost Territory Maintenance settlement", primaryFailure);
        }
    }

    public synchronized StoredTerritoryMaintenanceRestoration
            prepareTerritoryMaintenanceRestoration(
                    UUID restorationId,
                    String serviceIdentity,
                    String requestId,
                    UUID nationId,
                    UUID ftbTeamId,
                    UUID actorPlayerId,
                    String dimensionId,
                    int chunkX,
                    int chunkZ,
                    UUID policyId,
                    long nextFullCycleStartsAtEpochMillis,
                    long nextCyclePrepaymentMinorUnits,
                    long restorationFeeMinorUnits,
                    long totalDueMinorUnits,
                    long cooldownEndsAtEpochMillis,
                    int destructionBasisPoints,
                    String reason,
                    long preparedAtEpochMillis) {
        StoredTerritoryMaintenanceRestoration replay =
                territoryMaintenanceRestoration(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_maintenance_restoration (
                    restoration_id, service_identity, request_id, nation_id,
                    ftb_team_id, actor_player_id, dimension_id, chunk_x, chunk_z,
                    source_suspended_assessment_id, policy_id,
                    next_full_cycle_starts_at_epoch_millis,
                    next_cycle_prepayment_minor_units, restoration_fee_minor_units,
                    total_due_minor_units, remaining_next_cycle_credit_minor_units,
                    cooldown_ends_at_epoch_millis, destruction_basis_points,
                    state, reason, prepared_at_epoch_millis
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, assessment.assessment_id, ?,
                       ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?
                FROM territory_fiscal_assessment assessment
                JOIN territory_maintenance_cycle cycle
                  ON cycle.cycle_id = assessment.cycle_id
                WHERE assessment.service_identity = ?
                  AND assessment.nation_id = ?
                  AND assessment.ftb_team_id = ?
                  AND assessment.dimension_id = ?
                  AND assessment.chunk_x = ?
                  AND assessment.chunk_z = ?
                  AND assessment.validity = 'SUSPENDED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM territory_fiscal_assessment newer
                      JOIN territory_maintenance_cycle newer_cycle
                        ON newer_cycle.cycle_id = newer.cycle_id
                      WHERE newer.nation_id = assessment.nation_id
                        AND newer.ftb_team_id = assessment.ftb_team_id
                        AND newer.dimension_id = assessment.dimension_id
                        AND newer.chunk_x = assessment.chunk_x
                        AND newer.chunk_z = assessment.chunk_z
                        AND newer.validity IN ('EFFECTIVE', 'SUSPENDED')
                        AND (newer_cycle.ends_at_epoch_millis > cycle.ends_at_epoch_millis
                            OR (newer_cycle.ends_at_epoch_millis = cycle.ends_at_epoch_millis
                                AND newer.assessed_at_epoch_millis
                                    > assessment.assessed_at_epoch_millis))
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM territory_maintenance_restoration committed
                      WHERE committed.nation_id = assessment.nation_id
                        AND committed.ftb_team_id = assessment.ftb_team_id
                        AND committed.dimension_id = assessment.dimension_id
                        AND committed.chunk_x = assessment.chunk_x
                        AND committed.chunk_z = assessment.chunk_z
                        AND committed.state = 'CIVIC_COMMITTED'
                  )
                """)) {
            insert.setString(1, restorationId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, nationId.toString());
            insert.setString(5, ftbTeamId.toString());
            insert.setString(6, actorPlayerId.toString());
            insert.setString(7, dimensionId);
            insert.setInt(8, chunkX);
            insert.setInt(9, chunkZ);
            insert.setString(10, policyId.toString());
            insert.setLong(11, nextFullCycleStartsAtEpochMillis);
            insert.setLong(12, nextCyclePrepaymentMinorUnits);
            insert.setLong(13, restorationFeeMinorUnits);
            insert.setLong(14, totalDueMinorUnits);
            insert.setLong(15, nextCyclePrepaymentMinorUnits);
            insert.setLong(16, cooldownEndsAtEpochMillis);
            insert.setInt(17, destructionBasisPoints);
            insert.setString(18, reason);
            insert.setLong(19, preparedAtEpochMillis);
            insert.setString(20, serviceIdentity);
            insert.setString(21, nationId.toString());
            insert.setString(22, ftbTeamId.toString());
            insert.setString(23, dimensionId);
            insert.setInt(24, chunkX);
            insert.setInt(25, chunkZ);
            if (insert.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Territory Maintenance Restoration requires the exact latest suspended target");
            }
            return territoryMaintenanceRestoration(restorationId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to prepare Territory Maintenance Restoration", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceRestoration
            territoryMaintenanceRestoration(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_restoration
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryMaintenanceRestoration(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance Restoration replay", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceRestoration
            territoryMaintenanceRestoration(UUID restorationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_restoration WHERE restoration_id = ?
                """)) {
            query.setString(1, restorationId.toString());
            return readTerritoryMaintenanceRestoration(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Maintenance Restoration", failure);
        }
    }

    public synchronized boolean hasCommittedTerritoryMaintenanceRestoration(
            UUID sourceSuspendedAssessmentId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1
                FROM territory_maintenance_restoration
                WHERE source_suspended_assessment_id = ?
                  AND state = 'CIVIC_COMMITTED'
                """)) {
            query.setString(1, sourceSuspendedAssessmentId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read committed Territory Maintenance Restoration", failure);
        }
    }

    public synchronized StoredTerritoryMaintenanceRestoration
            confirmTerritoryMaintenanceRestoration(
                    UUID restorationId,
                    String serviceIdentity,
                    UUID reservationId,
                    UUID publicFundPaymentId,
                    UUID destructionOperationId,
                    long committedAtEpochMillis) {
        StoredTerritoryMaintenanceRestoration existing =
                territoryMaintenanceRestoration(restorationId);
        if (existing == null
                || !existing.serviceIdentity().equals(serviceIdentity)) {
            throw new SecurityException(
                    "Territory Maintenance Restoration requires its exact service identity");
        }
        if (existing.state().equals("CIVIC_COMMITTED")) {
            return existing;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            if (existing.totalDueMinorUnits() == 0L) {
                if (reservationId != null
                        || publicFundPaymentId != null
                        || destructionOperationId != null) {
                    throw new IllegalStateException(
                            "Zero-cost Territory Maintenance Restoration cannot cite fiscal effects");
                }
            } else {
                if (reservationId == null || publicFundPaymentId == null) {
                    throw new IllegalStateException(
                            "Funded Territory Maintenance Restoration requires exact evidence");
                }
                long evidenceAmount = territoryMaintenanceSettlementEvidenceAmount(
                        serviceIdentity,
                        existing.nationId(),
                        reservationId,
                        publicFundPaymentId,
                        destructionOperationId);
                if (evidenceAmount != existing.totalDueMinorUnits()) {
                    throw new IllegalStateException(
                            "Territory Maintenance Restoration evidence must fund its exact total");
                }
                StoredPaymentTransaction payment = paymentTransaction(publicFundPaymentId);
                StoredPermanentDestructionOperation destruction = destructionOperationId == null
                        ? null
                        : permanentDestructionOperation(destructionOperationId);
                long expectedDestroyed = Math.multiplyExact(
                                existing.totalDueMinorUnits(), existing.destructionBasisPoints())
                        / 10_000L;
                long actualDestroyed = destruction == null ? 0L : destruction.amountMinorUnits();
                if (actualDestroyed != expectedDestroyed
                        || payment.amountMinorUnits()
                                != Math.subtractExact(
                                        existing.totalDueMinorUnits(), expectedDestroyed)) {
                    throw new IllegalStateException(
                            "Territory Maintenance Restoration evidence uses the wrong policy split");
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE territory_maintenance_restoration AS restoration
                    SET state = 'CIVIC_COMMITTED',
                        reservation_id = ?,
                        public_fund_payment_id = ?,
                        destruction_operation_id = ?,
                        committed_at_epoch_millis = ?
                    WHERE restoration.restoration_id = ?
                      AND restoration.service_identity = ?
                      AND restoration.state = 'PREPARED'
                      AND EXISTS (
                          SELECT 1
                          FROM territory_fiscal_assessment assessment
                          JOIN territory_maintenance_cycle cycle
                            ON cycle.cycle_id = assessment.cycle_id
                          WHERE assessment.assessment_id =
                                  restoration.source_suspended_assessment_id
                            AND assessment.service_identity = restoration.service_identity
                            AND assessment.nation_id = restoration.nation_id
                            AND assessment.ftb_team_id = restoration.ftb_team_id
                            AND assessment.dimension_id = restoration.dimension_id
                            AND assessment.chunk_x = restoration.chunk_x
                            AND assessment.chunk_z = restoration.chunk_z
                            AND assessment.validity = 'SUSPENDED'
                            AND NOT EXISTS (
                                SELECT 1
                                FROM territory_fiscal_assessment newer
                                JOIN territory_maintenance_cycle newer_cycle
                                  ON newer_cycle.cycle_id = newer.cycle_id
                                WHERE newer.nation_id = assessment.nation_id
                                  AND newer.ftb_team_id = assessment.ftb_team_id
                                  AND newer.dimension_id = assessment.dimension_id
                                  AND newer.chunk_x = assessment.chunk_x
                                  AND newer.chunk_z = assessment.chunk_z
                                  AND newer.validity IN ('EFFECTIVE', 'SUSPENDED')
                                  AND (newer_cycle.ends_at_epoch_millis
                                            > cycle.ends_at_epoch_millis
                                      OR (newer_cycle.ends_at_epoch_millis
                                                = cycle.ends_at_epoch_millis
                                          AND newer.assessed_at_epoch_millis
                                                > assessment.assessed_at_epoch_millis))
                            )
                      )
                    """)) {
                if (reservationId == null) {
                    update.setNull(1, java.sql.Types.VARCHAR);
                    update.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    update.setString(1, reservationId.toString());
                    update.setString(2, publicFundPaymentId.toString());
                }
                if (destructionOperationId == null) {
                    update.setNull(3, java.sql.Types.VARCHAR);
                } else {
                    update.setString(3, destructionOperationId.toString());
                }
                update.setLong(4, committedAtEpochMillis);
                update.setString(5, restorationId.toString());
                update.setString(6, serviceIdentity);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Territory Maintenance Restoration target changed before commit");
                }
            }
            connection.commit();
            return territoryMaintenanceRestoration(restorationId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "Unable to confirm Territory Maintenance Restoration", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Territory Maintenance Restoration confirmation", primaryFailure);
        }
    }

    public synchronized StoredTerritoryForceLoadEnforcement territoryForceLoadEnforcement(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_force_load_enforcement
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryForceLoadEnforcement(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Force-load Enforcement replay", failure);
        }
    }

    public synchronized StoredTerritoryForceLoadEnforcement territoryForceLoadEnforcement(
            UUID enforcementId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_force_load_enforcement WHERE enforcement_id = ?
                """)) {
            query.setString(1, enforcementId.toString());
            return readTerritoryForceLoadEnforcement(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Force-load Enforcement", failure);
        }
    }

    public synchronized List<StoredTerritoryForceLoadEnforcement>
            dueTerritoryForceLoadEnforcements(long nowEpochMillis) {
        List<StoredTerritoryForceLoadEnforcement> enforcements = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM territory_force_load_enforcement
                    WHERE state IN ('PREPARED', 'EXTERNAL_APPLIED')
                      AND not_before_epoch_millis <= ?
                    ORDER BY prepared_at_epoch_millis, enforcement_id
                    """)) {
            query.setLong(1, nowEpochMillis);
            try (ResultSet due = query.executeQuery()) {
                while (due.next()) {
                    enforcements.add(storedTerritoryForceLoadEnforcement(due));
                }
            }
            return List.copyOf(enforcements);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read incomplete Territory Force-load Enforcements", failure);
        }
    }

    public synchronized List<StoredTerritoryForceLoadRestriction>
            activeTerritoryForceLoadRestrictions(long nowEpochMillis) {
        List<StoredTerritoryForceLoadRestriction> restrictions = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                WITH concluded AS (
                    SELECT assessment.assessment_id,
                           assessment.nation_id,
                           assessment.ftb_team_id,
                           assessment.dimension_id,
                           assessment.chunk_x,
                           assessment.chunk_z,
                           assessment.validity,
                           cycle.ends_at_epoch_millis,
                           ROW_NUMBER() OVER (
                               PARTITION BY assessment.nation_id,
                                            assessment.ftb_team_id,
                                            assessment.dimension_id,
                                            assessment.chunk_x,
                                            assessment.chunk_z
                               ORDER BY cycle.ends_at_epoch_millis DESC,
                                        assessment.assessed_at_epoch_millis DESC,
                                        assessment.assessment_id DESC
                           ) AS current_rank
                    FROM territory_fiscal_assessment assessment
                    JOIN territory_maintenance_cycle cycle
                      ON cycle.cycle_id = assessment.cycle_id
                    WHERE assessment.validity IN ('EFFECTIVE', 'SUSPENDED')
                      AND cycle.ends_at_epoch_millis <= ?
                )
                SELECT assessment_id, nation_id, ftb_team_id, dimension_id,
                       chunk_x, chunk_z,
                       ends_at_epoch_millis + ? AS restricted_at_epoch_millis
                FROM concluded
                WHERE current_rank = 1
                  AND validity = 'SUSPENDED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM territory_maintenance_restoration restoration
                      WHERE restoration.source_suspended_assessment_id = assessment_id
                        AND restoration.state = 'CIVIC_COMMITTED'
                  )
                  AND ends_at_epoch_millis <= 9223372036854775807 - ?
                  AND ends_at_epoch_millis + ? <= ?
                ORDER BY ftb_team_id, dimension_id, chunk_x, chunk_z, assessment_id
                """)) {
            query.setLong(1, nowEpochMillis);
            query.setLong(2, TERRITORY_FORCE_LOAD_GRACE_MILLIS);
            query.setLong(3, TERRITORY_FORCE_LOAD_GRACE_MILLIS);
            query.setLong(4, TERRITORY_FORCE_LOAD_GRACE_MILLIS);
            query.setLong(5, nowEpochMillis);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    restrictions.add(new StoredTerritoryForceLoadRestriction(
                            UUID.fromString(result.getString("assessment_id")),
                            UUID.fromString(result.getString("nation_id")),
                            UUID.fromString(result.getString("ftb_team_id")),
                            result.getString("dimension_id"),
                            result.getInt("chunk_x"),
                            result.getInt("chunk_z"),
                            result.getLong("restricted_at_epoch_millis")));
                }
            }
            return List.copyOf(restrictions);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read active Territory Force-load Restrictions", failure);
        }
    }

    public synchronized StoredTerritoryForceLoadEnforcement
            prepareTerritoryForceLoadEnforcement(
                    UUID enforcementId,
                    String serviceIdentity,
                    String requestId,
                    UUID assessmentId,
                    String reason,
                    long preparedAtEpochMillis) {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_force_load_enforcement (
                    enforcement_id, service_identity, request_id, assessment_id,
                    ftb_team_id, dimension_id, chunk_x, chunk_z, state, reason,
                    not_before_epoch_millis,
                    prepared_at_epoch_millis, external_applied_at_epoch_millis,
                    committed_at_epoch_millis
                )
                SELECT ?, assessment.service_identity, ?, assessment.assessment_id,
                       assessment.ftb_team_id, assessment.dimension_id,
                       assessment.chunk_x, assessment.chunk_z, 'PREPARED', ?,
                       cycle.ends_at_epoch_millis + ?, ?, NULL, NULL
                FROM territory_fiscal_assessment assessment
                JOIN territory_maintenance_cycle cycle
                  ON cycle.cycle_id = assessment.cycle_id
                WHERE assessment.assessment_id = ?
                  AND assessment.service_identity = ?
                  AND assessment.validity = 'SUSPENDED'
                  AND cycle.ends_at_epoch_millis <= 9223372036854775807 - ?
                """)) {
            insert.setString(1, enforcementId.toString());
            insert.setString(2, requestId);
            insert.setString(3, reason);
            insert.setLong(4, TERRITORY_FORCE_LOAD_GRACE_MILLIS);
            insert.setLong(5, preparedAtEpochMillis);
            insert.setString(6, assessmentId.toString());
            insert.setString(7, serviceIdentity);
            insert.setLong(8, TERRITORY_FORCE_LOAD_GRACE_MILLIS);
            if (insert.executeUpdate() != 1) {
                throw new SecurityException(
                        "Force-load Enforcement requires the exact suspended Assessment service identity");
            }
            return territoryForceLoadEnforcement(enforcementId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to prepare Territory Force-load Enforcement", failure);
        }
    }

    public synchronized StoredTerritoryForceLoadEnforcement
            markTerritoryForceLoadExternalApplied(
                    UUID enforcementId, long externalAppliedAtEpochMillis) {
        StoredTerritoryForceLoadEnforcement existing = requireTerritoryForceLoadEnforcement(
                enforcementId);
        if (!existing.state().equals("PREPARED")) {
            return existing;
        }
        if (externalAppliedAtEpochMillis < existing.notBeforeEpochMillis()) {
            throw new IllegalStateException(
                    "Territory Force-load Enforcement grace has not elapsed");
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE territory_force_load_enforcement
                SET state = 'EXTERNAL_APPLIED', external_applied_at_epoch_millis = ?
                WHERE enforcement_id = ? AND state = 'PREPARED'
                """)) {
            update.setLong(1, externalAppliedAtEpochMillis);
            update.setString(2, enforcementId.toString());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Territory Force-load Enforcement state changed concurrently");
            }
            return territoryForceLoadEnforcement(enforcementId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to mark Territory Force-load Enforcement externally applied", failure);
        }
    }

    public synchronized StoredTerritoryForceLoadEnforcement commitTerritoryForceLoadEnforcement(
            UUID enforcementId, long committedAtEpochMillis) {
        StoredTerritoryForceLoadEnforcement existing = requireTerritoryForceLoadEnforcement(
                enforcementId);
        if (existing.state().equals("CIVIC_COMMITTED")) {
            return existing;
        }
        if (!existing.state().equals("EXTERNAL_APPLIED")) {
            throw new IllegalStateException(
                    "Territory Force-load Enforcement must be externally applied before commit");
        }
        if (existing.externalAppliedAtEpochMillis() == null
                || committedAtEpochMillis < existing.externalAppliedAtEpochMillis()) {
            throw new IllegalStateException(
                    "Territory Force-load Enforcement commit cannot precede external application");
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE territory_force_load_enforcement
                SET state = 'CIVIC_COMMITTED', committed_at_epoch_millis = ?
                WHERE enforcement_id = ? AND state = 'EXTERNAL_APPLIED'
                """)) {
            update.setLong(1, committedAtEpochMillis);
            update.setString(2, enforcementId.toString());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Territory Force-load Enforcement state changed concurrently");
            }
            return territoryForceLoadEnforcement(enforcementId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to commit Territory Force-load Enforcement", failure);
        }
    }

    private StoredTerritoryForceLoadEnforcement requireTerritoryForceLoadEnforcement(
            UUID enforcementId) {
        StoredTerritoryForceLoadEnforcement existing =
                territoryForceLoadEnforcement(enforcementId);
        if (existing == null) {
            throw new IllegalArgumentException(
                    "Unknown Territory Force-load Enforcement " + enforcementId);
        }
        return existing;
    }

    public synchronized StoredTerritoryClaimPermit territoryClaimPermit(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryClaimPermit(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Claim Permit", failure);
        }
    }

    public synchronized StoredMintRecipeVersion publishMintRecipeVersion(
            UUID recipeVersionId,
            String serviceIdentity,
            String requestId,
            int versionNumber,
            List<StoredMintRecipeIngredient> ingredients,
            long processingDurationMillis,
            String reason,
            long publishedAtEpochMillis) {
        if (recipeVersionId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || versionNumber <= 0
                || ingredients == null
                || ingredients.isEmpty()
                || processingDurationMillis <= 0L
                || reason == null
                || reason.isBlank()
                || publishedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Mint Recipe Version values are invalid");
        }
        validateMintRecipeIngredients(ingredients);
        StoredMintRecipeVersion replay = mintRecipeVersion(serviceIdentity, requestId);
        if (replay != null) {
            if (!replay.recipeVersionId().equals(recipeVersionId)
                    || replay.versionNumber() != versionNumber
                    || replay.processingDurationMillis() != processingDurationMillis
                    || !replay.reason().equals(reason)
                    || !mintRecipeIngredients(recipeVersionId).equals(List.copyOf(ingredients))) {
                throw new IllegalArgumentException(
                        "Mint Recipe Version replay changed its immutable payload");
            }
            return replay;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertVersion = connection.prepareStatement("""
                        INSERT INTO mint_recipe_version (
                            recipe_version_id, service_identity, request_id,
                            version_number, processing_duration_millis,
                            reason, published_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """);
                    PreparedStatement insertIngredient = connection.prepareStatement("""
                        INSERT INTO mint_recipe_ingredient (
                            recipe_version_id, ingredient_index, group_index,
                            matcher_kind, matcher_value, quantity_units,
                            per_face_value_minor_units
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                insertVersion.setString(1, recipeVersionId.toString());
                insertVersion.setString(2, serviceIdentity);
                insertVersion.setString(3, requestId);
                insertVersion.setInt(4, versionNumber);
                insertVersion.setLong(5, processingDurationMillis);
                insertVersion.setString(6, reason);
                insertVersion.setLong(7, publishedAtEpochMillis);
                insertVersion.executeUpdate();
                for (int index = 0; index < ingredients.size(); index++) {
                    StoredMintRecipeIngredient ingredient = ingredients.get(index);
                    insertIngredient.setString(1, recipeVersionId.toString());
                    insertIngredient.setInt(2, index);
                    insertIngredient.setInt(3, ingredient.groupIndex());
                    insertIngredient.setString(4, ingredient.matcherKind());
                    insertIngredient.setString(5, ingredient.matcherValue());
                    insertIngredient.setLong(6, ingredient.quantityUnits());
                    insertIngredient.setLong(7, ingredient.perFaceValueMinorUnits());
                    insertIngredient.addBatch();
                }
                insertIngredient.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return mintRecipeVersion(recipeVersionId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to publish Mint Recipe Version", failure);
        }
    }

    public synchronized StoredMintRecipeVersion mintRecipeVersion(UUID recipeVersionId) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM mint_recipe_version WHERE recipe_version_id = ?")) {
            query.setString(1, recipeVersionId.toString());
            return readMintRecipeVersion(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Mint Recipe Version", failure);
        }
    }

    public synchronized StoredMintRecipeVersion mintRecipeVersion(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM mint_recipe_version
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readMintRecipeVersion(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Mint Recipe Version request", failure);
        }
    }

    public synchronized List<StoredMintRecipeIngredient> mintRecipeIngredients(
            UUID recipeVersionId) {
        List<StoredMintRecipeIngredient> ingredients = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM mint_recipe_ingredient
                WHERE recipe_version_id = ?
                ORDER BY ingredient_index
                """)) {
            query.setString(1, recipeVersionId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    ingredients.add(new StoredMintRecipeIngredient(
                            result.getInt("group_index"),
                            result.getString("matcher_kind"),
                            result.getString("matcher_value"),
                            result.getLong("quantity_units"),
                            result.getLong("per_face_value_minor_units")));
                }
            }
            return List.copyOf(ingredients);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Mint Recipe ingredients", failure);
        }
    }

    public synchronized StoredRegisteredMint registerMint(
            UUID mintId,
            String serviceIdentity,
            String requestId,
            UUID nationId,
            String dimensionId,
            int blockX,
            int blockY,
            int blockZ,
            UUID operatorOrganizationId,
            UUID licenseId,
            boolean automationAllowed,
            UUID recipeVersionId,
            UUID actorPlayerId,
            String reason,
            long registeredAtEpochMillis) {
        if (mintId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || nationId == null
                || dimensionId == null
                || dimensionId.isBlank()
                || operatorOrganizationId == null
                || licenseId == null
                || recipeVersionId == null
                || actorPlayerId == null
                || reason == null
                || reason.isBlank()
                || registeredAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Registered Mint values are invalid");
        }
        StoredRegisteredMint replay = registeredMint(serviceIdentity, requestId);
        if (replay != null) {
            if (!replay.mintId().equals(mintId)
                    || !replay.nationId().equals(nationId)
                    || !replay.dimensionId().equals(dimensionId)
                    || replay.blockX() != blockX
                    || replay.blockY() != blockY
                    || replay.blockZ() != blockZ
                    || !replay.operatorOrganizationId().equals(operatorOrganizationId)
                    || !replay.licenseId().equals(licenseId)
                    || replay.automationAllowed() != automationAllowed
                    || !replay.recipeVersionId().equals(recipeVersionId)
                    || !replay.actorPlayerId().equals(actorPlayerId)
                    || !replay.reason().equals(reason)) {
                throw new IllegalArgumentException(
                        "Registered Mint replay changed its immutable payload");
            }
            return replay;
        }
        if (nation(nationId) == null) {
            throw new IllegalStateException("Registered Mint references an unknown Nation");
        }
        if (mintRecipeVersion(recipeVersionId) == null) {
            throw new IllegalStateException("Registered Mint references an unknown Mint Recipe Version");
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO registered_mint (
                    mint_id, service_identity, request_id, nation_id,
                    dimension_id, block_x, block_y, block_z,
                    operator_organization_id, license_id, automation_allowed,
                    recipe_version_id, actor_player_id, transaction_state, reason,
                    registered_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IDLE', ?, ?)
                """)) {
            insert.setString(1, mintId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, nationId.toString());
            insert.setString(5, dimensionId);
            insert.setInt(6, blockX);
            insert.setInt(7, blockY);
            insert.setInt(8, blockZ);
            insert.setString(9, operatorOrganizationId.toString());
            insert.setString(10, licenseId.toString());
            insert.setInt(11, automationAllowed ? 1 : 0);
            insert.setString(12, recipeVersionId.toString());
            insert.setString(13, actorPlayerId.toString());
            insert.setString(14, reason);
            insert.setLong(15, registeredAtEpochMillis);
            insert.executeUpdate();
            return registeredMint(mintId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to register Mint", failure);
        }
    }

    public synchronized StoredRegisteredMint registeredMint(UUID mintId) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM registered_mint WHERE mint_id = ?")) {
            query.setString(1, mintId.toString());
            return readRegisteredMint(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Registered Mint", failure);
        }
    }

    public synchronized StoredRegisteredMint registeredMint(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM registered_mint
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readRegisteredMint(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Registered Mint request", failure);
        }
    }

    private static void validateMintRecipeIngredients(
            List<StoredMintRecipeIngredient> ingredients) {
        Set<String> matchers = new HashSet<>();
        var groupQuantities = new java.util.HashMap<Integer, String>();
        for (StoredMintRecipeIngredient ingredient : ingredients) {
            if (ingredient == null) {
                throw new IllegalArgumentException("Mint Recipe ingredient cannot be null");
            }
            String matcher = ingredient.groupIndex()
                    + "|" + ingredient.matcherKind() + "|" + ingredient.matcherValue();
            if (!matchers.add(matcher)) {
                throw new IllegalArgumentException("Mint Recipe contains a duplicate matcher");
            }
            String quantity = ingredient.quantityUnits()
                    + "|" + ingredient.perFaceValueMinorUnits();
            String previous = groupQuantities.putIfAbsent(ingredient.groupIndex(), quantity);
            if (previous != null && !previous.equals(quantity)) {
                throw new IllegalArgumentException(
                        "Mint Recipe alternatives must share one exact quantity rule");
            }
        }
    }

    private static StoredMintRecipeVersion readMintRecipeVersion(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredMintRecipeVersion(
                    UUID.fromString(result.getString("recipe_version_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getInt("version_number"),
                    result.getLong("processing_duration_millis"),
                    result.getString("reason"),
                    result.getLong("published_at_epoch_millis"));
        }
    }

    private static StoredRegisteredMint readRegisteredMint(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredRegisteredMint(
                    UUID.fromString(result.getString("mint_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("nation_id")),
                    result.getString("dimension_id"),
                    result.getInt("block_x"),
                    result.getInt("block_y"),
                    result.getInt("block_z"),
                    UUID.fromString(result.getString("operator_organization_id")),
                    UUID.fromString(result.getString("license_id")),
                    result.getInt("automation_allowed") == 1,
                    UUID.fromString(result.getString("recipe_version_id")),
                    UUID.fromString(result.getString("actor_player_id")),
                    result.getString("transaction_state"),
                    result.getString("reason"),
                    result.getLong("registered_at_epoch_millis"));
        }
    }

    public synchronized StoredMintBatch prepareMintBatch(
            UUID batchId,
            String serviceIdentity,
            String requestId,
            UUID mintId,
            UUID periodId,
            UUID nationId,
            UUID recipeVersionId,
            long issuedMinorUnits,
            List<StoredMintMaterialStack> materials,
            UUID actorPlayerId,
            String reason,
            long preparedAtEpochMillis) {
        if (batchId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || mintId == null
                || periodId == null
                || nationId == null
                || recipeVersionId == null
                || issuedMinorUnits <= 0L
                || materials == null
                || materials.isEmpty()
                || actorPlayerId == null
                || reason == null
                || reason.isBlank()
                || preparedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Mint Batch preparation values are invalid");
        }
        StoredMintBatch replay = mintBatch(serviceIdentity, requestId);
        if (replay != null) {
            if (!replay.batchId().equals(batchId)
                    || !replay.mintId().equals(mintId)
                    || !replay.periodId().equals(periodId)
                    || !replay.nationId().equals(nationId)
                    || !replay.recipeVersionId().equals(recipeVersionId)
                    || replay.issuedMinorUnits() != issuedMinorUnits
                    || !replay.actorPlayerId().equals(actorPlayerId)
                    || !replay.reason().equals(reason)
                    || !mintBatchMaterials(batchId).equals(List.copyOf(materials))) {
                throw new IllegalArgumentException(
                        "Mint Batch preparation replay changed its immutable payload");
            }
            return replay;
        }
        StoredRegisteredMint mint = registeredMint(mintId);
        if (mint == null
                || !mint.nationId().equals(nationId)
                || !mint.recipeVersionId().equals(recipeVersionId)
                || !"IDLE".equals(mint.transactionState())) {
            throw new IllegalStateException("Registered Mint is not eligible for this batch");
        }
        StoredIssuanceQuotaPeriod period = issuanceQuotaPeriod(periodId);
        StoredNationalIssuanceQuota quota = nationalIssuanceQuota(periodId, nationId);
        if (period == null
                || quota == null
                || preparedAtEpochMillis < period.startsAtEpochMillis()
                || preparedAtEpochMillis >= period.endsAtEpochMillis()) {
            throw new IllegalStateException("Mint Batch has no active National Issuance Quota");
        }
        validateMintBatchMaterials(recipeVersionId, issuedMinorUnits, materials);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement reserveQuota = connection.prepareStatement("""
                        UPDATE national_issuance_quota
                        SET reserved_minor_units = reserved_minor_units + ?
                        WHERE period_id = ? AND nation_id = ?
                          AND reserved_minor_units + used_minor_units + ?
                              <= activated_minor_units
                        """);
                    PreparedStatement reserveMint = connection.prepareStatement("""
                        UPDATE registered_mint SET transaction_state = 'PREPARING'
                        WHERE mint_id = ? AND transaction_state = 'IDLE'
                        """);
                    PreparedStatement insertBatch = connection.prepareStatement("""
                        INSERT INTO mint_batch (
                            batch_id, service_identity, request_id, mint_id,
                            period_id, nation_id, recipe_version_id,
                            issued_minor_units, actor_player_id, state, custody_state,
                            reason, prepared_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING',
                                  'EXTERNAL_PENDING', ?, ?)
                        """);
                    PreparedStatement insertMaterial = connection.prepareStatement("""
                        INSERT INTO mint_batch_material (
                            batch_id, material_index, group_index, matcher_kind,
                            matcher_value, item_id, item_count
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                reserveQuota.setLong(1, issuedMinorUnits);
                reserveQuota.setString(2, periodId.toString());
                reserveQuota.setString(3, nationId.toString());
                reserveQuota.setLong(4, issuedMinorUnits);
                if (reserveQuota.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Mint Batch exceeds remaining National Issuance Quota");
                }
                reserveMint.setString(1, mintId.toString());
                if (reserveMint.executeUpdate() != 1) {
                    throw new IllegalStateException("Registered Mint changed concurrently");
                }
                insertBatch.setString(1, batchId.toString());
                insertBatch.setString(2, serviceIdentity);
                insertBatch.setString(3, requestId);
                insertBatch.setString(4, mintId.toString());
                insertBatch.setString(5, periodId.toString());
                insertBatch.setString(6, nationId.toString());
                insertBatch.setString(7, recipeVersionId.toString());
                insertBatch.setLong(8, issuedMinorUnits);
                insertBatch.setString(9, actorPlayerId.toString());
                insertBatch.setString(10, reason);
                insertBatch.setLong(11, preparedAtEpochMillis);
                insertBatch.executeUpdate();
                for (int index = 0; index < materials.size(); index++) {
                    StoredMintMaterialStack material = materials.get(index);
                    insertMaterial.setString(1, batchId.toString());
                    insertMaterial.setInt(2, index);
                    insertMaterial.setInt(3, material.groupIndex());
                    insertMaterial.setString(4, material.matcherKind());
                    insertMaterial.setString(5, material.matcherValue());
                    insertMaterial.setString(6, material.itemId());
                    insertMaterial.setLong(7, material.count());
                    insertMaterial.addBatch();
                }
                insertMaterial.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return mintBatch(batchId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to prepare Mint Batch", failure);
        }
    }

    public synchronized StoredMintBatch confirmMintBatchCustody(
            UUID batchId,
            String serviceIdentity,
            String requestId,
            String externalReference,
            long confirmedAtEpochMillis) {
        if (batchId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || externalReference == null
                || externalReference.isBlank()
                || confirmedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Mint Batch custody confirmation values are invalid");
        }
        StoredMintBatch batch = mintBatch(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("Unknown Mint Batch");
        }
        if (batch.custodyRequestId() != null) {
            if (!serviceIdentity.equals(batch.custodyServiceIdentity())
                    || !requestId.equals(batch.custodyRequestId())
                    || !externalReference.equals(batch.custodyExternalReference())) {
                throw new IllegalArgumentException(
                        "Mint Batch custody replay changed its immutable payload");
            }
            return batch;
        }
        if (!"PREPARING".equals(batch.state())
                || !"EXTERNAL_PENDING".equals(batch.custodyState())) {
            throw new IllegalStateException("Mint Batch is not awaiting material custody");
        }
        StoredMintRecipeVersion recipe = mintRecipeVersion(batch.recipeVersionId());
        long completesAt = Math.addExact(confirmedAtEpochMillis, recipe.processingDurationMillis());
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement updateBatch = connection.prepareStatement("""
                        UPDATE mint_batch
                        SET state = 'PROCESSING', custody_state = 'HELD',
                            custody_service_identity = ?, custody_request_id = ?,
                            custody_external_reference = ?,
                            processing_started_at_epoch_millis = ?,
                            processing_completes_at_epoch_millis = ?
                        WHERE batch_id = ? AND state = 'PREPARING'
                          AND custody_state = 'EXTERNAL_PENDING'
                        """);
                    PreparedStatement updateMint = connection.prepareStatement("""
                        UPDATE registered_mint SET transaction_state = 'PROCESSING'
                        WHERE mint_id = ? AND transaction_state = 'PREPARING'
                        """)) {
                updateBatch.setString(1, serviceIdentity);
                updateBatch.setString(2, requestId);
                updateBatch.setString(3, externalReference);
                updateBatch.setLong(4, confirmedAtEpochMillis);
                updateBatch.setLong(5, completesAt);
                updateBatch.setString(6, batchId.toString());
                if (updateBatch.executeUpdate() != 1) {
                    throw new IllegalStateException("Mint Batch changed concurrently");
                }
                updateMint.setString(1, batch.mintId().toString());
                if (updateMint.executeUpdate() != 1) {
                    throw new IllegalStateException("Registered Mint changed concurrently");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return mintBatch(batchId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to confirm Mint Batch custody", failure);
        }
    }

    public synchronized StoredMintBatch prepareMintBatchCancellation(
            UUID batchId,
            String serviceIdentity,
            String requestId,
            UUID actorPlayerId,
            String reason,
            long preparedAtEpochMillis) {
        if (batchId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || actorPlayerId == null
                || reason == null
                || reason.isBlank()
                || preparedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Mint Batch cancellation values are invalid");
        }
        StoredMintBatch batch = mintBatch(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("Unknown Mint Batch");
        }
        if (batch.cancellationRequestId() != null) {
            if (!serviceIdentity.equals(batch.cancellationServiceIdentity())
                    || !requestId.equals(batch.cancellationRequestId())
                    || !actorPlayerId.equals(batch.cancellationActorPlayerId())
                    || !reason.equals(batch.cancellationReason())) {
                throw new IllegalArgumentException(
                        "Mint Batch cancellation replay changed its immutable payload");
            }
            return batch;
        }
        if (!"PROCESSING".equals(batch.state()) || !"HELD".equals(batch.custodyState())) {
            throw new IllegalStateException("Mint Batch is not eligible for cancellation");
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement updateBatch = connection.prepareStatement("""
                        UPDATE mint_batch
                        SET state = 'CANCELLING', custody_state = 'RETURN_PENDING',
                            cancellation_service_identity = ?, cancellation_request_id = ?,
                            cancellation_actor_player_id = ?, cancellation_reason = ?,
                            cancellation_prepared_at_epoch_millis = ?
                        WHERE batch_id = ? AND state = 'PROCESSING' AND custody_state = 'HELD'
                        """);
                    PreparedStatement updateMint = connection.prepareStatement("""
                        UPDATE registered_mint SET transaction_state = 'RECOVERY'
                        WHERE mint_id = ? AND transaction_state = 'PROCESSING'
                        """)) {
                updateBatch.setString(1, serviceIdentity);
                updateBatch.setString(2, requestId);
                updateBatch.setString(3, actorPlayerId.toString());
                updateBatch.setString(4, reason);
                updateBatch.setLong(5, preparedAtEpochMillis);
                updateBatch.setString(6, batchId.toString());
                if (updateBatch.executeUpdate() != 1) {
                    throw new IllegalStateException("Mint Batch changed concurrently");
                }
                updateMint.setString(1, batch.mintId().toString());
                if (updateMint.executeUpdate() != 1) {
                    throw new IllegalStateException("Registered Mint changed concurrently");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return mintBatch(batchId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to prepare Mint Batch cancellation", failure);
        }
    }

    public synchronized StoredMintBatch confirmMintBatchMaterialReturn(
            UUID batchId,
            String serviceIdentity,
            String requestId,
            String externalReference,
            long confirmedAtEpochMillis) {
        if (batchId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || externalReference == null
                || externalReference.isBlank()
                || confirmedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Mint Batch material return values are invalid");
        }
        StoredMintBatch batch = mintBatch(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("Unknown Mint Batch");
        }
        if (batch.returnRequestId() != null) {
            if (!serviceIdentity.equals(batch.returnServiceIdentity())
                    || !requestId.equals(batch.returnRequestId())
                    || !externalReference.equals(batch.returnExternalReference())) {
                throw new IllegalArgumentException(
                        "Mint Batch material return replay changed its immutable payload");
            }
            return batch;
        }
        if (!"CANCELLING".equals(batch.state())
                || !"RETURN_PENDING".equals(batch.custodyState())) {
            throw new IllegalStateException("Mint Batch is not awaiting material return");
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement releaseQuota = connection.prepareStatement("""
                        UPDATE national_issuance_quota
                        SET reserved_minor_units = reserved_minor_units - ?
                        WHERE period_id = ? AND nation_id = ?
                          AND reserved_minor_units >= ?
                        """);
                    PreparedStatement updateBatch = connection.prepareStatement("""
                        UPDATE mint_batch
                        SET state = 'CANCELLED', custody_state = 'RETURNED',
                            return_service_identity = ?, return_request_id = ?,
                            return_external_reference = ?, cancelled_at_epoch_millis = ?
                        WHERE batch_id = ? AND state = 'CANCELLING'
                          AND custody_state = 'RETURN_PENDING'
                        """);
                    PreparedStatement updateMint = connection.prepareStatement("""
                        UPDATE registered_mint SET transaction_state = 'IDLE'
                        WHERE mint_id = ? AND transaction_state = 'RECOVERY'
                        """)) {
                releaseQuota.setLong(1, batch.issuedMinorUnits());
                releaseQuota.setString(2, batch.periodId().toString());
                releaseQuota.setString(3, batch.nationId().toString());
                releaseQuota.setLong(4, batch.issuedMinorUnits());
                if (releaseQuota.executeUpdate() != 1) {
                    throw new IllegalStateException("Mint Batch reserved quota cannot be released");
                }
                updateBatch.setString(1, serviceIdentity);
                updateBatch.setString(2, requestId);
                updateBatch.setString(3, externalReference);
                updateBatch.setLong(4, confirmedAtEpochMillis);
                updateBatch.setString(5, batchId.toString());
                if (updateBatch.executeUpdate() != 1) {
                    throw new IllegalStateException("Mint Batch changed concurrently");
                }
                updateMint.setString(1, batch.mintId().toString());
                if (updateMint.executeUpdate() != 1) {
                    throw new IllegalStateException("Registered Mint changed concurrently");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return mintBatch(batchId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to confirm Mint Batch material return", failure);
        }
    }
    public synchronized StoredMintBatch mintBatch(UUID batchId) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM mint_batch WHERE batch_id = ?")) {
            query.setString(1, batchId.toString());
            return readMintBatch(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Mint Batch", failure);
        }
    }

    public synchronized StoredMintBatch mintBatch(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM mint_batch
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readMintBatch(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Mint Batch request", failure);
        }
    }

    public synchronized List<StoredMintBatch> recoverableMintBatches() {
        List<StoredMintBatch> batches = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM mint_batch
                WHERE state NOT IN ('COMMITTED', 'CANCELLED')
                ORDER BY prepared_at_epoch_millis, batch_id
                """)) {
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    batches.add(readMintBatch(result));
                }
            }
            return List.copyOf(batches);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read recoverable Mint Batches", failure);
        }
    }
    public synchronized List<StoredMintMaterialStack> mintBatchMaterials(UUID batchId) {
        List<StoredMintMaterialStack> materials = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM mint_batch_material
                WHERE batch_id = ? ORDER BY material_index
                """)) {
            query.setString(1, batchId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    materials.add(new StoredMintMaterialStack(
                            result.getInt("group_index"),
                            result.getString("matcher_kind"),
                            result.getString("matcher_value"),
                            result.getString("item_id"),
                            result.getLong("item_count")));
                }
            }
            return List.copyOf(materials);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Mint Batch materials", failure);
        }
    }

    private void validateMintBatchMaterials(
            UUID recipeVersionId,
            long issuedMinorUnits,
            List<StoredMintMaterialStack> materials) {
        List<StoredMintRecipeIngredient> ingredients = mintRecipeIngredients(recipeVersionId);
        Set<Integer> recipeGroups = new HashSet<>();
        ingredients.forEach(ingredient -> recipeGroups.add(ingredient.groupIndex()));
        Set<Integer> suppliedGroups = new HashSet<>();
        if (materials.size() != recipeGroups.size()) {
            throw new IllegalStateException("Mint Batch material manifest is incomplete");
        }
        for (StoredMintMaterialStack material : materials) {
            if (material == null || !suppliedGroups.add(material.groupIndex())) {
                throw new IllegalStateException("Mint Batch material groups are invalid");
            }
            StoredMintRecipeIngredient selected = ingredients.stream()
                    .filter(ingredient -> ingredient.groupIndex() == material.groupIndex()
                            && ingredient.matcherKind().equals(material.matcherKind())
                            && ingredient.matcherValue().equals(material.matcherValue()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Mint Batch material is not allowed by its locked recipe"));
            if ("EXACT_ITEM".equals(selected.matcherKind())
                    && !selected.matcherValue().equals(material.itemId())) {
                throw new IllegalStateException("Mint Batch exact-item material does not match");
            }
            long expected = selected.quantityUnits();
            if (selected.perFaceValueMinorUnits() > 0L) {
                if (issuedMinorUnits % selected.perFaceValueMinorUnits() != 0L) {
                    throw new IllegalStateException(
                            "Mint Batch amount does not align with the recipe face value");
                }
                expected = Math.multiplyExact(
                        expected, issuedMinorUnits / selected.perFaceValueMinorUnits());
            }
            if (material.count() != expected) {
                throw new IllegalStateException("Mint Batch material count is not exact");
            }
        }
    }

    private static StoredMintBatch readMintBatch(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? readMintBatch(result) : null;
        }
    }

    private static StoredMintBatch readMintBatch(ResultSet result) throws SQLException {
            long started = result.getLong("processing_started_at_epoch_millis");
            Long startedAt = result.wasNull() ? null : started;
            long completes = result.getLong("processing_completes_at_epoch_millis");
            Long completesAt = result.wasNull() ? null : completes;
            return new StoredMintBatch(
                    UUID.fromString(result.getString("batch_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("mint_id")),
                    UUID.fromString(result.getString("period_id")),
                    UUID.fromString(result.getString("nation_id")),
                    UUID.fromString(result.getString("recipe_version_id")),
                    result.getLong("issued_minor_units"),
                    UUID.fromString(result.getString("actor_player_id")),
                    result.getString("state"),
                    result.getString("custody_state"),
                    result.getString("custody_service_identity"),
                    result.getString("custody_request_id"),
                    result.getString("custody_external_reference"),
                    result.getString("reason"),
                    result.getString("cancellation_service_identity"),
                    result.getString("cancellation_request_id"),
                    optionalUuid(result.getString("cancellation_actor_player_id")),
                    result.getString("cancellation_reason"),
                    optionalLong(result, "cancellation_prepared_at_epoch_millis"),
                    result.getString("return_service_identity"),
                    result.getString("return_request_id"),
                    result.getString("return_external_reference"),
                    optionalLong(result, "cancelled_at_epoch_millis"),
                    result.getLong("prepared_at_epoch_millis"),
                    startedAt,
                    completesAt);
    }

    private static UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Long optionalLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public synchronized StoredIssuanceQuotaPeriod publishIssuanceQuotaPeriod(
            UUID periodId,
            String serviceIdentity,
            String requestId,
            long startsAtEpochMillis,
            long endsAtEpochMillis,
            long hardCapMinorUnits,
            long globalQuotaMinorUnits,
            List<StoredNationalIssuanceQuotaAllocation> allocations,
            String reason,
            long publishedAtEpochMillis) {
        if (periodId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || allocations == null
                || reason == null
                || reason.isBlank()
                || startsAtEpochMillis < 0L
                || endsAtEpochMillis <= startsAtEpochMillis
                || hardCapMinorUnits < 0L
                || globalQuotaMinorUnits < 0L
                || globalQuotaMinorUnits > hardCapMinorUnits
                || publishedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Issuance Quota Period values are invalid");
        }
        StoredIssuanceQuotaPeriod replay = issuanceQuotaPeriod(serviceIdentity, requestId);
        if (replay != null) {
            if (!replay.periodId().equals(periodId)
                    || replay.startsAtEpochMillis() != startsAtEpochMillis
                    || replay.endsAtEpochMillis() != endsAtEpochMillis
                    || replay.hardCapMinorUnits() != hardCapMinorUnits
                    || replay.globalQuotaMinorUnits() != globalQuotaMinorUnits
                    || !replay.reason().equals(reason)
                    || !allocationSet(replay.periodId()).equals(Set.copyOf(allocations))) {
                throw new IllegalArgumentException(
                        "Issuance Quota Period replay changed its immutable payload");
            }
            return replay;
        }
        long totalAllocated = 0L;
        Set<UUID> nationIds = new HashSet<>();
        for (StoredNationalIssuanceQuotaAllocation allocation : allocations) {
            if (!nationIds.add(allocation.nationId())) {
                throw new IllegalArgumentException(
                        "Issuance Quota Period contains a duplicate Nation allocation");
            }
            totalAllocated = Math.addExact(totalAllocated, allocation.ceilingMinorUnits());
            if (nation(allocation.nationId()) == null) {
                throw new IllegalStateException(
                        "Issuance Quota allocation references an unknown Nation "
                                + allocation.nationId());
            }
        }
        if (totalAllocated > globalQuotaMinorUnits) {
            throw new IllegalStateException(
                    "National Issuance Quota ceilings exceed the global period quota");
        }
        long remainingHardCap = Math.subtractExact(
                hardCapMinorUnits, cumulativeNetIssuanceMinorUnits());
        if (globalQuotaMinorUnits > remainingHardCap) {
            throw new IllegalStateException(
                    "Global issuance quota exceeds remaining Issuance Hard Cap space");
        }
        try {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement overlap = connection.prepareStatement("""
                        SELECT 1 FROM issuance_quota_period
                        WHERE starts_at_epoch_millis < ? AND ends_at_epoch_millis > ?
                        LIMIT 1
                        """)) {
                    overlap.setLong(1, endsAtEpochMillis);
                    overlap.setLong(2, startsAtEpochMillis);
                    try (ResultSet result = overlap.executeQuery()) {
                        if (result.next()) {
                            throw new IllegalStateException(
                                    "Issuance Quota Period overlaps an existing period");
                        }
                    }
                }
                try (PreparedStatement insertPeriod = connection.prepareStatement("""
                        INSERT INTO issuance_quota_period (
                            period_id, service_identity, request_id,
                            starts_at_epoch_millis, ends_at_epoch_millis,
                            hard_cap_minor_units, global_quota_minor_units,
                            reason, published_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                    PreparedStatement insertAllocation = connection.prepareStatement("""
                        INSERT INTO national_issuance_quota (
                            period_id, nation_id, ceiling_minor_units,
                            activated_minor_units, reserved_minor_units, used_minor_units
                        ) VALUES (?, ?, ?, 0, 0, 0)
                        """)) {
                    insertPeriod.setString(1, periodId.toString());
                    insertPeriod.setString(2, serviceIdentity);
                    insertPeriod.setString(3, requestId);
                    insertPeriod.setLong(4, startsAtEpochMillis);
                    insertPeriod.setLong(5, endsAtEpochMillis);
                    insertPeriod.setLong(6, hardCapMinorUnits);
                    insertPeriod.setLong(7, globalQuotaMinorUnits);
                    insertPeriod.setString(8, reason);
                    insertPeriod.setLong(9, publishedAtEpochMillis);
                    insertPeriod.executeUpdate();
                    for (StoredNationalIssuanceQuotaAllocation allocation : allocations) {
                        insertAllocation.setString(1, periodId.toString());
                        insertAllocation.setString(2, allocation.nationId().toString());
                        insertAllocation.setLong(3, allocation.ceilingMinorUnits());
                        insertAllocation.addBatch();
                    }
                    insertAllocation.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return issuanceQuotaPeriod(periodId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to publish Issuance Quota Period", failure);
        }
    }

    public synchronized StoredIssuanceQuotaPeriod issuanceQuotaPeriod(UUID periodId) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM issuance_quota_period WHERE period_id = ?")) {
            query.setString(1, periodId.toString());
            return readIssuanceQuotaPeriod(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Issuance Quota Period", failure);
        }
    }

    public synchronized StoredIssuanceQuotaPeriod issuanceQuotaPeriod(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM issuance_quota_period
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readIssuanceQuotaPeriod(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Issuance Quota Period request", failure);
        }
    }

    public synchronized StoredNationalIssuanceQuota nationalIssuanceQuota(
            UUID periodId, UUID nationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM national_issuance_quota
                WHERE period_id = ? AND nation_id = ?
                """)) {
            query.setString(1, periodId.toString());
            query.setString(2, nationId.toString());
            return readNationalIssuanceQuota(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read National Issuance Quota", failure);
        }
    }

    public synchronized StoredNationalIssuanceQuotaActivation activateNationalIssuanceQuota(
            UUID activationId,
            String serviceIdentity,
            String requestId,
            UUID periodId,
            UUID nationId,
            UUID actorPlayerId,
            long activatedMinorUnits,
            String reason,
            long activatedAtEpochMillis) {
        if (activationId == null
                || serviceIdentity == null
                || serviceIdentity.isBlank()
                || requestId == null
                || requestId.isBlank()
                || periodId == null
                || nationId == null
                || actorPlayerId == null
                || activatedMinorUnits < 0L
                || reason == null
                || reason.isBlank()
                || activatedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("National Issuance Quota activation values are invalid");
        }
        StoredNationalIssuanceQuotaActivation replay =
                nationalIssuanceQuotaActivation(serviceIdentity, requestId);
        if (replay != null) {
            if (!replay.periodId().equals(periodId)
                    || !replay.nationId().equals(nationId)
                    || !replay.actorPlayerId().equals(actorPlayerId)
                    || replay.activatedMinorUnits() != activatedMinorUnits
                    || !replay.reason().equals(reason)) {
                throw new IllegalArgumentException(
                        "National Issuance Quota activation replay changed its payload");
            }
            return replay;
        }
        StoredNationalIssuanceQuota quota = nationalIssuanceQuota(periodId, nationId);
        if (quota == null) {
            throw new IllegalArgumentException("Unknown National Issuance Quota");
        }
        long encumbered = Math.addExact(quota.reservedMinorUnits(), quota.usedMinorUnits());
        if (activatedMinorUnits > quota.ceilingMinorUnits() || activatedMinorUnits < encumbered) {
            throw new IllegalStateException(
                    "National Issuance Quota activation exceeds its ceiling or revokes encumbered quota");
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE national_issuance_quota SET activated_minor_units = ?
                        WHERE period_id = ? AND nation_id = ?
                        """);
                    PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO national_issuance_quota_activation (
                            activation_id, service_identity, request_id,
                            period_id, nation_id, actor_player_id,
                            activated_minor_units, reason, activated_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                update.setLong(1, activatedMinorUnits);
                update.setString(2, periodId.toString());
                update.setString(3, nationId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("National Issuance Quota changed concurrently");
                }
                insert.setString(1, activationId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, periodId.toString());
                insert.setString(5, nationId.toString());
                insert.setString(6, actorPlayerId.toString());
                insert.setLong(7, activatedMinorUnits);
                insert.setString(8, reason);
                insert.setLong(9, activatedAtEpochMillis);
                insert.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return nationalIssuanceQuotaActivation(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to activate National Issuance Quota", failure);
        }
    }

    public synchronized StoredNationalIssuanceQuotaActivation nationalIssuanceQuotaActivation(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM national_issuance_quota_activation
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readNationalIssuanceQuotaActivation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read National Issuance Quota activation", failure);
        }
    }

    private Set<StoredNationalIssuanceQuotaAllocation> allocationSet(UUID periodId) {
        Set<StoredNationalIssuanceQuotaAllocation> allocations = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT nation_id, ceiling_minor_units FROM national_issuance_quota
                WHERE period_id = ?
                """)) {
            query.setString(1, periodId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    allocations.add(new StoredNationalIssuanceQuotaAllocation(
                            UUID.fromString(result.getString("nation_id")),
                            result.getLong("ceiling_minor_units")));
                }
            }
            return Set.copyOf(allocations);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read National Issuance allocations", failure);
        }
    }

    private static StoredIssuanceQuotaPeriod readIssuanceQuotaPeriod(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredIssuanceQuotaPeriod(
                    UUID.fromString(result.getString("period_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getLong("starts_at_epoch_millis"),
                    result.getLong("ends_at_epoch_millis"),
                    result.getLong("hard_cap_minor_units"),
                    result.getLong("global_quota_minor_units"),
                    result.getString("reason"),
                    result.getLong("published_at_epoch_millis"));
        }
    }

    private static StoredNationalIssuanceQuota readNationalIssuanceQuota(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredNationalIssuanceQuota(
                    UUID.fromString(result.getString("period_id")),
                    UUID.fromString(result.getString("nation_id")),
                    result.getLong("ceiling_minor_units"),
                    result.getLong("activated_minor_units"),
                    result.getLong("reserved_minor_units"),
                    result.getLong("used_minor_units"));
        }
    }

    private static StoredNationalIssuanceQuotaActivation readNationalIssuanceQuotaActivation(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredNationalIssuanceQuotaActivation(
                    UUID.fromString(result.getString("activation_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("period_id")),
                    UUID.fromString(result.getString("nation_id")),
                    UUID.fromString(result.getString("actor_player_id")),
                    result.getLong("activated_minor_units"),
                    result.getString("reason"),
                    result.getLong("activated_at_epoch_millis"));
        }
    }

    public synchronized StoredMonetarySupplyEvent monetarySupplyEvent(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM monetary_supply_event
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readMonetarySupplyEvent(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Monetary Supply event", failure);
        }
    }

    public synchronized List<StoredMonetarySupplyEvent> monetarySupplyEvents() {
        List<StoredMonetarySupplyEvent> events = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM monetary_supply_event
                ORDER BY confirmed_at_epoch_millis, event_id
                """)) {
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    events.add(storedMonetarySupplyEvent(result));
                }
            }
            return List.copyOf(events);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list Monetary Supply events", failure);
        }
    }

    public synchronized long cumulativeNetIssuanceMinorUnits() {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT cumulative_net_issuance_minor_units
                        FROM monetary_supply_summary WHERE singleton = 1
                        """)) {
            if (!result.next()) {
                throw new IllegalStateException("Monetary Supply summary is missing");
            }
            return result.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Cumulative Net Issuance", failure);
        }
    }

    public synchronized StoredMonetarySupplyEvent confirmMonetarySupplyChange(
            UUID eventId,
            String serviceIdentity,
            String requestId,
            String changeKind,
            long amountMinorUnits,
            String externalReference,
            String reason,
            long confirmedAtEpochMillis,
            long hardCapMinorUnits) {
        StoredMonetarySupplyEvent replay = monetarySupplyEvent(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            long current = cumulativeNetIssuanceMinorUnits();
            long next = changeKind.equals("ISSUANCE")
                    ? Math.addExact(current, amountMinorUnits)
                    : Math.subtractExact(current, amountMinorUnits);
            if (next > hardCapMinorUnits) {
                throw new org.civiceconomy.monetary.IssuanceHardCapExceededException();
            }
            if (next < 0L) {
                throw new org.civiceconomy.monetary.DestructionExceedsNetIssuanceException();
            }
            if (changeKind.equals("PERMANENT_DESTRUCTION")
                    && next < pendingPermanentDestructionMinorUnits()) {
                throw new org.civiceconomy.monetary.DestructionExceedsNetIssuanceException();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO monetary_supply_event (
                        event_id, service_identity, request_id, change_kind,
                        amount_minor_units, external_reference, reason,
                        confirmed_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, eventId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, changeKind);
                insert.setLong(5, amountMinorUnits);
                insert.setString(6, externalReference);
                insert.setString(7, reason);
                insert.setLong(8, confirmedAtEpochMillis);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE monetary_supply_summary
                    SET cumulative_net_issuance_minor_units = ?
                    WHERE singleton = 1
                    """)) {
                update.setLong(1, next);
                update.executeUpdate();
            }
            connection.commit();
            return monetarySupplyEvent(serviceIdentity, requestId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("Unable to confirm Monetary Supply change", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(failure);
                } else {
                    throw new IllegalStateException(
                            "Unable to restore Monetary Supply transaction mode", failure);
                }
            }
        }
    }

    public synchronized StoredPermanentDestructionOperation preparePermanentDestruction(
            UUID operationId,
            String serviceIdentity,
            String requestId,
            String sourceAccount,
            long amountMinorUnits,
            String reason,
            long preparedAtEpochMillis) {
        StoredPermanentDestructionOperation replay =
                permanentDestructionOperation(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            long pending = pendingPermanentDestructionMinorUnits();
            long reservedAfter = Math.addExact(pending, amountMinorUnits);
            if (reservedAfter > cumulativeNetIssuanceMinorUnits()) {
                throw new org.civiceconomy.monetary.DestructionExceedsNetIssuanceException();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO permanent_destruction_operation (
                        operation_id, service_identity, request_id, source_account,
                        amount_minor_units, reason, state, prepared_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                    """)) {
                insert.setString(1, operationId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, sourceAccount);
                insert.setLong(5, amountMinorUnits);
                insert.setString(6, reason);
                insert.setLong(7, preparedAtEpochMillis);
                insert.executeUpdate();
            }
            connection.commit();
            return permanentDestructionOperation(serviceIdentity, requestId);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("Unable to prepare Permanent Destruction", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Permanent Destruction preparation", primaryFailure);
        }
    }

    public synchronized StoredPermanentDestructionOperation permanentDestructionOperation(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM permanent_destruction_operation
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readPermanentDestructionOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Permanent Destruction operation", failure);
        }
    }

    public synchronized List<StoredPermanentDestructionOperation> permanentDestructionOperations() {
        List<StoredPermanentDestructionOperation> operations = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM permanent_destruction_operation
                ORDER BY prepared_at_epoch_millis, operation_id
                """)) {
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    operations.add(storedPermanentDestructionOperation(result));
                }
            }
            return List.copyOf(operations);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list Permanent Destruction operations", failure);
        }
    }

    public synchronized StoredMonetarySupplyEvent commitPermanentDestruction(
            UUID operationId, long committedAtEpochMillis) {
        String externalReference = "permanent-destruction:" + operationId;
        RuntimeException primaryFailure = null;
        try {
            connection.setAutoCommit(false);
            StoredPermanentDestructionOperation operation =
                    permanentDestructionOperation(operationId);
            if (operation == null) {
                throw new IllegalArgumentException("Unknown Permanent Destruction " + operationId);
            }
            StoredMonetarySupplyEvent replay = monetarySupplyEventByExternalReference(
                    "PERMANENT_DESTRUCTION", externalReference);
            if (operation.state().equals("COMMITTED")) {
                if (replay == null) {
                    throw new IllegalStateException(
                            "Committed Permanent Destruction has no Monetary Supply event "
                                    + operationId);
                }
                connection.commit();
                return replay;
            }
            long next = Math.subtractExact(
                    cumulativeNetIssuanceMinorUnits(), operation.amountMinorUnits());
            if (next < 0L) {
                throw new org.civiceconomy.monetary.DestructionExceedsNetIssuanceException();
            }
            UUID eventId = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO monetary_supply_event (
                        event_id, service_identity, request_id, change_kind,
                        amount_minor_units, external_reference, reason,
                        confirmed_at_epoch_millis
                    ) VALUES (?, ?, ?, 'PERMANENT_DESTRUCTION', ?, ?, ?, ?)
                    """)) {
                insert.setString(1, eventId.toString());
                insert.setString(2, operation.serviceIdentity());
                insert.setString(3, operation.requestId());
                insert.setLong(4, operation.amountMinorUnits());
                insert.setString(5, externalReference);
                insert.setString(6, operation.reason());
                insert.setLong(7, committedAtEpochMillis);
                insert.executeUpdate();
            }
            try (PreparedStatement updateSummary = connection.prepareStatement("""
                    UPDATE monetary_supply_summary
                    SET cumulative_net_issuance_minor_units = ? WHERE singleton = 1
                    """);
                    PreparedStatement updateOperation = connection.prepareStatement("""
                    UPDATE permanent_destruction_operation
                    SET state = 'COMMITTED', committed_at_epoch_millis = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """)) {
                updateSummary.setLong(1, next);
                updateSummary.executeUpdate();
                updateOperation.setLong(1, committedAtEpochMillis);
                updateOperation.setString(2, operationId.toString());
                if (updateOperation.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Permanent Destruction did not advance " + operationId);
                }
            }
            connection.commit();
            return monetarySupplyEventByExternalReference(
                    "PERMANENT_DESTRUCTION", externalReference);
        } catch (RuntimeException | SQLException failure) {
            RuntimeException propagated = failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("Unable to commit Permanent Destruction", failure);
            primaryFailure = propagated;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                propagated.addSuppressed(rollbackFailure);
            }
            throw propagated;
        } finally {
            restoreAutoCommit("Permanent Destruction commit", primaryFailure);
        }
    }

    private StoredPermanentDestructionOperation permanentDestructionOperation(UUID operationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM permanent_destruction_operation WHERE operation_id = ?
                """)) {
            query.setString(1, operationId.toString());
            return readPermanentDestructionOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Permanent Destruction operation", failure);
        }
    }

    private long pendingPermanentDestructionMinorUnits() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT COALESCE(SUM(amount_minor_units), 0)
                        FROM permanent_destruction_operation
                        WHERE state = 'PREPARED'
                        """)) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private StoredMonetarySupplyEvent monetarySupplyEventByExternalReference(
            String changeKind, String externalReference) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM monetary_supply_event
                WHERE change_kind = ? AND external_reference = ?
                """)) {
            query.setString(1, changeKind);
            query.setString(2, externalReference);
            return readMonetarySupplyEvent(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Monetary Supply external reference", failure);
        }
    }

    private void restoreAutoCommit(String operation, RuntimeException primaryFailure) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException failure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(failure);
            } else {
                throw new IllegalStateException(
                        "Unable to restore " + operation + " transaction mode", failure);
            }
        }
    }

    public synchronized StoredTerritoryClaimPermit territoryClaimPermit(UUID permitId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit WHERE permit_id = ?
                """)) {
            query.setString(1, permitId.toString());
            return readTerritoryClaimPermit(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Claim Permit", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermit territoryClaimPermitByTarget(
            UUID nationId, String dimensionId, int chunkX, int chunkZ) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit
                WHERE nation_id = ? AND dimension_id = ? AND chunk_x = ? AND chunk_z = ?
                ORDER BY issued_at_epoch_millis DESC, permit_id DESC
                LIMIT 1
                """)) {
            query.setString(1, nationId.toString());
            query.setString(2, dimensionId);
            query.setInt(3, chunkX);
            query.setInt(4, chunkZ);
            return readTerritoryClaimPermit(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Claim Permit target", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermit territoryClaimPermitByPrepaymentTransaction(
            UUID transactionId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit
                WHERE prepayment_transaction_id = ?
                """)) {
            query.setString(1, transactionId.toString());
            return readTerritoryClaimPermit(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Claim Permit prepayment", failure);
        }
    }

    public synchronized List<StoredTerritoryClaimPermit> dueTerritoryClaimPermits(
            long asOfEpochMillis) {
        List<StoredTerritoryClaimPermit> permits = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT permit.* FROM territory_claim_permit permit
                WHERE permit.state = 'READY'
                  AND permit.expires_at_epoch_millis <= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM territory_claim_permit_compensation compensation
                      WHERE compensation.permit_id = permit.permit_id
                  )
                ORDER BY permit.expires_at_epoch_millis, permit.permit_id
                """)) {
            query.setLong(1, asOfEpochMillis);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    permits.add(storedTerritoryClaimPermit(result));
                }
            }
            return List.copyOf(permits);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to list due Territory Claim Permits", failure);
        }
    }

    public synchronized List<StoredTerritoryClaimPermit> readyTerritoryClaimPermits(
            long asOfEpochMillis) {
        List<StoredTerritoryClaimPermit> permits = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT permit.* FROM territory_claim_permit permit
                WHERE permit.state = 'READY'
                  AND permit.expires_at_epoch_millis > ?
                  AND NOT EXISTS (
                      SELECT 1 FROM territory_claim_permit_compensation compensation
                      WHERE compensation.permit_id = permit.permit_id
                  )
                ORDER BY permit.issued_at_epoch_millis, permit.permit_id
                """)) {
            query.setLong(1, asOfEpochMillis);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    permits.add(storedTerritoryClaimPermit(result));
                }
            }
            return List.copyOf(permits);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to list READY Territory Claim Permits", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermitConsumption territoryClaimPermitConsumption(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit_consumption
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryClaimPermitConsumption(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Territory Claim Permit consumption", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermitCompensation territoryClaimPermitCompensation(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit_compensation
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readTerritoryClaimPermitCompensation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Claim Permit compensation request", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermitCompensation territoryClaimPermitCompensation(
            UUID permitId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit_compensation WHERE permit_id = ?
                """)) {
            query.setString(1, permitId.toString());
            return readTerritoryClaimPermitCompensation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Claim Permit compensation", failure);
        }
    }

    public synchronized List<StoredTerritoryClaimPermitCompensation>
            incompleteTerritoryClaimPermitCompensations() {
        List<StoredTerritoryClaimPermitCompensation> compensations = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit_compensation
                WHERE completed_at_epoch_millis IS NULL
                ORDER BY requested_at_epoch_millis, compensation_id
                """);
                ResultSet result = query.executeQuery()) {
            while (result.next()) {
                compensations.add(storedTerritoryClaimPermitCompensation(result));
            }
            return List.copyOf(compensations);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to list incomplete Territory Claim Permit compensations", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermitCompensation
            startTerritoryClaimPermitCompensation(
                    UUID compensationId,
                    UUID permitId,
                    String serviceIdentity,
                    String requestId,
                    String actorIdentity,
                    String kind,
                    String reason,
                    String refundRequestId,
                    long requestedAtEpochMillis) {
        StoredTerritoryClaimPermitCompensation replay =
                territoryClaimPermitCompensation(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredTerritoryClaimPermitCompensation existing =
                territoryClaimPermitCompensation(permitId);
        if (existing != null) {
            return existing;
        }
        StoredTerritoryClaimPermit permit = territoryClaimPermit(permitId);
        if (permit == null) {
            throw new IllegalArgumentException("Unknown Territory Claim Permit " + permitId);
        }
        if (!"READY".equals(permit.state())) {
            throw new IllegalStateException(
                    "Territory Claim Permit cannot be compensated from state " + permit.state());
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_claim_permit_compensation (
                    compensation_id, permit_id, service_identity, request_id,
                    actor_identity, kind, reason, refund_request_id,
                    requested_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, compensationId.toString());
            insert.setString(2, permitId.toString());
            insert.setString(3, serviceIdentity);
            insert.setString(4, requestId);
            insert.setString(5, actorIdentity);
            insert.setString(6, kind);
            insert.setString(7, reason);
            insert.setString(8, refundRequestId);
            insert.setLong(9, requestedAtEpochMillis);
            insert.executeUpdate();
            return territoryClaimPermitCompensation(permitId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to start Territory Claim Permit compensation", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermit completeTerritoryClaimPermitCompensation(
            UUID compensationId, long completedAtEpochMillis) {
        StoredTerritoryClaimPermitCompensation compensation =
                territoryClaimPermitCompensationById(compensationId);
        if (compensation == null) {
            throw new IllegalArgumentException(
                    "Unknown Territory Claim Permit compensation " + compensationId);
        }
        StoredTerritoryClaimPermit permit = territoryClaimPermit(compensation.permitId());
        if (compensation.completedAtEpochMillis() != null) {
            return permit;
        }
        if (compensation.refundTransactionId() == null) {
            throw new IllegalStateException(
                    "Territory Claim Permit compensation has no refund transaction");
        }
        StoredPaymentTransaction refund = paymentTransaction(compensation.refundTransactionId());
        if (refund == null
                || !"REFUND".equals(refund.kind())
                || !"CIVIC_COMMITTED".equals(refund.state())
                || !refund.parentTransactionId().equals(permit.prepaymentTransactionId())
                || refund.amountMinorUnits() != permit.prepaymentMinorUnits()) {
            throw new IllegalStateException(
                    "Territory Claim Permit compensation refund is not committed exactly");
        }
        String finalState = switch (compensation.kind()) {
            case "CANCEL" -> "CANCELLED";
            case "EXPIRE" -> "EXPIRED";
            default -> throw new IllegalStateException(
                    "Unknown Territory Claim Permit compensation kind " + compensation.kind());
        };
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement updatePermit = connection.prepareStatement("""
                        UPDATE territory_claim_permit SET state = ?
                        WHERE permit_id = ? AND state = 'READY'
                        """);
                    PreparedStatement complete = connection.prepareStatement("""
                        UPDATE territory_claim_permit_compensation
                        SET completed_at_epoch_millis = ?
                        WHERE compensation_id = ? AND completed_at_epoch_millis IS NULL
                        """)) {
                updatePermit.setString(1, finalState);
                updatePermit.setString(2, permit.permitId().toString());
                if (updatePermit.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Territory Claim Permit state changed before compensation completed");
                }
                complete.setLong(1, completedAtEpochMillis);
                complete.setString(2, compensationId.toString());
                if (complete.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Territory Claim Permit compensation was already completed");
                }
                connection.commit();
                return territoryClaimPermit(permit.permitId());
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to complete Territory Claim Permit compensation", failure);
        }
    }

    private StoredTerritoryClaimPermitCompensation territoryClaimPermitCompensationById(
            UUID compensationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_claim_permit_compensation WHERE compensation_id = ?
                """)) {
            query.setString(1, compensationId.toString());
            return readTerritoryClaimPermitCompensation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory Claim Permit compensation ID", failure);
        }
    }

    public synchronized StoredTerritoryClaimPermit consumeTerritoryClaimPermit(
            UUID permitId,
            String serviceIdentity,
            String requestId,
            UUID nationId,
            UUID ftbTeamId,
            UUID actorPlayerId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            long consumedAtEpochMillis) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement consume = connection.prepareStatement("""
                    INSERT INTO territory_claim_permit_consumption (
                        permit_id, service_identity, request_id, nation_id, ftb_team_id,
                        actor_player_id, dimension_id, chunk_x, chunk_z, consumed_at_epoch_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
                    PreparedStatement update = connection.prepareStatement("""
                    UPDATE territory_claim_permit SET state = 'CONSUMED'
                    WHERE permit_id = ? AND state = 'READY'
                      AND NOT EXISTS (
                          SELECT 1 FROM territory_claim_permit_compensation
                          WHERE permit_id = ?
                      )
                    """)) {
                consume.setString(1, permitId.toString());
                consume.setString(2, serviceIdentity);
                consume.setString(3, requestId);
                consume.setString(4, nationId.toString());
                consume.setString(5, ftbTeamId.toString());
                consume.setString(6, actorPlayerId.toString());
                consume.setString(7, dimensionId);
                consume.setInt(8, chunkX);
                consume.setInt(9, chunkZ);
                consume.setLong(10, consumedAtEpochMillis);
                consume.executeUpdate();
                update.setString(1, permitId.toString());
                update.setString(2, permitId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Territory Claim Permit was not READY " + permitId);
                }
            }
            connection.commit();
            return territoryClaimPermit(permitId);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Unable to consume Territory Claim Permit", failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to restore Civic database auto-commit", failure);
            }
        }
    }

    public synchronized StoredTerritoryClaimPermit issueTerritoryClaimPermit(
            UUID permitId,
            String serviceIdentity,
            String requestId,
            UUID nationId,
            UUID ftbTeamId,
            UUID actorPlayerId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            int quotedCurrentClaimedChunks,
            int quotedFreeAllocation,
            long prepaymentMinorUnits,
            UUID prepaymentTransactionId,
            String state,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis) {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_claim_permit (
                    permit_id, service_identity, request_id, nation_id, ftb_team_id,
                    actor_player_id, dimension_id, chunk_x, chunk_z,
                    quoted_current_claimed_chunks, quoted_free_allocation,
                    prepayment_minor_units, prepayment_transaction_id, state,
                    issued_at_epoch_millis, expires_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, permitId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, nationId.toString());
            insert.setString(5, ftbTeamId.toString());
            insert.setString(6, actorPlayerId.toString());
            insert.setString(7, dimensionId);
            insert.setInt(8, chunkX);
            insert.setInt(9, chunkZ);
            insert.setInt(10, quotedCurrentClaimedChunks);
            insert.setInt(11, quotedFreeAllocation);
            insert.setLong(12, prepaymentMinorUnits);
            insert.setString(13, prepaymentTransactionId.toString());
            insert.setString(14, state);
            insert.setLong(15, issuedAtEpochMillis);
            insert.setLong(16, expiresAtEpochMillis);
            insert.executeUpdate();
            return territoryClaimPermit(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to issue Territory Claim Permit", failure);
        }
    }

    public synchronized StoredTerritoryExpansionPricingPolicy currentTerritoryExpansionPricingPolicy(
            long asOfEpochMillis) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_expansion_pricing_policy
                WHERE effective_at_epoch_millis <= ?
                ORDER BY effective_at_epoch_millis DESC,
                         recorded_at_epoch_millis DESC,
                         policy_id DESC
                LIMIT 1
                """)) {
            query.setLong(1, asOfEpochMillis);
            return readTerritoryExpansionPricingPolicy(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read current Territory Expansion pricing", failure);
        }
    }

    public synchronized StoredTerritoryMaintenancePolicy currentTerritoryMaintenancePolicy(
            long asOfEpochMillis) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_maintenance_policy
                WHERE effective_at_epoch_millis <= ?
                ORDER BY effective_at_epoch_millis DESC,
                         recorded_at_epoch_millis DESC,
                         policy_id DESC
                LIMIT 1
                """)) {
            query.setLong(1, asOfEpochMillis);
            return readTerritoryMaintenancePolicy(query);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read current Territory Maintenance policy", failure);
        }
    }

    public synchronized StoredTerritoryMaintenancePolicy scheduleTerritoryMaintenancePolicy(
            UUID policyId,
            String serviceIdentity,
            String requestId,
            String actorIdentity,
            long cycleDurationMillis,
            long baseMaintenancePerChargeableClaimMinorUnits,
            int enclaveAndCrossDimensionMultiplierBasisPoints,
            long forceLoadSurchargeMinorUnits,
            long restorationFeeMinorUnits,
            long restorationCooldownMillis,
            int destructionBasisPoints,
            long effectiveAtEpochMillis,
            String reason,
            long recordedAtEpochMillis) {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_maintenance_policy (
                    policy_id, service_identity, request_id, actor_identity,
                    cycle_duration_millis,
                    base_maintenance_per_chargeable_claim_minor_units,
                    enclave_cross_dimension_multiplier_basis_points,
                    force_load_surcharge_minor_units,
                    restoration_fee_minor_units, restoration_cooldown_millis,
                    destruction_basis_points,
                    effective_at_epoch_millis, reason, recorded_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, policyId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, actorIdentity);
            insert.setLong(5, cycleDurationMillis);
            insert.setLong(6, baseMaintenancePerChargeableClaimMinorUnits);
            insert.setInt(7, enclaveAndCrossDimensionMultiplierBasisPoints);
            insert.setLong(8, forceLoadSurchargeMinorUnits);
            insert.setLong(9, restorationFeeMinorUnits);
            insert.setLong(10, restorationCooldownMillis);
            insert.setInt(11, destructionBasisPoints);
            insert.setLong(12, effectiveAtEpochMillis);
            insert.setString(13, reason);
            insert.setLong(14, recordedAtEpochMillis);
            insert.executeUpdate();
            return territoryMaintenancePolicy(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to schedule Territory Maintenance policy", failure);
        }
    }

    public synchronized StoredTerritoryExpansionPricingPolicy scheduleTerritoryExpansionPricingPolicy(
            UUID policyId,
            String serviceIdentity,
            String requestId,
            String actorIdentity,
            long firstOverageChunkCost,
            long additionalMarginalCost,
            long effectiveAtEpochMillis,
            String reason,
            long recordedAtEpochMillis) {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_expansion_pricing_policy (
                    policy_id, service_identity, request_id, actor_identity,
                    first_overage_chunk_cost, additional_marginal_cost,
                    effective_at_epoch_millis, reason, recorded_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, policyId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, actorIdentity);
            insert.setLong(5, firstOverageChunkCost);
            insert.setLong(6, additionalMarginalCost);
            insert.setLong(7, effectiveAtEpochMillis);
            insert.setString(8, reason);
            insert.setLong(9, recordedAtEpochMillis);
            insert.executeUpdate();
            return territoryExpansionPricingPolicy(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to schedule Territory Expansion pricing", failure);
        }
    }

    public synchronized StoredTerritoryFreeAllocationPolicy currentTerritoryFreeAllocationPolicy(
            long asOfEpochMillis) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM territory_free_allocation_policy
                WHERE effective_at_epoch_millis <= ?
                ORDER BY effective_at_epoch_millis DESC,
                         recorded_at_epoch_millis DESC,
                         policy_id DESC
                LIMIT 1
                """)) {
            query.setLong(1, asOfEpochMillis);
            return readTerritoryFreeAllocationPolicy(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read current Territory Free Allocation policy", failure);
        }
    }

    public synchronized StoredTerritoryFreeAllocationPolicy scheduleTerritoryFreeAllocationPolicy(
            UUID policyId,
            String serviceIdentity,
            String requestId,
            String actorIdentity,
            int baseChunks,
            int chunksPerEffectiveCitizen,
            long effectiveAtEpochMillis,
            String reason,
            long recordedAtEpochMillis) {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO territory_free_allocation_policy (
                    policy_id, service_identity, request_id, actor_identity,
                    base_chunks, chunks_per_effective_citizen,
                    effective_at_epoch_millis, reason, recorded_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, policyId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, actorIdentity);
            insert.setInt(5, baseChunks);
            insert.setInt(6, chunksPerEffectiveCitizen);
            insert.setLong(7, effectiveAtEpochMillis);
            insert.setString(8, reason);
            insert.setLong(9, recordedAtEpochMillis);
            insert.executeUpdate();
            return territoryFreeAllocationPolicy(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to schedule Territory Free Allocation policy", failure);
        }
    }

    public synchronized StoredNationFiscalPermissionGrant nationFiscalPermissionGrant(UUID grantId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_fiscal_permission_grant WHERE grant_id = ?
                """)) {
            query.setString(1, grantId.toString());
            return readNationFiscalPermissionGrant(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation Fiscal Permission grant", failure);
        }
    }

    public synchronized StoredNationFiscalPermissionGrant activeNationFiscalPermissionGrant(
            UUID nationId, UUID playerId, String permission) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT grant_row.* FROM nation_fiscal_permission_grant grant_row
                LEFT JOIN nation_fiscal_permission_revocation revocation
                    ON revocation.grant_id = grant_row.grant_id
                WHERE grant_row.nation_id = ?
                    AND grant_row.player_id = ?
                    AND grant_row.permission = ?
                    AND revocation.grant_id IS NULL
                """)) {
            query.setString(1, nationId.toString());
            query.setString(2, playerId.toString());
            query.setString(3, permission);
            return readNationFiscalPermissionGrant(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read active Nation Fiscal Permission grant", failure);
        }
    }

    public synchronized List<StoredNationFiscalPermissionGrant> activeNationFiscalPermissionGrants(
            UUID nationId, UUID playerId) {
        List<StoredNationFiscalPermissionGrant> grants = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT grant_row.* FROM nation_fiscal_permission_grant grant_row
                LEFT JOIN nation_fiscal_permission_revocation revocation
                    ON revocation.grant_id = grant_row.grant_id
                WHERE grant_row.nation_id = ?
                    AND grant_row.player_id = ?
                    AND revocation.grant_id IS NULL
                ORDER BY grant_row.permission, grant_row.granted_at_epoch_millis, grant_row.grant_id
                """)) {
            query.setString(1, nationId.toString());
            query.setString(2, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    grants.add(storedNationFiscalPermissionGrant(result));
                }
            }
            return List.copyOf(grants);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list active Nation Fiscal Permission grants", failure);
        }
    }

    public synchronized List<StoredNationFiscalPermissionGrant> activeNationFiscalPermissionGrants(
            UUID nationId) {
        List<StoredNationFiscalPermissionGrant> grants = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT grant_row.* FROM nation_fiscal_permission_grant grant_row
                LEFT JOIN nation_fiscal_permission_revocation revocation
                    ON revocation.grant_id = grant_row.grant_id
                WHERE grant_row.nation_id = ? AND revocation.grant_id IS NULL
                ORDER BY grant_row.player_id, grant_row.permission,
                         grant_row.granted_at_epoch_millis, grant_row.grant_id
                """)) {
            query.setString(1, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    grants.add(storedNationFiscalPermissionGrant(result));
                }
            }
            return List.copyOf(grants);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list Nation Fiscal Permission grants", failure);
        }
    }

    public synchronized StoredNationFiscalPermissionGrant grantNationFiscalPermission(
            UUID grantId,
            String serviceIdentity,
            String requestId,
            UUID nationId,
            UUID actorPlayerId,
            UUID playerId,
            String permission,
            String reason,
            long grantedAtEpochMillis) {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO nation_fiscal_permission_grant (
                    grant_id, service_identity, request_id, nation_id, actor_player_id,
                    player_id, permission, reason, granted_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, grantId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, nationId.toString());
            insert.setString(5, actorPlayerId.toString());
            insert.setString(6, playerId.toString());
            insert.setString(7, permission);
            insert.setString(8, reason);
            insert.setLong(9, grantedAtEpochMillis);
            insert.executeUpdate();
            return nationFiscalPermissionGrant(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to grant Nation Fiscal Permission", failure);
        }
    }

    public synchronized StoredNationFiscalPermissionRevocation nationFiscalPermissionRevocation(
            String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_fiscal_permission_revocation
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readNationFiscalPermissionRevocation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation Fiscal Permission revocation", failure);
        }
    }

    public synchronized StoredNationFiscalPermissionRevocation nationFiscalPermissionRevocation(
            UUID grantId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM nation_fiscal_permission_revocation WHERE grant_id = ?
                """)) {
            query.setString(1, grantId.toString());
            return readNationFiscalPermissionRevocation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Nation Fiscal Permission revocation", failure);
        }
    }

    public synchronized StoredNationFiscalPermissionRevocation revokeNationFiscalPermission(
            UUID revocationId,
            UUID grantId,
            String serviceIdentity,
            String requestId,
            UUID nationId,
            UUID actorPlayerId,
            String reason,
            long revokedAtEpochMillis) {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO nation_fiscal_permission_revocation (
                    revocation_id, grant_id, service_identity, request_id, nation_id,
                    actor_player_id, reason, revoked_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, revocationId.toString());
            insert.setString(2, grantId.toString());
            insert.setString(3, serviceIdentity);
            insert.setString(4, requestId);
            insert.setString(5, nationId.toString());
            insert.setString(6, actorPlayerId.toString());
            insert.setString(7, reason);
            insert.setLong(8, revokedAtEpochMillis);
            insert.executeUpdate();
            return nationFiscalPermissionRevocation(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to revoke Nation Fiscal Permission", failure);
        }
    }

    public synchronized StoredOnlineInterval recordOnlineTime(
            UUID intervalId,
            String serviceIdentity,
            String requestId,
            UUID playerId,
            long startedAtEpochMillis,
            long endedAtEpochMillis) {
        StoredOnlineInterval replay = onlineTimeRegistration(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO online_time_interval (
                    interval_id, service_identity, request_id, player_id,
                    started_at_epoch_millis, ended_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, intervalId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, playerId.toString());
            insert.setLong(5, startedAtEpochMillis);
            insert.setLong(6, endedAtEpochMillis);
            insert.executeUpdate();
            return onlineTimeInterval(intervalId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to record online time for player " + playerId, failure);
        }
    }

    public synchronized StoredOnlineInterval onlineTimeRegistration(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM online_time_interval
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readOnlineInterval(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read online-time request", failure);
        }
    }

    public synchronized List<StoredOnlineInterval> onlineTimeHistory(UUID playerId) {
        List<StoredOnlineInterval> history = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM online_time_interval
                WHERE player_id = ?
                ORDER BY started_at_epoch_millis, ended_at_epoch_millis, interval_id
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    history.add(readOnlineInterval(result));
                }
            }
            return List.copyOf(history);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read online-time history for player " + playerId, failure);
        }
    }

    public synchronized StoredOnlineInterval overlappingOnlineTime(
            UUID playerId, long startedAtEpochMillis, long endedAtEpochMillis) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM online_time_interval
                WHERE player_id = ?
                  AND started_at_epoch_millis < ?
                  AND ended_at_epoch_millis > ?
                ORDER BY started_at_epoch_millis
                LIMIT 1
                """)) {
            query.setString(1, playerId.toString());
            query.setLong(2, endedAtEpochMillis);
            query.setLong(3, startedAtEpochMillis);
            return readOnlineInterval(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to check online-time overlap for player " + playerId, failure);
        }
    }

    public synchronized StoredReservation reserve(
            String serviceIdentity,
            String requestId,
            String sourceAccount,
            long amountMinorUnits,
            String purpose) {
        StoredReservation existing = reservation(serviceIdentity, requestId);
        if (existing != null) {
            return existing;
        }
        UUID reservationId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_reservation (
                    reservation_id, service_identity, request_id, source_account, amount_minor_units, purpose, state
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """)) {
            insert.setString(1, reservationId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, sourceAccount);
            insert.setLong(5, amountMinorUnits);
            insert.setString(6, purpose);
            insert.executeUpdate();
            return new StoredReservation(
                    reservationId,
                    serviceIdentity,
                    requestId,
                    sourceAccount,
                    amountMinorUnits,
                    0L,
                    purpose,
                    "ACTIVE");
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to create Reservation", failure);
        }
    }

    public synchronized StoredReservation reservation(String serviceIdentity, String requestId) {
        return findReservation(serviceIdentity, requestId);
    }

    public synchronized StoredBudget createBudget(
            UUID budgetId,
            String serviceIdentity,
            String requestId,
            String sourceAccount,
            long amountMinorUnits,
            String budgetCode,
            String purpose,
            long expiresAtEpochMillis) {
        StoredBudget existing = budget(serviceIdentity, requestId);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_budget (
                    budget_id, service_identity, request_id, source_account,
                    amount_minor_units, budget_code, purpose,
                    expires_at_epoch_millis, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                """)) {
            insert.setString(1, budgetId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, sourceAccount);
            insert.setLong(5, amountMinorUnits);
            insert.setString(6, budgetCode);
            insert.setString(7, purpose);
            insert.setLong(8, expiresAtEpochMillis);
            insert.executeUpdate();
            return budget(budgetId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to create Budget " + budgetCode, failure);
        }
    }

    public synchronized StoredFiscalBill issueFiscalBill(
            UUID billId,
            String serviceIdentity,
            String requestId,
            String payerAccount,
            String beneficiaryAccount,
            long amountMinorUnits,
            String kind,
            String purpose,
            long dueAtEpochMillis) {
        StoredFiscalBill existing = fiscalBill(serviceIdentity, requestId);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_bill (
                    bill_id, service_identity, request_id, payer_account,
                    beneficiary_account, amount_minor_units, kind, purpose,
                    due_at_epoch_millis, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED')
                """)) {
            insert.setString(1, billId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, payerAccount);
            insert.setString(5, beneficiaryAccount);
            insert.setLong(6, amountMinorUnits);
            insert.setString(7, kind);
            insert.setString(8, purpose);
            insert.setLong(9, dueAtEpochMillis);
            insert.executeUpdate();
            return fiscalBill(billId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to issue Fiscal Bill " + billId, failure);
        }
    }

    public synchronized StoredFiscalBill fiscalBill(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT b.*, COALESCE(r.settled_minor_units, 0) AS settled_minor_units
                FROM fiscal_bill b
                LEFT JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                LEFT JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE b.service_identity = ? AND b.request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readFiscalBill(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Fiscal Bill request", failure);
        }
    }

    public synchronized StoredFiscalBill fiscalBill(UUID billId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT b.*, COALESCE(r.settled_minor_units, 0) AS settled_minor_units
                FROM fiscal_bill b
                LEFT JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                LEFT JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE b.bill_id = ?
                """)) {
            query.setString(1, billId.toString());
            return readFiscalBill(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Fiscal Bill " + billId, failure);
        }
    }

    public synchronized StoredFiscalBill fiscalBillFunding(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT b.*, COALESCE(r.settled_minor_units, 0) AS settled_minor_units
                FROM fiscal_bill b
                LEFT JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                LEFT JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE b.funding_service_identity = ? AND b.funding_request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readFiscalBill(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Fiscal Bill funding request", failure);
        }
    }

    public synchronized StoredFiscalBill fundFiscalBill(
            UUID escrowId,
            UUID reservationId,
            String serviceIdentity,
            String requestId,
            UUID billId) {
        StoredFiscalBill replay = fiscalBillFunding(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredFiscalBill bill = fiscalBill(billId);
        if (bill == null) {
            throw new IllegalArgumentException("Unknown Fiscal Bill " + billId);
        }
        if (!"ISSUED".equals(bill.state())) {
            throw new IllegalStateException("Fiscal Bill is not awaiting funding: " + bill.state());
        }

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertReservation = connection.prepareStatement("""
                        INSERT INTO fiscal_reservation (
                            reservation_id, service_identity, request_id, source_account,
                            amount_minor_units, purpose, state
                        ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                        """);
                    PreparedStatement insertEscrow = connection.prepareStatement("""
                        INSERT INTO fiscal_escrow (
                            escrow_id, service_identity, request_id, reservation_id,
                            external_object_id, purpose, expires_at_epoch_millis,
                            required_recipient_account, state
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED')
                        """);
                    PreparedStatement fund = connection.prepareStatement("""
                        UPDATE fiscal_bill
                        SET escrow_id = ?, funding_service_identity = ?,
                            funding_request_id = ?, state = 'RESERVED'
                        WHERE bill_id = ? AND state = 'ISSUED' AND escrow_id IS NULL
                        """)) {
                insertReservation.setString(1, reservationId.toString());
                insertReservation.setString(2, serviceIdentity);
                insertReservation.setString(3, requestId);
                insertReservation.setString(4, bill.payerAccount());
                insertReservation.setLong(5, bill.amountMinorUnits());
                insertReservation.setString(6, bill.purpose());
                insertReservation.executeUpdate();

                insertEscrow.setString(1, escrowId.toString());
                insertEscrow.setString(2, serviceIdentity);
                insertEscrow.setString(3, requestId);
                insertEscrow.setString(4, reservationId.toString());
                insertEscrow.setString(5, "bill:" + billId);
                insertEscrow.setString(6, bill.purpose());
                insertEscrow.setLong(7, bill.dueAtEpochMillis());
                insertEscrow.setString(8, bill.beneficiaryAccount());
                insertEscrow.executeUpdate();

                fund.setString(1, escrowId.toString());
                fund.setString(2, serviceIdentity);
                fund.setString(3, requestId);
                fund.setString(4, billId.toString());
                if (fund.executeUpdate() != 1) {
                    throw new IllegalStateException("Fiscal Bill state changed during funding " + billId);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return fiscalBill(billId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to fund Fiscal Bill " + billId, failure);
        }
    }

    public synchronized StoredBudget budget(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT b.*, COALESCE(r.settled_minor_units, 0) AS settled_minor_units
                FROM fiscal_budget b
                LEFT JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                LEFT JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE b.service_identity = ? AND b.request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readBudget(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Budget request", failure);
        }
    }

    public synchronized StoredBudget budget(UUID budgetId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT b.*, COALESCE(r.settled_minor_units, 0) AS settled_minor_units
                FROM fiscal_budget b
                LEFT JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                LEFT JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE b.budget_id = ?
                """)) {
            query.setString(1, budgetId.toString());
            return readBudget(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Budget " + budgetId, failure);
        }
    }

    public synchronized StoredBudget budgetApproval(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT b.*, COALESCE(r.settled_minor_units, 0) AS settled_minor_units
                FROM fiscal_budget b
                LEFT JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                LEFT JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE b.approval_service_identity = ? AND b.approval_request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readBudget(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Budget approval request", failure);
        }
    }

    public synchronized StoredBudget approveBudget(
            UUID escrowId,
            UUID reservationId,
            String serviceIdentity,
            String requestId,
            UUID budgetId) {
        StoredBudget replay = budgetApproval(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredBudget budget = budget(budgetId);
        if (budget == null) {
            throw new IllegalArgumentException("Unknown Budget " + budgetId);
        }
        if (!"DRAFT".equals(budget.state())) {
            throw new IllegalStateException("Budget is not a draft: " + budget.state());
        }

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertReservation = connection.prepareStatement("""
                        INSERT INTO fiscal_reservation (
                            reservation_id, service_identity, request_id, source_account,
                            amount_minor_units, purpose, state
                        ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                        """);
                    PreparedStatement insertEscrow = connection.prepareStatement("""
                        INSERT INTO fiscal_escrow (
                            escrow_id, service_identity, request_id, reservation_id,
                            external_object_id, purpose, expires_at_epoch_millis, state
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED')
                        """);
                    PreparedStatement approve = connection.prepareStatement("""
                        UPDATE fiscal_budget
                        SET escrow_id = ?, approval_service_identity = ?,
                            approval_request_id = ?, state = 'APPROVED'
                        WHERE budget_id = ? AND state = 'DRAFT' AND escrow_id IS NULL
                        """)) {
                insertReservation.setString(1, reservationId.toString());
                insertReservation.setString(2, serviceIdentity);
                insertReservation.setString(3, requestId);
                insertReservation.setString(4, budget.sourceAccount());
                insertReservation.setLong(5, budget.amountMinorUnits());
                insertReservation.setString(6, budget.purpose());
                insertReservation.executeUpdate();

                insertEscrow.setString(1, escrowId.toString());
                insertEscrow.setString(2, serviceIdentity);
                insertEscrow.setString(3, requestId);
                insertEscrow.setString(4, reservationId.toString());
                insertEscrow.setString(5, "budget:" + budgetId);
                insertEscrow.setString(6, budget.purpose());
                insertEscrow.setLong(7, budget.expiresAtEpochMillis());
                insertEscrow.executeUpdate();

                approve.setString(1, escrowId.toString());
                approve.setString(2, serviceIdentity);
                approve.setString(3, requestId);
                approve.setString(4, budgetId.toString());
                if (approve.executeUpdate() != 1) {
                    throw new IllegalStateException("Budget state changed during approval " + budgetId);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return budget(budgetId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to approve Budget " + budgetId, failure);
        }
    }

    public synchronized StoredEscrow openEscrow(
            UUID escrowId,
            UUID reservationId,
            String serviceIdentity,
            String requestId,
            String sourceAccount,
            long amountMinorUnits,
            String externalObjectId,
            String purpose,
            long expiresAtEpochMillis) {
        StoredEscrow existing = escrow(serviceIdentity, requestId);
        if (existing != null) {
            return existing;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertReservation = connection.prepareStatement("""
                        INSERT INTO fiscal_reservation (
                            reservation_id, service_identity, request_id, source_account,
                            amount_minor_units, purpose, state
                        ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                        """);
                    PreparedStatement insertEscrow = connection.prepareStatement("""
                        INSERT INTO fiscal_escrow (
                            escrow_id, service_identity, request_id, reservation_id,
                            external_object_id, purpose, expires_at_epoch_millis, state
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED')
                        """)) {
                insertReservation.setString(1, reservationId.toString());
                insertReservation.setString(2, serviceIdentity);
                insertReservation.setString(3, requestId);
                insertReservation.setString(4, sourceAccount);
                insertReservation.setLong(5, amountMinorUnits);
                insertReservation.setString(6, purpose);
                insertReservation.executeUpdate();

                insertEscrow.setString(1, escrowId.toString());
                insertEscrow.setString(2, serviceIdentity);
                insertEscrow.setString(3, requestId);
                insertEscrow.setString(4, reservationId.toString());
                insertEscrow.setString(5, externalObjectId);
                insertEscrow.setString(6, purpose);
                insertEscrow.setLong(7, expiresAtEpochMillis);
                insertEscrow.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return escrow(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to open Escrow " + externalObjectId, failure);
        }
    }

    public synchronized StoredEscrow escrow(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT e.*, r.source_account, r.amount_minor_units, r.settled_minor_units
                FROM fiscal_escrow e
                JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE e.service_identity = ? AND e.request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readEscrow(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Escrow request", failure);
        }
    }

    public synchronized StoredEscrow escrow(UUID escrowId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT e.*, r.source_account, r.amount_minor_units, r.settled_minor_units
                FROM fiscal_escrow e
                JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                WHERE e.escrow_id = ?
                """)) {
            query.setString(1, escrowId.toString());
            return readEscrow(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Escrow " + escrowId, failure);
        }
    }

    public synchronized StoredEscrowExpiry escrowExpiry(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM escrow_expiry
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readEscrowExpiry(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Escrow expiry request", failure);
        }
    }

    public synchronized StoredEscrow expireEscrow(
            UUID expiryId,
            String serviceIdentity,
            String requestId,
            UUID escrowId,
            long expiredAtEpochMillis) {
        StoredEscrowExpiry replay = escrowExpiry(serviceIdentity, requestId);
        if (replay != null) {
            return escrow(replay.escrowId());
        }
        StoredEscrow escrow = escrow(escrowId);
        if (escrow == null) {
            throw new IllegalArgumentException("Unknown Escrow " + escrowId);
        }
        if (expiredAtEpochMillis < escrow.expiresAtEpochMillis()) {
            throw new IllegalStateException("Escrow has not reached its expiry " + escrowId);
        }
        if (!"RESERVED".equals(escrow.state()) && !"PARTIALLY_SETTLED".equals(escrow.state())) {
            throw new IllegalStateException("Escrow is not active: " + escrow.state());
        }

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertExpiry = connection.prepareStatement("""
                        INSERT INTO escrow_expiry (
                            expiry_id, service_identity, request_id, escrow_id,
                            expired_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?)
                        """);
                    PreparedStatement releaseReservation = connection.prepareStatement("""
                        UPDATE fiscal_reservation SET state = 'RELEASED'
                        WHERE reservation_id = ? AND state = 'ACTIVE'
                        """);
                    PreparedStatement expire = connection.prepareStatement("""
                        UPDATE fiscal_escrow SET state = 'EXPIRED'
                        WHERE escrow_id = ? AND state IN ('RESERVED', 'PARTIALLY_SETTLED')
                        """);
                    PreparedStatement expireBudget = connection.prepareStatement("""
                        UPDATE fiscal_budget SET state = 'EXPIRED'
                        WHERE escrow_id = ? AND state IN ('APPROVED', 'PARTIALLY_SPENT')
                        """);
                    PreparedStatement expireBill = connection.prepareStatement("""
                        UPDATE fiscal_bill SET state = 'EXPIRED'
                        WHERE escrow_id = ? AND state IN ('RESERVED', 'PARTIALLY_PAID')
                        """)) {
                if (hasIncompletePayment(escrow.reservationId())) {
                    throw new PendingReservationPaymentException(escrow.reservationId());
                }
                insertExpiry.setString(1, expiryId.toString());
                insertExpiry.setString(2, serviceIdentity);
                insertExpiry.setString(3, requestId);
                insertExpiry.setString(4, escrowId.toString());
                insertExpiry.setLong(5, expiredAtEpochMillis);
                insertExpiry.executeUpdate();

                releaseReservation.setString(1, escrow.reservationId().toString());
                if (releaseReservation.executeUpdate() != 1) {
                    throw new InactiveReservationException(escrow.reservationId(), "CHANGED_CONCURRENTLY");
                }

                expire.setString(1, escrowId.toString());
                if (expire.executeUpdate() != 1) {
                    throw new IllegalStateException("Escrow state changed during expiry " + escrowId);
                }
                expireBudget.setString(1, escrowId.toString());
                expireBudget.executeUpdate();
                expireBill.setString(1, escrowId.toString());
                expireBill.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return escrow(escrowId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to expire Escrow " + escrowId, failure);
        }
    }

    public synchronized StoredReservationRelease releaseReservation(
            UUID releaseId,
            String serviceIdentity,
            String requestId,
            UUID reservationId,
            String reason,
            long releasedAtEpochMillis) {
        StoredReservationRelease replay = reservationRelease(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO reservation_release (
                            release_id, service_identity, request_id, reservation_id,
                            reason, released_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """);
                    PreparedStatement release = connection.prepareStatement("""
                        UPDATE fiscal_reservation SET state = 'RELEASED'
                        WHERE reservation_id = ? AND state = 'ACTIVE'
                        """);
                    PreparedStatement releaseEscrow = connection.prepareStatement("""
                        UPDATE fiscal_escrow SET state = 'RELEASED'
                        WHERE reservation_id = ?
                          AND state IN ('RESERVED', 'PARTIALLY_SETTLED')
                        """);
                    PreparedStatement releaseBudget = connection.prepareStatement("""
                        UPDATE fiscal_budget SET state = 'RELEASED'
                        WHERE escrow_id = (
                            SELECT escrow_id FROM fiscal_escrow WHERE reservation_id = ?
                        )
                          AND state IN ('APPROVED', 'PARTIALLY_SPENT')
                        """);
                    PreparedStatement cancelBill = connection.prepareStatement("""
                        UPDATE fiscal_bill SET state = 'CANCELLED'
                        WHERE escrow_id = (
                            SELECT escrow_id FROM fiscal_escrow WHERE reservation_id = ?
                        )
                          AND state IN ('RESERVED', 'PARTIALLY_PAID')
                        """)) {
                String state = reservationState(reservationId);
                if (!"ACTIVE".equals(state)) {
                    throw new InactiveReservationException(
                            reservationId, state == null ? "UNKNOWN" : state);
                }
                if (hasIncompletePayment(reservationId)) {
                    throw new PendingReservationPaymentException(reservationId);
                }
                insert.setString(1, releaseId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, reservationId.toString());
                insert.setString(5, reason);
                insert.setLong(6, releasedAtEpochMillis);
                insert.executeUpdate();
                release.setString(1, reservationId.toString());
                if (release.executeUpdate() != 1) {
                    throw new InactiveReservationException(reservationId, "CHANGED_CONCURRENTLY");
                }
                releaseEscrow.setString(1, reservationId.toString());
                releaseEscrow.executeUpdate();
                releaseBudget.setString(1, reservationId.toString());
                releaseBudget.executeUpdate();
                cancelBill.setString(1, reservationId.toString());
                cancelBill.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return reservationRelease(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to release Reservation " + reservationId, failure);
        }
    }

    public synchronized StoredReservationRelease reservationRelease(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM reservation_release
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readReservationRelease(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Reservation release request", failure);
        }
    }

    public synchronized long activeReservedMinorUnits(String sourceAccount) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount_minor_units - settled_minor_units), 0)
                FROM fiscal_reservation
                WHERE source_account = ? AND state = 'ACTIVE'
                """)) {
            query.setString(1, sourceAccount);
            try (ResultSet result = query.executeQuery()) {
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to total active Reservations", failure);
        }
    }

    public synchronized StoredPaymentTransaction preparePayment(
            String serviceIdentity,
            String requestId,
            UUID reservationId,
            String recipientAccount,
            long amountMinorUnits) {
        StoredPaymentTransaction existing = paymentTransaction(serviceIdentity, requestId);
        if (existing != null) {
            return existing;
        }
        StoredReservation reservation = reservation(reservationId);
        if (reservation == null) {
            throw new IllegalArgumentException("Unknown Reservation " + reservationId);
        }
        String requiredRecipient = requiredRecipient(reservationId);
        if (requiredRecipient != null && !requiredRecipient.equals(recipientAccount)) {
            throw new RequiredRecipientMismatchException(
                    reservationId, requiredRecipient, recipientAccount);
        }
        long remainingMinorUnits = Math.subtractExact(
                reservation.amountMinorUnits(), reservation.settledMinorUnits());
        if (amountMinorUnits > remainingMinorUnits) {
            throw new ReservationRemainderExceededException(
                    reservationId, amountMinorUnits, remainingMinorUnits);
        }
        try {
            if (hasIncompletePayment(reservationId)) {
                throw new PendingReservationPaymentException(reservationId);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect pending Reservation payments", failure);
        }
        UUID transactionId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO payment_transaction (
                    transaction_id, service_identity, request_id, reservation_id,
                    source_account, recipient_account, amount_minor_units, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED')
                """)) {
            insert.setString(1, transactionId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, reservationId.toString());
            insert.setString(5, reservation.sourceAccount());
            insert.setString(6, recipientAccount);
            insert.setLong(7, amountMinorUnits);
            insert.executeUpdate();
            return paymentTransaction(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to prepare payment", failure);
        }
    }

    public synchronized StoredPaymentTransaction prepareRefund(
            String serviceIdentity,
            String requestId,
            UUID originalTransactionId,
            long amountMinorUnits,
            String reason) {
        StoredPaymentTransaction existing = paymentTransaction(serviceIdentity, requestId);
        if (existing != null) {
            return existing;
        }
        StoredPaymentTransaction original = paymentTransaction(originalTransactionId);
        if (original == null || !"PAYMENT".equals(original.kind())
                || !"CIVIC_COMMITTED".equals(original.state())) {
            throw new IllegalArgumentException(
                    "Refund parent must be a committed payment " + originalTransactionId);
        }
        if (territoryClaimPermitByPrepaymentTransaction(originalTransactionId) != null) {
            throw new IllegalStateException(
                    "Territory Claim Permit prepayment cannot use the normal refund path "
                            + originalTransactionId);
        }
        long refundableMinorUnits = Math.subtractExact(
                original.amountMinorUnits(), original.refundedMinorUnits());
        if (amountMinorUnits > refundableMinorUnits) {
            throw new ReservationRemainderExceededException(
                    originalTransactionId, amountMinorUnits, refundableMinorUnits);
        }
        try {
            if (hasIncompleteRefund(originalTransactionId)) {
                throw new IllegalStateException(
                        "Payment already has an incomplete refund " + originalTransactionId);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect pending refunds", failure);
        }
        UUID transactionId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO payment_transaction (
                    transaction_id, service_identity, request_id, reservation_id,
                    source_account, recipient_account, amount_minor_units, kind,
                    parent_transaction_id, reason, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'REFUND', ?, ?, 'PREPARED')
                """)) {
            insert.setString(1, transactionId.toString());
            insert.setString(2, serviceIdentity);
            insert.setString(3, requestId);
            insert.setString(4, original.reservationId().toString());
            insert.setString(5, original.recipientAccount());
            insert.setString(6, original.sourceAccount());
            insert.setLong(7, amountMinorUnits);
            insert.setString(8, originalTransactionId.toString());
            insert.setString(9, reason);
            insert.executeUpdate();
            return paymentTransaction(serviceIdentity, requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to prepare refund", failure);
        }
    }

    public synchronized StoredPaymentTransaction prepareTerritoryClaimPermitRefund(
            String serviceIdentity,
            String requestId,
            UUID permitId,
            String reason) {
        StoredPaymentTransaction replay = paymentTransaction(serviceIdentity, requestId);
        if (replay != null) {
            return replay;
        }
        StoredTerritoryClaimPermitCompensation compensation =
                territoryClaimPermitCompensation(permitId);
        if (compensation == null
                || !compensation.serviceIdentity().equals(serviceIdentity)
                || !compensation.refundRequestId().equals(requestId)
                || !compensation.reason().equals(reason)
                || compensation.completedAtEpochMillis() != null) {
            throw new SecurityException(
                    "Territory Claim Permit refund requires its exact pending compensation");
        }
        if (compensation.refundTransactionId() != null) {
            StoredPaymentTransaction linked = paymentTransaction(compensation.refundTransactionId());
            if (linked == null) {
                throw new IllegalStateException(
                        "Territory Claim Permit compensation references a missing refund");
            }
            return linked;
        }
        StoredTerritoryClaimPermit permit = territoryClaimPermit(permitId);
        if (permit == null || !"READY".equals(permit.state())) {
            throw new IllegalStateException(
                    "Territory Claim Permit is not awaiting compensation");
        }
        StoredPaymentTransaction original = paymentTransaction(permit.prepaymentTransactionId());
        if (original == null
                || !"PAYMENT".equals(original.kind())
                || !"CIVIC_COMMITTED".equals(original.state())
                || original.amountMinorUnits() != permit.prepaymentMinorUnits()
                || original.refundedMinorUnits() != 0L) {
            throw new IllegalStateException(
                    "Territory Claim Permit prepayment is not exactly refundable");
        }
        try {
            if (hasIncompleteRefund(original.transactionId())) {
                throw new IllegalStateException(
                        "Territory Claim Permit prepayment already has an incomplete refund");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Territory Claim Permit refunds", failure);
        }
        UUID refundTransactionId = UUID.randomUUID();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO payment_transaction (
                            transaction_id, service_identity, request_id, reservation_id,
                            source_account, recipient_account, amount_minor_units, kind,
                            parent_transaction_id, reason, state
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'REFUND', ?, ?, 'PREPARED')
                        """);
                    PreparedStatement link = connection.prepareStatement("""
                        UPDATE territory_claim_permit_compensation
                        SET refund_transaction_id = ?
                        WHERE compensation_id = ? AND refund_transaction_id IS NULL
                        """)) {
                insert.setString(1, refundTransactionId.toString());
                insert.setString(2, serviceIdentity);
                insert.setString(3, requestId);
                insert.setString(4, original.reservationId().toString());
                insert.setString(5, original.recipientAccount());
                insert.setString(6, original.sourceAccount());
                insert.setLong(7, original.amountMinorUnits());
                insert.setString(8, original.transactionId().toString());
                insert.setString(9, reason);
                insert.executeUpdate();
                link.setString(1, refundTransactionId.toString());
                link.setString(2, compensation.compensationId().toString());
                if (link.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Territory Claim Permit compensation refund link changed");
                }
                connection.commit();
                return paymentTransaction(refundTransactionId);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to prepare Territory Claim Permit refund", failure);
        }
    }

    public synchronized void markExternalApplied(UUID transactionId) {
        updateTransactionState(transactionId, "PREPARED", "EXTERNAL_APPLIED");
    }

    public synchronized void markExternalAppliedDuringRecovery(
            UUID transactionId, long recordedAtEpochMillis) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE payment_transaction SET state = 'EXTERNAL_APPLIED'
                    WHERE transaction_id = ? AND state = 'PREPARED'
                    """)) {
                update.setString(1, transactionId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Payment transaction state changed during recovery " + transactionId);
                }
                insertRecoveryAudit(
                        transactionId,
                        "RECOVERY_EXTERNAL_APPLIED",
                        "civiceconomy-recovery",
                        "Recovery confirmed the external payment",
                        recordedAtEpochMillis);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to record recovered external payment " + transactionId, failure);
        }
    }

    public synchronized void commitPayment(UUID transactionId, UUID reservationId) {
        commitPayment(transactionId, reservationId, false, 0L);
    }

    public synchronized void commitPaymentDuringRecovery(
            UUID transactionId, UUID reservationId, long recordedAtEpochMillis) {
        commitPayment(transactionId, reservationId, true, recordedAtEpochMillis);
    }

    private void commitPayment(
            UUID transactionId,
            UUID reservationId,
            boolean recovery,
            long recordedAtEpochMillis) {
        StoredPaymentTransaction transaction = paymentTransaction(transactionId);
        if (transaction == null) {
            throw new IllegalArgumentException("Unknown payment transaction " + transactionId);
        }
        if ("CIVIC_COMMITTED".equals(transaction.state())) {
            return;
        }
        if (!"EXTERNAL_APPLIED".equals(transaction.state())) {
            throw new IllegalStateException(
                    "Payment transaction is not ready for Civic commit: " + transaction.state());
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement settle = connection.prepareStatement("""
                        UPDATE fiscal_reservation
                        SET settled_minor_units = settled_minor_units + ?,
                            state = CASE
                                WHEN settled_minor_units + ? = amount_minor_units THEN 'SETTLED'
                                ELSE 'ACTIVE'
                            END
                        WHERE reservation_id = ?
                          AND state = 'ACTIVE'
                          AND amount_minor_units - settled_minor_units >= ?
                        """);
                    PreparedStatement commit = connection.prepareStatement("""
                        UPDATE payment_transaction SET state = 'CIVIC_COMMITTED'
                        WHERE transaction_id = ? AND state = 'EXTERNAL_APPLIED'
                        """);
                    PreparedStatement updateEscrow = connection.prepareStatement("""
                        UPDATE fiscal_escrow
                        SET state = CASE
                            WHEN (SELECT settled_minor_units FROM fiscal_reservation
                                  WHERE reservation_id = fiscal_escrow.reservation_id)
                               = (SELECT amount_minor_units FROM fiscal_reservation
                                  WHERE reservation_id = fiscal_escrow.reservation_id)
                            THEN 'SETTLED'
                            ELSE 'PARTIALLY_SETTLED'
                        END
                        WHERE reservation_id = ?
                          AND state IN ('RESERVED', 'PARTIALLY_SETTLED')
                        """);
                    PreparedStatement updateBudget = connection.prepareStatement("""
                        UPDATE fiscal_budget
                        SET state = CASE
                            WHEN (SELECT state FROM fiscal_escrow
                                  WHERE escrow_id = fiscal_budget.escrow_id) = 'SETTLED'
                            THEN 'SPENT'
                            ELSE 'PARTIALLY_SPENT'
                        END
                        WHERE escrow_id = (
                            SELECT escrow_id FROM fiscal_escrow WHERE reservation_id = ?
                        )
                          AND state IN ('APPROVED', 'PARTIALLY_SPENT')
                        """);
                    PreparedStatement updateBill = connection.prepareStatement("""
                        UPDATE fiscal_bill
                        SET state = CASE
                            WHEN (SELECT state FROM fiscal_escrow
                                  WHERE escrow_id = fiscal_bill.escrow_id) = 'SETTLED'
                            THEN 'PAID'
                            ELSE 'PARTIALLY_PAID'
                        END
                        WHERE escrow_id = (
                            SELECT escrow_id FROM fiscal_escrow WHERE reservation_id = ?
                        )
                          AND state IN ('RESERVED', 'PARTIALLY_PAID')
                        """)) {
                settle.setLong(1, transaction.amountMinorUnits());
                settle.setLong(2, transaction.amountMinorUnits());
                settle.setString(3, reservationId.toString());
                settle.setLong(4, transaction.amountMinorUnits());
                if (settle.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Reservation remainder changed before payment commit " + reservationId);
                }
                commit.setString(1, transactionId.toString());
                if (commit.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Payment transaction state changed before Civic commit " + transactionId);
                }
                updateEscrow.setString(1, reservationId.toString());
                updateEscrow.executeUpdate();
                updateBudget.setString(1, reservationId.toString());
                updateBudget.executeUpdate();
                updateBill.setString(1, reservationId.toString());
                updateBill.executeUpdate();
                insertLedgerEntries(transaction, System.currentTimeMillis());
                if (recovery) {
                    insertRecoveryAudit(
                            transactionId,
                            "RECOVERY_CIVIC_COMMITTED",
                            "civiceconomy-recovery",
                            "Recovery completed the Civic payment commit",
                            recordedAtEpochMillis);
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to commit payment", failure);
        }
    }

    public synchronized void commitRefund(UUID transactionId, UUID originalTransactionId) {
        commitRefund(transactionId, originalTransactionId, false, 0L);
    }

    public synchronized void commitRefundDuringRecovery(
            UUID transactionId, UUID originalTransactionId, long recordedAtEpochMillis) {
        commitRefund(transactionId, originalTransactionId, true, recordedAtEpochMillis);
    }

    private void commitRefund(
            UUID transactionId,
            UUID originalTransactionId,
            boolean recovery,
            long recordedAtEpochMillis) {
        StoredPaymentTransaction refund = paymentTransaction(transactionId);
        if (refund == null) {
            throw new IllegalArgumentException("Unknown refund transaction " + transactionId);
        }
        if ("CIVIC_COMMITTED".equals(refund.state())) {
            return;
        }
        if (!"REFUND".equals(refund.kind()) || !"EXTERNAL_APPLIED".equals(refund.state())
                || !originalTransactionId.equals(refund.parentTransactionId())) {
            throw new IllegalStateException("Refund transaction is not ready for Civic commit " + transactionId);
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement updateOriginal = connection.prepareStatement("""
                        UPDATE payment_transaction
                        SET refunded_minor_units = refunded_minor_units + ?
                        WHERE transaction_id = ?
                          AND kind = 'PAYMENT'
                          AND state = 'CIVIC_COMMITTED'
                          AND amount_minor_units - refunded_minor_units >= ?
                        """);
                    PreparedStatement commitRefund = connection.prepareStatement("""
                        UPDATE payment_transaction SET state = 'CIVIC_COMMITTED'
                        WHERE transaction_id = ? AND kind = 'REFUND' AND state = 'EXTERNAL_APPLIED'
                        """)) {
                updateOriginal.setLong(1, refund.amountMinorUnits());
                updateOriginal.setString(2, originalTransactionId.toString());
                updateOriginal.setLong(3, refund.amountMinorUnits());
                if (updateOriginal.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Refund exceeds remaining refundable payment " + originalTransactionId);
                }
                commitRefund.setString(1, transactionId.toString());
                if (commitRefund.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Refund transaction state changed before Civic commit " + transactionId);
                }
                if (recovery) {
                    insertRecoveryAudit(
                            transactionId,
                            "RECOVERY_CIVIC_COMMITTED",
                            "civiceconomy-recovery",
                            "Recovery completed the Civic refund commit",
                            recordedAtEpochMillis);
                }
                insertLedgerEntries(refund, System.currentTimeMillis());
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to commit refund " + transactionId, failure);
        }
    }

    public synchronized StoredPaymentCompensation prepareCompensation(
            String serviceIdentity,
            String requestId,
            UUID transactionId,
            String reason,
            long recordedAtEpochMillis) {
        StoredPaymentCompensation existing = paymentCompensation(serviceIdentity, requestId);
        if (existing != null) {
            return existing;
        }
        existing = paymentCompensation(transactionId);
        if (existing != null) {
            return existing;
        }
        StoredPaymentTransaction transaction = paymentTransaction(transactionId);
        if (transaction == null) {
            throw new IllegalArgumentException("Unknown payment transaction " + transactionId);
        }
        if (!"EXTERNAL_APPLIED".equals(transaction.state())) {
            throw new IllegalStateException(
                    "Only an externally applied uncommitted transaction can be compensated: "
                            + transaction.state());
        }

        UUID compensationId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertCompensation = connection.prepareStatement("""
                        INSERT INTO payment_compensation (
                            compensation_id, service_identity, request_id,
                            transaction_id, reason, state
                        ) VALUES (?, ?, ?, ?, ?, 'STARTED')
                        """);
                    PreparedStatement markCompensating = connection.prepareStatement("""
                        UPDATE payment_transaction SET state = 'COMPENSATING'
                        WHERE transaction_id = ? AND state = 'EXTERNAL_APPLIED'
                        """);
                    PreparedStatement insertAudit = connection.prepareStatement("""
                        INSERT INTO payment_recovery_audit (
                            audit_id, transaction_id, action, service_identity,
                            detail, recorded_at_epoch_millis
                        ) VALUES (?, ?, 'COMPENSATION_STARTED', ?, ?, ?)
                        """)) {
                insertCompensation.setString(1, compensationId.toString());
                insertCompensation.setString(2, serviceIdentity);
                insertCompensation.setString(3, requestId);
                insertCompensation.setString(4, transactionId.toString());
                insertCompensation.setString(5, reason);
                insertCompensation.executeUpdate();

                markCompensating.setString(1, transactionId.toString());
                if (markCompensating.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Payment transaction state changed before compensation " + transactionId);
                }

                insertAudit.setString(1, auditId.toString());
                insertAudit.setString(2, transactionId.toString());
                insertAudit.setString(3, serviceIdentity);
                insertAudit.setString(4, reason);
                insertAudit.setLong(5, recordedAtEpochMillis);
                insertAudit.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            return paymentCompensation(transactionId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to prepare payment compensation " + transactionId, failure);
        }
    }

    public synchronized void completeCompensation(UUID transactionId, long recordedAtEpochMillis) {
        StoredPaymentCompensation compensation = paymentCompensation(transactionId);
        if (compensation == null) {
            throw new IllegalArgumentException("Unknown payment compensation " + transactionId);
        }
        if ("COMPLETED".equals(compensation.state())) {
            StoredPaymentTransaction completed = paymentTransaction(transactionId);
            if (completed != null && "COMPENSATED".equals(completed.state())) {
                return;
            }
            throw new IllegalStateException(
                    "Completed compensation has inconsistent payment state " + transactionId);
        }
        StoredPaymentTransaction transaction = paymentTransaction(transactionId);
        if (transaction == null || !"COMPENSATING".equals(transaction.state())) {
            throw new IllegalStateException(
                    "Payment transaction is not ready to complete compensation " + transactionId);
        }

        UUID auditId = UUID.randomUUID();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement completeRequest = connection.prepareStatement("""
                        UPDATE payment_compensation SET state = 'COMPLETED'
                        WHERE transaction_id = ? AND state = 'STARTED'
                        """);
                    PreparedStatement markCompensated = connection.prepareStatement("""
                        UPDATE payment_transaction SET state = 'COMPENSATED'
                        WHERE transaction_id = ? AND state = 'COMPENSATING'
                        """);
                    PreparedStatement insertAudit = connection.prepareStatement("""
                        INSERT INTO payment_recovery_audit (
                            audit_id, transaction_id, action, service_identity,
                            detail, recorded_at_epoch_millis
                        ) VALUES (?, ?, 'COMPENSATION_COMPLETED', ?, ?, ?)
                        """)) {
                completeRequest.setString(1, transactionId.toString());
                if (completeRequest.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Compensation request state changed before completion " + transactionId);
                }

                markCompensated.setString(1, transactionId.toString());
                if (markCompensated.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Payment transaction state changed before compensation completion " + transactionId);
                }

                insertAudit.setString(1, auditId.toString());
                insertAudit.setString(2, transactionId.toString());
                insertAudit.setString(3, compensation.serviceIdentity());
                insertAudit.setString(4, compensation.reason());
                insertAudit.setLong(5, recordedAtEpochMillis);
                insertAudit.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to complete payment compensation " + transactionId, failure);
        }
    }

    public synchronized List<StoredRecoveryAuditEntry> recoveryAudit(UUID transactionId) {
        List<StoredRecoveryAuditEntry> entries = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM payment_recovery_audit
                WHERE transaction_id = ?
                ORDER BY recorded_at_epoch_millis, rowid
                """)) {
            query.setString(1, transactionId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    entries.add(new StoredRecoveryAuditEntry(
                            UUID.fromString(result.getString("audit_id")),
                            UUID.fromString(result.getString("transaction_id")),
                            result.getString("action"),
                            result.getString("service_identity"),
                            result.getString("detail"),
                            Instant.ofEpochMilli(result.getLong("recorded_at_epoch_millis"))));
                }
            }
            return List.copyOf(entries);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read payment recovery audit " + transactionId, failure);
        }
    }

    public synchronized List<StoredLedgerEntry> ledgerEntries(String accountId) {
        List<StoredLedgerEntry> entries = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM fiscal_ledger_entry
                WHERE account_id = ?
                ORDER BY recorded_at_epoch_millis, rowid
                """)) {
            query.setString(1, accountId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    entries.add(new StoredLedgerEntry(
                            UUID.fromString(result.getString("entry_id")),
                            UUID.fromString(result.getString("transaction_id")),
                            result.getString("account_id"),
                            result.getString("counterparty_account_id"),
                            result.getLong("amount_minor_units"),
                            result.getString("direction"),
                            result.getString("transaction_kind"),
                            result.getLong("recorded_at_epoch_millis")));
                }
            }
            return List.copyOf(entries);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read fiscal ledger for " + accountId, failure);
        }
    }

    public synchronized StoredPaymentTransaction paymentTransaction(String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM payment_transaction WHERE request_id = ?
                """)) {
            query.setString(1, requestId);
            return readPayment(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read payment", failure);
        }
    }

    public synchronized List<StoredPaymentTransaction> incompletePayments() {
        List<StoredPaymentTransaction> transactions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                    SELECT * FROM payment_transaction
                    WHERE state IN ('PREPARED', 'EXTERNAL_APPLIED', 'COMPENSATING')
                    ORDER BY rowid
                    """)) {
            while (result.next()) {
                transactions.add(readPayment(result));
            }
            return List.copyOf(transactions);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read incomplete payments", failure);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to close Civic database", failure);
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private void requireSupportedVersion() {
        int actualVersion = schemaVersion();
        if (actualVersion < 0 || actualVersion > SCHEMA_VERSION) {
            throw new UnsupportedDatabaseVersionException(actualVersion, SCHEMA_VERSION);
        }
    }

    private void initialize(DatabaseIdentity identity) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            int version = queryInteger("PRAGMA user_version");
            if (version < 1) {
                statement.execute("""
                        CREATE TABLE civic_identity (
                            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                            world_id TEXT NOT NULL,
                            civic_version TEXT NOT NULL,
                            lc_version TEXT NOT NULL,
                            ftb_teams_version TEXT NOT NULL,
                            ftb_chunks_version TEXT NOT NULL
                        )
                        """);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO civic_identity (
                            singleton, world_id, civic_version, lc_version, ftb_teams_version, ftb_chunks_version
                        ) VALUES (1, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, identity.worldId().toString());
                    insert.setString(2, identity.civicVersion());
                    insert.setString(3, identity.lightmansCurrencyVersion());
                    insert.setString(4, identity.ftbTeamsVersion());
                    insert.setString(5, identity.ftbChunksVersion());
                    insert.executeUpdate();
                }
                statement.execute("PRAGMA user_version = 1");
            }
            if (version < 2) {
                statement.execute("""
                        CREATE TABLE fiscal_reservation (
                            reservation_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            source_account TEXT NOT NULL,
                            amount_minor_units INTEGER NOT NULL CHECK (amount_minor_units > 0),
                            purpose TEXT NOT NULL,
                            state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'RELEASED', 'SETTLED')),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX fiscal_reservation_active_account
                        ON fiscal_reservation (source_account)
                        WHERE state = 'ACTIVE'
                        """);
                statement.execute("PRAGMA user_version = 2");
            }
            if (version < 3) {
                statement.execute("""
                        CREATE TABLE payment_transaction (
                            transaction_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            reservation_id TEXT NOT NULL REFERENCES fiscal_reservation(reservation_id),
                            source_account TEXT NOT NULL,
                            recipient_account TEXT NOT NULL,
                            amount_minor_units INTEGER NOT NULL CHECK (amount_minor_units > 0),
                            state TEXT NOT NULL CHECK (state IN (
                                'PREPARED', 'EXTERNAL_APPLIED', 'CIVIC_COMMITTED',
                                'COMPENSATING', 'COMPENSATED'
                            )),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 3");
            }
            if (version < 4) {
                statement.execute("""
                        CREATE TABLE nation_registry (
                            nation_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            ftb_team_id TEXT NOT NULL UNIQUE,
                            registered_at_epoch_millis INTEGER NOT NULL CHECK (registered_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 4");
            }
            if (version < 5) {
                statement.execute("""
                        CREATE TABLE citizenship_period (
                            citizenship_id TEXT PRIMARY KEY,
                            player_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            joined_at_epoch_millis INTEGER NOT NULL CHECK (joined_at_epoch_millis >= 0),
                            ended_at_epoch_millis INTEGER CHECK (
                                ended_at_epoch_millis IS NULL OR ended_at_epoch_millis >= joined_at_epoch_millis
                            ),
                            join_service_identity TEXT NOT NULL,
                            join_request_id TEXT NOT NULL,
                            leave_service_identity TEXT,
                            leave_request_id TEXT,
                            UNIQUE (join_service_identity, join_request_id),
                            UNIQUE (leave_service_identity, leave_request_id)
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX citizenship_one_active_nation_per_player
                        ON citizenship_period (player_id)
                        WHERE ended_at_epoch_millis IS NULL
                        """);
                statement.execute("PRAGMA user_version = 5");
            }
            if (version < 6) {
                statement.execute("""
                        CREATE TABLE online_time_interval (
                            interval_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            player_id TEXT NOT NULL,
                            started_at_epoch_millis INTEGER NOT NULL CHECK (started_at_epoch_millis >= 0),
                            ended_at_epoch_millis INTEGER NOT NULL CHECK (
                                ended_at_epoch_millis > started_at_epoch_millis
                            ),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX online_time_interval_player_time
                        ON online_time_interval (player_id, started_at_epoch_millis, ended_at_epoch_millis)
                        """);
                statement.execute("PRAGMA user_version = 6");
            }
            if (version < 7) {
                statement.execute("""
                        CREATE TABLE reservation_release (
                            release_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            reservation_id TEXT NOT NULL UNIQUE REFERENCES fiscal_reservation(reservation_id),
                            reason TEXT NOT NULL,
                            released_at_epoch_millis INTEGER NOT NULL CHECK (released_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 7");
            }
            if (version < 8) {
                statement.execute("""
                        ALTER TABLE fiscal_reservation
                        ADD COLUMN settled_minor_units INTEGER NOT NULL DEFAULT 0
                        CHECK (settled_minor_units >= 0 AND settled_minor_units <= amount_minor_units)
                        """);
                statement.execute("PRAGMA user_version = 8");
            }
            if (version < 9) {
                statement.execute("""
                        ALTER TABLE payment_transaction
                        ADD COLUMN kind TEXT NOT NULL DEFAULT 'PAYMENT'
                        CHECK (kind IN ('PAYMENT', 'REFUND'))
                        """);
                statement.execute("""
                        ALTER TABLE payment_transaction
                        ADD COLUMN parent_transaction_id TEXT REFERENCES payment_transaction(transaction_id)
                        """);
                statement.execute("""
                        ALTER TABLE payment_transaction
                        ADD COLUMN refunded_minor_units INTEGER NOT NULL DEFAULT 0
                        CHECK (refunded_minor_units >= 0 AND refunded_minor_units <= amount_minor_units)
                        """);
                statement.execute("ALTER TABLE payment_transaction ADD COLUMN reason TEXT");
                statement.execute("""
                        CREATE UNIQUE INDEX payment_one_incomplete_refund
                        ON payment_transaction (parent_transaction_id)
                        WHERE kind = 'REFUND' AND state IN ('PREPARED', 'EXTERNAL_APPLIED')
                        """);
                statement.execute("PRAGMA user_version = 9");
            }
            if (version < 10) {
                statement.execute("DROP INDEX payment_one_incomplete_refund");
                statement.execute("""
                        CREATE UNIQUE INDEX payment_one_incomplete_refund
                        ON payment_transaction (parent_transaction_id)
                        WHERE kind = 'REFUND'
                          AND state IN ('PREPARED', 'EXTERNAL_APPLIED', 'COMPENSATING')
                        """);
                statement.execute("""
                        CREATE TABLE payment_compensation (
                            compensation_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            transaction_id TEXT NOT NULL UNIQUE
                                REFERENCES payment_transaction(transaction_id),
                            reason TEXT NOT NULL,
                            state TEXT NOT NULL CHECK (state IN ('STARTED', 'COMPLETED')),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE payment_recovery_audit (
                            audit_id TEXT PRIMARY KEY,
                            transaction_id TEXT NOT NULL
                                REFERENCES payment_transaction(transaction_id),
                            action TEXT NOT NULL CHECK (action IN (
                                'COMPENSATION_STARTED', 'COMPENSATION_COMPLETED',
                                'RECOVERY_EXTERNAL_APPLIED', 'RECOVERY_CIVIC_COMMITTED'
                            )),
                            service_identity TEXT NOT NULL,
                            detail TEXT NOT NULL,
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0),
                            UNIQUE (transaction_id, action)
                        )
                        """);
                statement.execute("PRAGMA user_version = 10");
            }
            if (version < 11) {
                statement.execute("""
                        CREATE TABLE fiscal_escrow (
                            escrow_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            reservation_id TEXT NOT NULL UNIQUE
                                REFERENCES fiscal_reservation(reservation_id),
                            external_object_id TEXT NOT NULL,
                            purpose TEXT NOT NULL,
                            expires_at_epoch_millis INTEGER NOT NULL
                                CHECK (expires_at_epoch_millis >= 0),
                            state TEXT NOT NULL CHECK (state IN (
                                'RESERVED', 'PARTIALLY_SETTLED', 'SETTLED',
                                'RELEASED', 'EXPIRED', 'RECOVERING'
                            )),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (service_identity, external_object_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE escrow_expiry (
                            expiry_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            escrow_id TEXT NOT NULL UNIQUE REFERENCES fiscal_escrow(escrow_id),
                            expired_at_epoch_millis INTEGER NOT NULL
                                CHECK (expired_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 11");
            }
            if (version < 12) {
                statement.execute("""
                        CREATE TABLE fiscal_budget (
                            budget_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            source_account TEXT NOT NULL,
                            amount_minor_units INTEGER NOT NULL
                                CHECK (amount_minor_units > 0),
                            budget_code TEXT NOT NULL,
                            purpose TEXT NOT NULL,
                            expires_at_epoch_millis INTEGER NOT NULL
                                CHECK (expires_at_epoch_millis >= 0),
                            escrow_id TEXT UNIQUE REFERENCES fiscal_escrow(escrow_id),
                            approval_service_identity TEXT,
                            approval_request_id TEXT,
                            state TEXT NOT NULL CHECK (state IN (
                                'DRAFT', 'APPROVED', 'PARTIALLY_SPENT',
                                'SPENT', 'RELEASED', 'EXPIRED'
                            )),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (approval_service_identity, approval_request_id),
                            CHECK ((approval_service_identity IS NULL)
                                = (approval_request_id IS NULL))
                        )
                        """);
                statement.execute("PRAGMA user_version = 12");
            }
            if (version < 13) {
                statement.execute("""
                        ALTER TABLE fiscal_escrow
                        ADD COLUMN required_recipient_account TEXT
                        """);
                statement.execute("""
                        CREATE TABLE fiscal_bill (
                            bill_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            payer_account TEXT NOT NULL,
                            beneficiary_account TEXT NOT NULL,
                            amount_minor_units INTEGER NOT NULL
                                CHECK (amount_minor_units > 0),
                            kind TEXT NOT NULL CHECK (kind IN ('TAX', 'FEE', 'DUES')),
                            purpose TEXT NOT NULL,
                            due_at_epoch_millis INTEGER NOT NULL
                                CHECK (due_at_epoch_millis >= 0),
                            escrow_id TEXT UNIQUE REFERENCES fiscal_escrow(escrow_id),
                            funding_service_identity TEXT,
                            funding_request_id TEXT,
                            state TEXT NOT NULL CHECK (state IN (
                                'ISSUED', 'RESERVED', 'PARTIALLY_PAID',
                                'PAID', 'CANCELLED', 'EXPIRED'
                            )),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (funding_service_identity, funding_request_id),
                            CHECK ((funding_service_identity IS NULL)
                                = (funding_request_id IS NULL))
                        )
                        """);
                statement.execute("PRAGMA user_version = 13");
            }
            if (version < 14) {
                statement.execute("""
                        CREATE TABLE fiscal_ledger_entry (
                            entry_id TEXT PRIMARY KEY,
                            transaction_id TEXT NOT NULL
                                REFERENCES payment_transaction(transaction_id),
                            account_id TEXT NOT NULL,
                            counterparty_account_id TEXT NOT NULL,
                            amount_minor_units INTEGER NOT NULL
                                CHECK (amount_minor_units > 0),
                            direction TEXT NOT NULL CHECK (direction IN ('OUTFLOW', 'INFLOW')),
                            transaction_kind TEXT NOT NULL
                                CHECK (transaction_kind IN ('PAYMENT', 'REFUND')),
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0),
                            UNIQUE (transaction_id, direction)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX fiscal_ledger_entry_account_time
                        ON fiscal_ledger_entry (account_id, recorded_at_epoch_millis)
                        """);
                statement.execute("PRAGMA user_version = 14");
            }
            if (version < 15) {
                statement.execute("""
                        CREATE TABLE fiscal_service (
                            service_identity TEXT PRIMARY KEY,
                            owner_mod_id TEXT NOT NULL,
                            display_name TEXT NOT NULL,
                            registered_at_epoch_millis INTEGER NOT NULL
                                CHECK (registered_at_epoch_millis >= 0)
                        )
                        """);
                statement.execute("PRAGMA user_version = 15");
            }
            if (version < 16) {
                statement.execute("""
                        CREATE TABLE fiscal_service_grant (
                            grant_id TEXT PRIMARY KEY,
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            service_identity TEXT NOT NULL
                                REFERENCES fiscal_service(service_identity),
                            capability TEXT NOT NULL CHECK (capability IN (
                                'READ_ACCOUNT', 'RESERVE_FUNDS', 'MANAGE_ESCROW',
                                'MANAGE_BUDGET', 'ISSUE_BILL', 'FUND_BILL',
                                'SETTLE_PAYMENT', 'REFUND_PAYMENT', 'COMPENSATE_PAYMENT'
                            )),
                            account_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            granted_at_epoch_millis INTEGER NOT NULL
                                CHECK (granted_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id),
                            UNIQUE (service_identity, capability, account_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 16");
            }
            if (version < 17) {
                statement.execute("ALTER TABLE fiscal_service_grant RENAME TO fiscal_service_grant_v16");
                statement.execute("""
                        CREATE TABLE fiscal_service_grant (
                            grant_id TEXT PRIMARY KEY,
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            service_identity TEXT NOT NULL
                                REFERENCES fiscal_service(service_identity),
                            capability TEXT NOT NULL CHECK (capability IN (
                                'READ_ACCOUNT', 'RESERVE_FUNDS', 'MANAGE_ESCROW',
                                'MANAGE_BUDGET', 'ISSUE_BILL', 'FUND_BILL',
                                'SETTLE_PAYMENT', 'REFUND_PAYMENT', 'COMPENSATE_PAYMENT'
                            )),
                            account_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            granted_at_epoch_millis INTEGER NOT NULL
                                CHECK (granted_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO fiscal_service_grant (
                            grant_id, administrator_identity, request_id, service_identity,
                            capability, account_id, reason, granted_at_epoch_millis
                        )
                        SELECT grant_id, administrator_identity, request_id, service_identity,
                               capability, account_id, reason, granted_at_epoch_millis
                        FROM fiscal_service_grant_v16
                        """);
                statement.execute("DROP TABLE fiscal_service_grant_v16");
                statement.execute("""
                        CREATE INDEX fiscal_service_grant_active_scope
                        ON fiscal_service_grant (service_identity, capability, account_id)
                        """);
                statement.execute("""
                        CREATE TABLE fiscal_service_grant_revocation (
                            revocation_id TEXT PRIMARY KEY,
                            grant_id TEXT NOT NULL UNIQUE
                                REFERENCES fiscal_service_grant(grant_id),
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            revoked_at_epoch_millis INTEGER NOT NULL
                                CHECK (revoked_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 17");
            }
            if (version < 18) {
                statement.execute("""
                        CREATE TABLE fiscal_service_state_change (
                            change_id TEXT PRIMARY KEY,
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            service_identity TEXT NOT NULL
                                REFERENCES fiscal_service(service_identity),
                            state TEXT NOT NULL CHECK (state IN ('ENABLED', 'DISABLED')),
                            reason TEXT NOT NULL,
                            changed_at_epoch_millis INTEGER NOT NULL
                                CHECK (changed_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX fiscal_service_state_current
                        ON fiscal_service_state_change (service_identity, changed_at_epoch_millis)
                        """);
                statement.execute("PRAGMA user_version = 18");
            }
            if (version < 19) {
                statement.execute("""
                        CREATE TABLE fiscal_service_registration_audit (
                            service_identity TEXT PRIMARY KEY
                                REFERENCES fiscal_service(service_identity),
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            registered_at_epoch_millis INTEGER NOT NULL
                                CHECK (registered_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO fiscal_service_registration_audit (
                            service_identity, administrator_identity, request_id, reason,
                            registered_at_epoch_millis
                        )
                        SELECT service_identity,
                               'civiceconomy-legacy',
                               'legacy-registration:' || service_identity,
                               'Backfilled from pre-audit fiscal service registration',
                               registered_at_epoch_millis
                        FROM fiscal_service
                        """);
                statement.execute("PRAGMA user_version = 19");
            }
            if (version < 20) {
                statement.execute("""
                        CREATE TABLE nation_application (
                            application_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            ftb_team_id TEXT NOT NULL,
                            applicant_player_id TEXT NOT NULL,
                            created_at_epoch_millis INTEGER NOT NULL
                                CHECK (created_at_epoch_millis >= 0),
                            expires_at_epoch_millis INTEGER NOT NULL
                                CHECK (expires_at_epoch_millis > created_at_epoch_millis),
                            state TEXT NOT NULL CHECK (state IN (
                                'PENDING', 'CANCELLED', 'EXPIRED', 'ACTIVATED'
                            )),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX nation_application_one_pending_team
                        ON nation_application (ftb_team_id)
                        WHERE state = 'PENDING'
                        """);
                statement.execute("""
                        CREATE TABLE nation_application_candidate (
                            application_id TEXT NOT NULL
                                REFERENCES nation_application(application_id),
                            player_id TEXT NOT NULL,
                            affiliated_at_epoch_millis INTEGER NOT NULL
                                CHECK (affiliated_at_epoch_millis >= 0),
                            ended_at_epoch_millis INTEGER CHECK (
                                ended_at_epoch_millis IS NULL
                                OR ended_at_epoch_millis >= affiliated_at_epoch_millis
                            ),
                            PRIMARY KEY (application_id, player_id)
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX nation_application_one_active_candidate_affiliation
                        ON nation_application_candidate (player_id)
                        WHERE ended_at_epoch_millis IS NULL
                        """);
                statement.execute("""
                        CREATE TABLE nation_application_evidence (
                            interval_id TEXT PRIMARY KEY
                                REFERENCES online_time_interval(interval_id),
                            application_id TEXT NOT NULL,
                            player_id TEXT NOT NULL,
                            attributed_start_epoch_millis INTEGER NOT NULL
                                CHECK (attributed_start_epoch_millis >= 0),
                            attributed_end_epoch_millis INTEGER NOT NULL CHECK (
                                attributed_end_epoch_millis > attributed_start_epoch_millis
                            ),
                            claimed_at_epoch_millis INTEGER NOT NULL
                                CHECK (claimed_at_epoch_millis >= 0),
                            FOREIGN KEY (application_id, player_id)
                                REFERENCES nation_application_candidate(application_id, player_id)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX nation_application_evidence_application_candidate
                        ON nation_application_evidence (application_id, player_id)
                        """);
                statement.execute("""
                        CREATE TABLE nation_application_transition (
                            transition_id TEXT PRIMARY KEY,
                            application_id TEXT NOT NULL UNIQUE
                                REFERENCES nation_application(application_id),
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            actor_player_id TEXT,
                            observation_window_millis INTEGER CHECK (
                                observation_window_millis IS NULL
                                OR observation_window_millis > 0
                            ),
                            from_state TEXT NOT NULL CHECK (from_state = 'PENDING'),
                            to_state TEXT NOT NULL CHECK (to_state IN (
                                'CANCELLED', 'EXPIRED', 'ACTIVATED'
                            )),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            effective_at_epoch_millis INTEGER NOT NULL
                                CHECK (effective_at_epoch_millis >= 0),
                            transitioned_at_epoch_millis INTEGER NOT NULL
                                CHECK (transitioned_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE nation_application_activation (
                            application_id TEXT PRIMARY KEY
                                REFERENCES nation_application(application_id),
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL UNIQUE,
                            ftb_team_id TEXT NOT NULL UNIQUE,
                            treasury_account_id TEXT NOT NULL UNIQUE,
                            capital_dimension_id TEXT NOT NULL
                                CHECK (length(trim(capital_dimension_id)) > 0),
                            capital_chunk_x INTEGER NOT NULL,
                            capital_chunk_z INTEGER NOT NULL,
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            minimum_effective_candidates INTEGER NOT NULL
                                CHECK (minimum_effective_candidates > 0),
                            minimum_candidate_bypass_allowed INTEGER NOT NULL
                                CHECK (minimum_candidate_bypass_allowed IN (0, 1)),
                            observation_window_millis INTEGER NOT NULL
                                CHECK (observation_window_millis > 0),
                            state TEXT NOT NULL CHECK (state IN (
                                'PREPARED', 'TREASURY_PROVISIONED', 'COMMITTED'
                            )),
                            prepared_at_epoch_millis INTEGER NOT NULL
                                CHECK (prepared_at_epoch_millis >= 0),
                            treasury_provisioned_at_epoch_millis INTEGER,
                            committed_at_epoch_millis INTEGER,
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE nation_capital (
                            nation_id TEXT PRIMARY KEY
                                REFERENCES nation_registry(nation_id),
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            established_at_epoch_millis INTEGER NOT NULL
                                CHECK (established_at_epoch_millis >= 0)
                        )
                        """);
                statement.execute("PRAGMA user_version = 20");
            }
            if (version < 21) {
                statement.execute("""
                        CREATE TABLE citizenship_correction_grace (
                            grace_id TEXT PRIMARY KEY,
                            citizenship_id TEXT NOT NULL
                                REFERENCES citizenship_period(citizenship_id),
                            player_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ftb_team_id TEXT NOT NULL,
                            start_service_identity TEXT NOT NULL,
                            start_request_id TEXT NOT NULL,
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            started_at_epoch_millis INTEGER NOT NULL
                                CHECK (started_at_epoch_millis >= 0),
                            deadline_epoch_millis INTEGER NOT NULL
                                CHECK (deadline_epoch_millis > started_at_epoch_millis),
                            resolution TEXT CHECK (resolution IN (
                                'RESTORED', 'CITIZENSHIP_ENDED'
                            )),
                            resolution_service_identity TEXT,
                            resolution_request_id TEXT,
                            resolved_at_epoch_millis INTEGER CHECK (
                                resolved_at_epoch_millis IS NULL
                                OR resolved_at_epoch_millis >= started_at_epoch_millis
                            ),
                            CHECK ((resolution IS NULL
                                    AND resolution_service_identity IS NULL
                                    AND resolution_request_id IS NULL
                                    AND resolved_at_epoch_millis IS NULL)
                                OR (resolution IS NOT NULL
                                    AND resolution_service_identity IS NOT NULL
                                    AND resolution_request_id IS NOT NULL
                                    AND resolved_at_epoch_millis IS NOT NULL)),
                            UNIQUE (start_service_identity, start_request_id)
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX citizenship_one_active_correction_grace
                        ON citizenship_correction_grace (citizenship_id)
                        WHERE resolution IS NULL
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX citizenship_correction_grace_resolution_request
                        ON citizenship_correction_grace (
                            resolution_service_identity, resolution_request_id
                        )
                        WHERE resolution_request_id IS NOT NULL
                        """);
                statement.execute("PRAGMA user_version = 21");
            }
            if (version < 22) {
                if (!tableHasColumn("citizenship_correction_grace", "resolution_reason")) {
                    statement.execute("""
                            ALTER TABLE citizenship_correction_grace
                            ADD COLUMN resolution_reason TEXT
                            """);
                }
                statement.execute("""
                        UPDATE citizenship_correction_grace
                        SET resolution_reason = 'civiceconomy-legacy-v21-resolution'
                        WHERE resolution IS NOT NULL AND resolution_reason IS NULL
                        """);
                statement.execute("PRAGMA user_version = 22");
            }
            if (version < 23) {
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
                                'MANAGE_PUBLIC_POLICY', 'MANAGE_RECOVERY'
                            )),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            granted_at_epoch_millis INTEGER NOT NULL
                                CHECK (granted_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
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
                statement.execute("PRAGMA user_version = 23");
            }
            if (version < 24) {
                statement.execute("""
                        CREATE TABLE territory_free_allocation_policy (
                            policy_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            actor_identity TEXT NOT NULL
                                CHECK (length(trim(actor_identity)) > 0),
                            base_chunks INTEGER NOT NULL CHECK (base_chunks >= 0),
                            chunks_per_effective_citizen INTEGER NOT NULL
                                CHECK (chunks_per_effective_citizen >= 0),
                            effective_at_epoch_millis INTEGER NOT NULL
                                CHECK (effective_at_epoch_millis >= 0),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (effective_at_epoch_millis)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX territory_free_allocation_policy_current
                        ON territory_free_allocation_policy (effective_at_epoch_millis)
                        """);
                statement.execute("PRAGMA user_version = 24");
            }
            if (version < 25) {
                statement.execute("""
                        CREATE TABLE territory_expansion_pricing_policy (
                            policy_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            actor_identity TEXT NOT NULL
                                CHECK (length(trim(actor_identity)) > 0),
                            first_overage_chunk_cost INTEGER NOT NULL
                                CHECK (first_overage_chunk_cost >= 0),
                            additional_marginal_cost INTEGER NOT NULL
                                CHECK (additional_marginal_cost >= 0),
                            effective_at_epoch_millis INTEGER NOT NULL
                                CHECK (effective_at_epoch_millis >= 0),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (effective_at_epoch_millis)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX territory_expansion_pricing_policy_current
                        ON territory_expansion_pricing_policy (effective_at_epoch_millis)
                        """);
                statement.execute("PRAGMA user_version = 25");
            }
            if (version < 26) {
                statement.execute("""
                        CREATE TABLE territory_claim_permit (
                            permit_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ftb_team_id TEXT NOT NULL,
                            actor_player_id TEXT NOT NULL,
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            quoted_current_claimed_chunks INTEGER NOT NULL
                                CHECK (quoted_current_claimed_chunks >= 0),
                            quoted_free_allocation INTEGER NOT NULL
                                CHECK (quoted_free_allocation >= 0),
                            prepayment_minor_units INTEGER NOT NULL
                                CHECK (prepayment_minor_units > 0),
                            prepayment_transaction_id TEXT NOT NULL,
                            state TEXT NOT NULL CHECK (state IN (
                                'READY', 'CONSUMED', 'CANCELLED', 'EXPIRED'
                            )),
                            issued_at_epoch_millis INTEGER NOT NULL
                                CHECK (issued_at_epoch_millis >= 0),
                            expires_at_epoch_millis INTEGER NOT NULL
                                CHECK (expires_at_epoch_millis > issued_at_epoch_millis),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (prepayment_transaction_id)
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX territory_claim_permit_one_ready_target
                        ON territory_claim_permit (nation_id, dimension_id, chunk_x, chunk_z)
                        WHERE state = 'READY'
                        """);
                statement.execute("""
                        CREATE INDEX territory_claim_permit_ready_expiry
                        ON territory_claim_permit (expires_at_epoch_millis)
                        WHERE state = 'READY'
                        """);
                statement.execute("""
                        CREATE TABLE territory_claim_permit_consumption (
                            permit_id TEXT PRIMARY KEY
                                REFERENCES territory_claim_permit(permit_id),
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ftb_team_id TEXT NOT NULL,
                            actor_player_id TEXT NOT NULL,
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            consumed_at_epoch_millis INTEGER NOT NULL
                                CHECK (consumed_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 26");
            }
            if (version < 27) {
                statement.execute("""
                        CREATE TABLE territory_claim_permit_compensation (
                            compensation_id TEXT PRIMARY KEY,
                            permit_id TEXT NOT NULL UNIQUE
                                REFERENCES territory_claim_permit(permit_id),
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            actor_identity TEXT NOT NULL
                                CHECK (length(trim(actor_identity)) > 0),
                            kind TEXT NOT NULL CHECK (kind IN ('CANCEL', 'EXPIRE')),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            refund_request_id TEXT NOT NULL UNIQUE,
                            refund_transaction_id TEXT UNIQUE
                                REFERENCES payment_transaction(transaction_id),
                            requested_at_epoch_millis INTEGER NOT NULL
                                CHECK (requested_at_epoch_millis >= 0),
                            completed_at_epoch_millis INTEGER
                                CHECK (completed_at_epoch_millis IS NULL
                                    OR completed_at_epoch_millis >= requested_at_epoch_millis),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX territory_claim_permit_compensation_incomplete
                        ON territory_claim_permit_compensation (requested_at_epoch_millis)
                        WHERE completed_at_epoch_millis IS NULL
                        """);
                statement.execute("PRAGMA user_version = 27");
            }
            if (version < 28) {
                statement.execute("""
                        CREATE TABLE territory_maintenance_cycle (
                            cycle_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            starts_at_epoch_millis INTEGER NOT NULL
                                CHECK (starts_at_epoch_millis >= 0),
                            ends_at_epoch_millis INTEGER NOT NULL
                                CHECK (ends_at_epoch_millis > starts_at_epoch_millis),
                            opened_at_epoch_millis INTEGER NOT NULL
                                CHECK (opened_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (starts_at_epoch_millis, ends_at_epoch_millis)
                        )
                        """);
                statement.execute("""
                        CREATE TRIGGER territory_maintenance_cycle_no_overlap
                        BEFORE INSERT ON territory_maintenance_cycle
                        WHEN EXISTS (
                            SELECT 1 FROM territory_maintenance_cycle existing
                            WHERE NEW.starts_at_epoch_millis < existing.ends_at_epoch_millis
                              AND NEW.ends_at_epoch_millis > existing.starts_at_epoch_millis
                        )
                        BEGIN
                            SELECT RAISE(ABORT, 'Territory Maintenance Cycle overlaps');
                        END
                        """);
                statement.execute("""
                        CREATE TABLE territory_fiscal_assessment (
                            assessment_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            cycle_id TEXT NOT NULL
                                REFERENCES territory_maintenance_cycle(cycle_id),
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ftb_team_id TEXT NOT NULL,
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            maintenance_due_minor_units INTEGER NOT NULL
                                CHECK (maintenance_due_minor_units >= 0),
                            validity TEXT NOT NULL
                                CHECK (validity IN ('EFFECTIVE', 'SUSPENDED')),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            assessed_at_epoch_millis INTEGER NOT NULL
                                CHECK (assessed_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (cycle_id, nation_id, dimension_id, chunk_x, chunk_z)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX territory_fiscal_assessment_validity
                        ON territory_fiscal_assessment (
                            cycle_id, nation_id, validity, dimension_id, chunk_x, chunk_z
                        )
                        """);
                statement.execute("PRAGMA user_version = 28");
            }
            if (version < 29) {
                statement.execute("""
                        CREATE TABLE monetary_supply_event (
                            event_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            change_kind TEXT NOT NULL
                                CHECK (change_kind IN ('ISSUANCE', 'PERMANENT_DESTRUCTION')),
                            amount_minor_units INTEGER NOT NULL
                                CHECK (amount_minor_units > 0),
                            external_reference TEXT NOT NULL
                                CHECK (length(trim(external_reference)) > 0),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            confirmed_at_epoch_millis INTEGER NOT NULL
                                CHECK (confirmed_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (change_kind, external_reference)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE monetary_supply_summary (
                            singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                            cumulative_net_issuance_minor_units INTEGER NOT NULL
                                CHECK (cumulative_net_issuance_minor_units >= 0)
                        )
                        """);
                statement.execute("""
                        INSERT INTO monetary_supply_summary (
                            singleton, cumulative_net_issuance_minor_units
                        ) VALUES (1, 0)
                        """);
                statement.execute("PRAGMA user_version = 29");
            }
            if (version < 30) {
                statement.execute("DROP INDEX fiscal_service_grant_active_scope");
                statement.execute("ALTER TABLE fiscal_service_grant_revocation RENAME TO fiscal_service_grant_revocation_v29");
                statement.execute("ALTER TABLE fiscal_service_grant RENAME TO fiscal_service_grant_v29");
                statement.execute("""
                        CREATE TABLE fiscal_service_grant (
                            grant_id TEXT PRIMARY KEY,
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            service_identity TEXT NOT NULL
                                REFERENCES fiscal_service(service_identity),
                            capability TEXT NOT NULL CHECK (capability IN (
                                'READ_ACCOUNT', 'RESERVE_FUNDS', 'MANAGE_ESCROW',
                                'MANAGE_BUDGET', 'ISSUE_BILL', 'FUND_BILL',
                                'SETTLE_PAYMENT', 'REFUND_PAYMENT', 'COMPENSATE_PAYMENT',
                                'PERMANENT_DESTRUCTION'
                            )),
                            account_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            granted_at_epoch_millis INTEGER NOT NULL
                                CHECK (granted_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO fiscal_service_grant
                        SELECT * FROM fiscal_service_grant_v29
                        """);
                statement.execute("""
                        CREATE INDEX fiscal_service_grant_active_scope
                        ON fiscal_service_grant (service_identity, capability, account_id)
                        """);
                statement.execute("""
                        CREATE TABLE fiscal_service_grant_revocation (
                            revocation_id TEXT PRIMARY KEY,
                            grant_id TEXT NOT NULL UNIQUE
                                REFERENCES fiscal_service_grant(grant_id),
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            revoked_at_epoch_millis INTEGER NOT NULL
                                CHECK (revoked_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO fiscal_service_grant_revocation
                        SELECT * FROM fiscal_service_grant_revocation_v29
                        """);
                statement.execute("DROP TABLE fiscal_service_grant_revocation_v29");
                statement.execute("DROP TABLE fiscal_service_grant_v29");
                statement.execute("""
                        CREATE TABLE permanent_destruction_operation (
                            operation_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            source_account TEXT NOT NULL,
                            amount_minor_units INTEGER NOT NULL
                                CHECK (amount_minor_units > 0),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            state TEXT NOT NULL CHECK (state IN ('PREPARED', 'COMMITTED')),
                            prepared_at_epoch_millis INTEGER NOT NULL
                                CHECK (prepared_at_epoch_millis >= 0),
                            committed_at_epoch_millis INTEGER,
                            UNIQUE (service_identity, request_id),
                            CHECK ((state = 'PREPARED' AND committed_at_epoch_millis IS NULL)
                                OR (state = 'COMMITTED' AND committed_at_epoch_millis IS NOT NULL))
                        )
                        """);
                statement.execute("PRAGMA user_version = 30");
            }
            if (version < 31) {
                statement.execute("ALTER TABLE territory_fiscal_assessment RENAME TO territory_fiscal_assessment_v30");
                statement.execute("""
                        CREATE TABLE territory_fiscal_assessment (
                            assessment_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            cycle_id TEXT NOT NULL
                                REFERENCES territory_maintenance_cycle(cycle_id),
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ftb_team_id TEXT NOT NULL,
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            maintenance_due_minor_units INTEGER NOT NULL
                                CHECK (maintenance_due_minor_units >= 0),
                            validity TEXT NOT NULL
                                CHECK (validity IN ('PENDING', 'EFFECTIVE', 'SUSPENDED')),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            assessed_at_epoch_millis INTEGER NOT NULL
                                CHECK (assessed_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (cycle_id, nation_id, dimension_id, chunk_x, chunk_z)
                        )
                        """);
                statement.execute("""
                        INSERT INTO territory_fiscal_assessment (
                            assessment_id, service_identity, request_id, cycle_id,
                            nation_id, ftb_team_id, dimension_id, chunk_x, chunk_z,
                            maintenance_due_minor_units, validity, reason,
                            assessed_at_epoch_millis
                        )
                        SELECT assessment_id, service_identity, request_id, cycle_id,
                               nation_id, ftb_team_id, dimension_id, chunk_x, chunk_z,
                               maintenance_due_minor_units, validity, reason,
                               assessed_at_epoch_millis
                        FROM territory_fiscal_assessment_v30
                        """);
                statement.execute("DROP TABLE territory_fiscal_assessment_v30");
                statement.execute("""
                        CREATE INDEX territory_fiscal_assessment_validity
                        ON territory_fiscal_assessment (
                            cycle_id, nation_id, validity, dimension_id, chunk_x, chunk_z
                        )
                        """);
                statement.execute("""
                        CREATE TABLE territory_maintenance_settlement (
                            settlement_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            cycle_id TEXT NOT NULL
                                REFERENCES territory_maintenance_cycle(cycle_id),
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            reservation_id TEXT UNIQUE
                                REFERENCES fiscal_reservation(reservation_id),
                            public_fund_payment_id TEXT UNIQUE
                                REFERENCES payment_transaction(transaction_id),
                            destruction_operation_id TEXT UNIQUE
                                REFERENCES permanent_destruction_operation(operation_id),
                            validity TEXT NOT NULL
                                CHECK (validity IN ('EFFECTIVE', 'SUSPENDED')),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            settled_at_epoch_millis INTEGER NOT NULL
                                CHECK (settled_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (cycle_id, nation_id),
                            CHECK ((validity = 'EFFECTIVE'
                                    AND reservation_id IS NOT NULL
                                    AND public_fund_payment_id IS NOT NULL)
                                OR (validity = 'SUSPENDED'
                                    AND reservation_id IS NULL
                                    AND public_fund_payment_id IS NULL
                                    AND destruction_operation_id IS NULL))
                        )
                        """);
                statement.execute("PRAGMA user_version = 31");
            }
            if (version < 32) {
                statement.execute("""
                        ALTER TABLE territory_fiscal_assessment
                        ADD COLUMN priority TEXT NOT NULL DEFAULT 'ORDINARY'
                        CHECK (priority IN (
                            'CAPITAL', 'CAPITAL_CONNECTED_CORE', 'VALID_INFRASTRUCTURE',
                            'ORDINARY', 'ENCLAVE_OR_CROSS_DIMENSION'
                        ))
                        """);
                statement.execute("PRAGMA user_version = 32");
            }
            if (version < 33) {
                statement.execute("""
                        ALTER TABLE territory_maintenance_settlement
                        RENAME TO territory_maintenance_settlement_v32
                        """);
                statement.execute("""
                        CREATE TABLE territory_maintenance_settlement (
                            settlement_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            cycle_id TEXT NOT NULL
                                REFERENCES territory_maintenance_cycle(cycle_id),
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            reservation_id TEXT UNIQUE
                                REFERENCES fiscal_reservation(reservation_id),
                            public_fund_payment_id TEXT UNIQUE
                                REFERENCES payment_transaction(transaction_id),
                            destruction_operation_id TEXT UNIQUE
                                REFERENCES permanent_destruction_operation(operation_id),
                            outcome TEXT NOT NULL CHECK (outcome IN (
                                'FULLY_FUNDED', 'PARTIALLY_FUNDED', 'UNFUNDED'
                            )),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            settled_at_epoch_millis INTEGER NOT NULL
                                CHECK (settled_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (cycle_id, nation_id),
                            CHECK ((outcome IN ('FULLY_FUNDED', 'PARTIALLY_FUNDED')
                                    AND reservation_id IS NOT NULL
                                    AND public_fund_payment_id IS NOT NULL)
                                OR (outcome = 'UNFUNDED'
                                    AND reservation_id IS NULL
                                    AND public_fund_payment_id IS NULL
                                    AND destruction_operation_id IS NULL))
                        )
                        """);
                statement.execute("""
                        INSERT INTO territory_maintenance_settlement (
                            settlement_id, service_identity, request_id, cycle_id, nation_id,
                            reservation_id, public_fund_payment_id, destruction_operation_id,
                            outcome, reason, settled_at_epoch_millis
                        )
                        SELECT settlement_id, service_identity, request_id, cycle_id, nation_id,
                               reservation_id, public_fund_payment_id, destruction_operation_id,
                               CASE validity
                                   WHEN 'EFFECTIVE' THEN 'FULLY_FUNDED'
                                   ELSE 'UNFUNDED'
                               END,
                               reason, settled_at_epoch_millis
                        FROM territory_maintenance_settlement_v32
                        """);
                statement.execute("DROP TABLE territory_maintenance_settlement_v32");
                statement.execute("""
                        CREATE TABLE territory_maintenance_settlement_assessment (
                            settlement_id TEXT NOT NULL
                                REFERENCES territory_maintenance_settlement(settlement_id)
                                ON DELETE CASCADE,
                            assessment_id TEXT NOT NULL UNIQUE
                                REFERENCES territory_fiscal_assessment(assessment_id),
                            validity TEXT NOT NULL
                                CHECK (validity IN ('EFFECTIVE', 'SUSPENDED')),
                            PRIMARY KEY (settlement_id, assessment_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO territory_maintenance_settlement_assessment (
                            settlement_id, assessment_id, validity
                        )
                        SELECT settlement_id, assessment_id,
                               CASE outcome
                                   WHEN 'UNFUNDED' THEN 'SUSPENDED'
                                   ELSE 'EFFECTIVE'
                               END
                        FROM territory_maintenance_settlement
                        JOIN territory_fiscal_assessment USING (cycle_id, nation_id)
                        """);
                statement.execute("PRAGMA user_version = 33");
            }
            if (version < 34) {
                statement.execute("""
                        CREATE TABLE territory_maintenance_policy (
                            policy_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            actor_identity TEXT NOT NULL
                                CHECK (length(trim(actor_identity)) > 0),
                            cycle_duration_millis INTEGER NOT NULL
                                CHECK (cycle_duration_millis > 0),
                            base_maintenance_per_chargeable_claim_minor_units INTEGER NOT NULL
                                CHECK (base_maintenance_per_chargeable_claim_minor_units >= 0),
                            enclave_cross_dimension_multiplier_basis_points INTEGER NOT NULL
                                CHECK (enclave_cross_dimension_multiplier_basis_points >= 10000),
                            force_load_surcharge_minor_units INTEGER NOT NULL
                                CHECK (force_load_surcharge_minor_units >= 0),
                            destruction_basis_points INTEGER NOT NULL
                                CHECK (destruction_basis_points BETWEEN 3000 AND 8000),
                            effective_at_epoch_millis INTEGER NOT NULL
                                CHECK (effective_at_epoch_millis >= 0),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (effective_at_epoch_millis)
                        )
                        """);
                statement.execute("PRAGMA user_version = 34");
            }
            if (version < 35) {
                statement.execute("""
                        CREATE TABLE territory_maintenance_assessment_batch (
                            cycle_id TEXT PRIMARY KEY
                                REFERENCES territory_maintenance_cycle(cycle_id),
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            claim_count INTEGER NOT NULL CHECK (claim_count >= 0),
                            snapshot_sha256 TEXT NOT NULL
                                CHECK (length(snapshot_sha256) = 64),
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 35");
            }
            if (version < 36) {
                statement.execute("""
                        CREATE TABLE territory_maintenance_assessment_claim (
                            cycle_id TEXT NOT NULL
                                REFERENCES territory_maintenance_assessment_batch(cycle_id)
                                ON DELETE CASCADE,
                            ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ftb_team_id TEXT NOT NULL,
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            maintenance_due_minor_units INTEGER NOT NULL
                                CHECK (maintenance_due_minor_units >= 0),
                            priority TEXT NOT NULL CHECK (priority IN (
                                'CAPITAL', 'CAPITAL_CONNECTED_CORE', 'VALID_INFRASTRUCTURE',
                                'ORDINARY', 'ENCLAVE_OR_CROSS_DIMENSION'
                            )),
                            PRIMARY KEY (cycle_id, ordinal),
                            UNIQUE (cycle_id, nation_id, dimension_id, chunk_x, chunk_z)
                        )
                        """);
                statement.execute("PRAGMA user_version = 36");
            }
            if (version < 37) {
                statement.execute("""
                        INSERT INTO territory_maintenance_assessment_claim (
                            cycle_id, ordinal, nation_id, ftb_team_id, dimension_id,
                            chunk_x, chunk_z, maintenance_due_minor_units, priority
                        )
                        SELECT assessment.cycle_id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY assessment.cycle_id
                                   ORDER BY CASE WHEN lower(substr(assessment.nation_id, 1, 1))
                                                        IN ('8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
                                                 THEN 0 ELSE 1 END,
                                            lower(substr(assessment.nation_id, 1, 18)),
                                            CASE WHEN lower(substr(assessment.nation_id, 20, 1))
                                                        IN ('8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
                                                 THEN 0 ELSE 1 END,
                                            lower(substr(assessment.nation_id, 20)),
                                            assessment.dimension_id,
                                            assessment.chunk_x, assessment.chunk_z
                               ) - 1,
                               assessment.nation_id, assessment.ftb_team_id,
                               assessment.dimension_id, assessment.chunk_x, assessment.chunk_z,
                               assessment.maintenance_due_minor_units, assessment.priority
                        FROM territory_fiscal_assessment assessment
                        JOIN territory_maintenance_assessment_batch batch
                          ON batch.cycle_id = assessment.cycle_id
                        WHERE NOT EXISTS (
                                  SELECT 1 FROM territory_maintenance_assessment_claim existing
                                  WHERE existing.cycle_id = assessment.cycle_id
                              )
                          AND batch.claim_count = (
                                  SELECT COUNT(*) FROM territory_fiscal_assessment counted
                                  WHERE counted.cycle_id = assessment.cycle_id
                              )
                        """);
                statement.execute("PRAGMA user_version = 37");
            }
            if (version < 38) {
                statement.execute("""
                        ALTER TABLE territory_maintenance_settlement_assessment
                        RENAME TO territory_maintenance_settlement_assessment_v37
                        """);
                statement.execute("""
                        ALTER TABLE territory_maintenance_settlement
                        RENAME TO territory_maintenance_settlement_v37
                        """);
                statement.execute("""
                        CREATE TABLE territory_maintenance_settlement (
                            settlement_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            cycle_id TEXT NOT NULL
                                REFERENCES territory_maintenance_cycle(cycle_id),
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            reservation_id TEXT UNIQUE
                                REFERENCES fiscal_reservation(reservation_id),
                            public_fund_payment_id TEXT UNIQUE
                                REFERENCES payment_transaction(transaction_id),
                            destruction_operation_id TEXT UNIQUE
                                REFERENCES permanent_destruction_operation(operation_id),
                            outcome TEXT NOT NULL CHECK (outcome IN (
                                'FULLY_FUNDED', 'PARTIALLY_FUNDED', 'UNFUNDED'
                            )),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            settled_at_epoch_millis INTEGER NOT NULL
                                CHECK (settled_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (cycle_id, nation_id),
                            CHECK ((outcome IN ('FULLY_FUNDED', 'PARTIALLY_FUNDED')
                                    AND ((reservation_id IS NOT NULL
                                            AND public_fund_payment_id IS NOT NULL)
                                        OR (reservation_id IS NULL
                                            AND public_fund_payment_id IS NULL
                                            AND destruction_operation_id IS NULL)))
                                OR (outcome = 'UNFUNDED'
                                    AND reservation_id IS NULL
                                    AND public_fund_payment_id IS NULL
                                    AND destruction_operation_id IS NULL))
                        )
                        """);
                statement.execute("""
                        INSERT INTO territory_maintenance_settlement
                        SELECT * FROM territory_maintenance_settlement_v37
                        """);
                statement.execute("""
                        CREATE TABLE territory_maintenance_settlement_assessment (
                            settlement_id TEXT NOT NULL
                                REFERENCES territory_maintenance_settlement(settlement_id)
                                ON DELETE CASCADE,
                            assessment_id TEXT NOT NULL UNIQUE
                                REFERENCES territory_fiscal_assessment(assessment_id),
                            validity TEXT NOT NULL
                                CHECK (validity IN ('EFFECTIVE', 'SUSPENDED')),
                            PRIMARY KEY (settlement_id, assessment_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO territory_maintenance_settlement_assessment
                        SELECT * FROM territory_maintenance_settlement_assessment_v37
                        """);
                statement.execute("DROP TABLE territory_maintenance_settlement_assessment_v37");
                statement.execute("DROP TABLE territory_maintenance_settlement_v37");
                statement.execute("PRAGMA user_version = 38");
            }
            if (version < 39) {
                statement.execute("""
                        ALTER TABLE territory_maintenance_policy
                        ADD COLUMN restoration_fee_minor_units INTEGER NOT NULL DEFAULT 0
                        CHECK (restoration_fee_minor_units >= 0)
                        """);
                statement.execute("""
                        ALTER TABLE territory_maintenance_policy
                        ADD COLUMN restoration_cooldown_millis INTEGER NOT NULL DEFAULT 1
                        CHECK (restoration_cooldown_millis > 0)
                        """);
                statement.execute("""
                        UPDATE territory_maintenance_policy
                        SET restoration_fee_minor_units =
                                base_maintenance_per_chargeable_claim_minor_units,
                            restoration_cooldown_millis = cycle_duration_millis
                        """);
                statement.execute("PRAGMA user_version = 39");
            }
            if (version < 40) {
                statement.execute("""
                        ALTER TABLE territory_maintenance_assessment_claim
                        ADD COLUMN restoration_fee_minor_units INTEGER NOT NULL DEFAULT 0
                        CHECK (restoration_fee_minor_units >= 0)
                        """);
                statement.execute("""
                        ALTER TABLE territory_maintenance_assessment_claim
                        ADD COLUMN restoration_eligibility TEXT NOT NULL DEFAULT 'NOT_REQUIRED'
                        CHECK (restoration_eligibility IN (
                            'NOT_REQUIRED', 'ELIGIBLE', 'COOLDOWN_BLOCKED'
                        ))
                        """);
                statement.execute("""
                        ALTER TABLE territory_maintenance_assessment_claim
                        ADD COLUMN restoration_cooldown_ends_at_epoch_millis INTEGER
                        CHECK (restoration_cooldown_ends_at_epoch_millis IS NULL
                            OR restoration_cooldown_ends_at_epoch_millis >= 0)
                        """);
                statement.execute("""
                        ALTER TABLE territory_fiscal_assessment
                        ADD COLUMN restoration_fee_minor_units INTEGER NOT NULL DEFAULT 0
                        CHECK (restoration_fee_minor_units >= 0)
                        """);
                statement.execute("""
                        ALTER TABLE territory_fiscal_assessment
                        ADD COLUMN restoration_eligibility TEXT NOT NULL DEFAULT 'NOT_REQUIRED'
                        CHECK (restoration_eligibility IN (
                            'NOT_REQUIRED', 'ELIGIBLE', 'COOLDOWN_BLOCKED'
                        ))
                        """);
                statement.execute("""
                        ALTER TABLE territory_fiscal_assessment
                        ADD COLUMN restoration_cooldown_ends_at_epoch_millis INTEGER
                        CHECK (restoration_cooldown_ends_at_epoch_millis IS NULL
                            OR restoration_cooldown_ends_at_epoch_millis >= 0)
                        """);
                statement.execute("PRAGMA user_version = 40");
            }
            if (version < 41) {
                statement.execute("""
                        CREATE TABLE territory_force_load_enforcement (
                            enforcement_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            assessment_id TEXT NOT NULL UNIQUE
                                REFERENCES territory_fiscal_assessment(assessment_id),
                            ftb_team_id TEXT NOT NULL,
                            dimension_id TEXT NOT NULL,
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            state TEXT NOT NULL CHECK (state IN (
                                'PREPARED', 'EXTERNAL_APPLIED', 'CIVIC_COMMITTED'
                            )),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            prepared_at_epoch_millis INTEGER NOT NULL
                                CHECK (prepared_at_epoch_millis >= 0),
                            external_applied_at_epoch_millis INTEGER
                                CHECK (external_applied_at_epoch_millis IS NULL
                                    OR external_applied_at_epoch_millis >= 0),
                            committed_at_epoch_millis INTEGER
                                CHECK (committed_at_epoch_millis IS NULL
                                    OR committed_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            CHECK (
                                (state = 'PREPARED'
                                    AND external_applied_at_epoch_millis IS NULL
                                    AND committed_at_epoch_millis IS NULL)
                                OR (state = 'EXTERNAL_APPLIED'
                                    AND external_applied_at_epoch_millis IS NOT NULL
                                    AND committed_at_epoch_millis IS NULL)
                                OR (state = 'CIVIC_COMMITTED'
                                    AND external_applied_at_epoch_millis IS NOT NULL
                                    AND committed_at_epoch_millis IS NOT NULL)
                            )
                        )
                        """);
                statement.execute("""
                        CREATE INDEX territory_force_load_enforcement_incomplete
                        ON territory_force_load_enforcement (state, prepared_at_epoch_millis)
                        WHERE state IN ('PREPARED', 'EXTERNAL_APPLIED')
                        """);
                statement.execute("PRAGMA user_version = 41");
            }
            if (version < 42) {
                statement.execute("""
                        ALTER TABLE territory_force_load_enforcement
                        ADD COLUMN not_before_epoch_millis INTEGER NOT NULL DEFAULT 0
                        CHECK (not_before_epoch_millis >= 0)
                        """);
                statement.execute("""
                        UPDATE territory_force_load_enforcement
                        SET not_before_epoch_millis = COALESCE((
                            SELECT CASE
                                WHEN cycle.ends_at_epoch_millis
                                        <= 9223372036854775807 - 86400000
                                THEN cycle.ends_at_epoch_millis + 86400000
                                ELSE 9223372036854775807
                            END
                            FROM territory_fiscal_assessment assessment
                            JOIN territory_maintenance_cycle cycle
                              ON cycle.cycle_id = assessment.cycle_id
                            WHERE assessment.assessment_id =
                                    territory_force_load_enforcement.assessment_id
                        ), 9223372036854775807)
                        """);
                statement.execute("PRAGMA user_version = 42");
            }
            if (version < 43) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS territory_maintenance_restoration (
                            restoration_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ftb_team_id TEXT NOT NULL,
                            actor_player_id TEXT NOT NULL,
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            source_suspended_assessment_id TEXT NOT NULL UNIQUE
                                REFERENCES territory_fiscal_assessment(assessment_id),
                            policy_id TEXT NOT NULL
                                REFERENCES territory_maintenance_policy(policy_id),
                            next_full_cycle_starts_at_epoch_millis INTEGER NOT NULL
                                CHECK (next_full_cycle_starts_at_epoch_millis >= 0),
                            next_cycle_prepayment_minor_units INTEGER NOT NULL
                                CHECK (next_cycle_prepayment_minor_units >= 0),
                            restoration_fee_minor_units INTEGER NOT NULL
                                CHECK (restoration_fee_minor_units >= 0),
                            total_due_minor_units INTEGER NOT NULL
                                CHECK (total_due_minor_units >= 0),
                            remaining_next_cycle_credit_minor_units INTEGER NOT NULL
                                CHECK (remaining_next_cycle_credit_minor_units >= 0
                                    AND remaining_next_cycle_credit_minor_units
                                        <= next_cycle_prepayment_minor_units),
                            cooldown_ends_at_epoch_millis INTEGER NOT NULL
                                CHECK (cooldown_ends_at_epoch_millis >= 0),
                            destruction_basis_points INTEGER NOT NULL
                                CHECK (destruction_basis_points BETWEEN 3000 AND 8000),
                            state TEXT NOT NULL
                                CHECK (state IN ('PREPARED', 'CIVIC_COMMITTED')),
                            reservation_id TEXT UNIQUE
                                REFERENCES fiscal_reservation(reservation_id),
                            public_fund_payment_id TEXT UNIQUE
                                REFERENCES payment_transaction(transaction_id),
                            destruction_operation_id TEXT UNIQUE
                                REFERENCES permanent_destruction_operation(operation_id),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            prepared_at_epoch_millis INTEGER NOT NULL
                                CHECK (prepared_at_epoch_millis >= 0),
                            committed_at_epoch_millis INTEGER
                                CHECK (committed_at_epoch_millis IS NULL
                                    OR committed_at_epoch_millis >= prepared_at_epoch_millis),
                            UNIQUE (service_identity, request_id),
                            CHECK (total_due_minor_units =
                                next_cycle_prepayment_minor_units
                                    + restoration_fee_minor_units),
                            CHECK ((state = 'PREPARED'
                                    AND reservation_id IS NULL
                                    AND public_fund_payment_id IS NULL
                                    AND destruction_operation_id IS NULL
                                    AND committed_at_epoch_millis IS NULL)
                                OR (state = 'CIVIC_COMMITTED'
                                    AND committed_at_epoch_millis IS NOT NULL))
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS territory_maintenance_restoration_target
                        ON territory_maintenance_restoration (
                            nation_id, ftb_team_id, dimension_id, chunk_x, chunk_z,
                            state, prepared_at_epoch_millis
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS territory_maintenance_restoration_credit
                        ON territory_maintenance_restoration (
                            next_full_cycle_starts_at_epoch_millis,
                            remaining_next_cycle_credit_minor_units
                        )
                        WHERE state = 'CIVIC_COMMITTED'
                          AND remaining_next_cycle_credit_minor_units > 0
                        """);
                statement.execute("PRAGMA user_version = 43");
            }
            if (version < 44) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS
                                territory_maintenance_restoration_credit_application (
                            restoration_id TEXT NOT NULL
                                REFERENCES territory_maintenance_restoration(restoration_id),
                            assessment_id TEXT NOT NULL UNIQUE
                                REFERENCES territory_fiscal_assessment(assessment_id),
                            gross_maintenance_due_minor_units INTEGER NOT NULL
                                CHECK (gross_maintenance_due_minor_units >= 0),
                            applied_minor_units INTEGER NOT NULL
                                CHECK (applied_minor_units > 0
                                    AND applied_minor_units
                                        <= gross_maintenance_due_minor_units),
                            applied_at_epoch_millis INTEGER NOT NULL
                                CHECK (applied_at_epoch_millis >= 0),
                            PRIMARY KEY (restoration_id, assessment_id)
                        )
                        """);
                statement.execute("PRAGMA user_version = 44");
            }
            if (version < 45) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS database_backup_operation (
                            operation_id TEXT PRIMARY KEY,
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            file_name TEXT NOT NULL UNIQUE,
                            reason TEXT NOT NULL,
                            state TEXT NOT NULL
                                CHECK (state IN ('PREPARED', 'COMMITTED', 'RETIRED')),
                            size_bytes INTEGER NOT NULL DEFAULT 0
                                CHECK (size_bytes >= 0),
                            sha256 TEXT,
                            prepared_at_epoch_millis INTEGER NOT NULL
                                CHECK (prepared_at_epoch_millis >= 0),
                            committed_at_epoch_millis INTEGER,
                            retired_at_epoch_millis INTEGER,
                            UNIQUE (administrator_identity, request_id),
                            CHECK ((state = 'PREPARED'
                                    AND size_bytes = 0
                                    AND sha256 IS NULL
                                    AND committed_at_epoch_millis IS NULL
                                    AND retired_at_epoch_millis IS NULL)
                                OR (state = 'COMMITTED'
                                    AND size_bytes > 0
                                    AND length(sha256) = 64
                                    AND committed_at_epoch_millis IS NOT NULL
                                    AND retired_at_epoch_millis IS NULL)
                                OR (state = 'RETIRED'
                                    AND size_bytes > 0
                                    AND length(sha256) = 64
                                    AND committed_at_epoch_millis IS NOT NULL
                                    AND retired_at_epoch_millis IS NOT NULL))
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS database_backup_audit (
                            audit_id TEXT PRIMARY KEY,
                            operation_id TEXT NOT NULL
                                REFERENCES database_backup_operation(operation_id),
                            action TEXT NOT NULL
                                CHECK (action IN ('PREPARED', 'FAILED', 'COMMITTED', 'RETIRED')),
                            detail TEXT NOT NULL,
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS database_backup_operation_state_time
                        ON database_backup_operation (
                            state, committed_at_epoch_millis, prepared_at_epoch_millis
                        )
                        """);
                statement.execute("PRAGMA user_version = 45");
            }
            if (version < 46) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS database_restore_operation (
                            operation_id TEXT PRIMARY KEY,
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            source_backup_operation_id TEXT NOT NULL,
                            source_file_name TEXT NOT NULL,
                            source_size_bytes INTEGER NOT NULL
                                CHECK (source_size_bytes > 0),
                            source_sha256 TEXT NOT NULL
                                CHECK (length(source_sha256) = 64),
                            rollback_backup_operation_id TEXT,
                            rollback_file_name TEXT,
                            rollback_size_bytes INTEGER NOT NULL DEFAULT 0
                                CHECK (rollback_size_bytes >= 0),
                            rollback_sha256 TEXT,
                            state TEXT NOT NULL
                                CHECK (state IN ('STAGED', 'ACTIVATED', 'CANCELLED')),
                            reason TEXT NOT NULL,
                            staged_at_epoch_millis INTEGER NOT NULL
                                CHECK (staged_at_epoch_millis >= 0),
                            activated_at_epoch_millis INTEGER,
                            cancellation_administrator_identity TEXT,
                            cancellation_request_id TEXT,
                            cancellation_reason TEXT,
                            cancelled_at_epoch_millis INTEGER,
                            UNIQUE (administrator_identity, request_id),
                            UNIQUE (
                                cancellation_administrator_identity,
                                cancellation_request_id
                            ),
                            CHECK ((state = 'STAGED'
                                    AND rollback_backup_operation_id IS NULL
                                    AND rollback_file_name IS NULL
                                    AND rollback_size_bytes = 0
                                    AND rollback_sha256 IS NULL
                                    AND activated_at_epoch_millis IS NULL
                                    AND cancelled_at_epoch_millis IS NULL)
                                OR (state = 'ACTIVATED'
                                    AND rollback_backup_operation_id IS NOT NULL
                                    AND rollback_file_name IS NOT NULL
                                    AND rollback_size_bytes > 0
                                    AND length(rollback_sha256) = 64
                                    AND activated_at_epoch_millis IS NOT NULL
                                    AND cancelled_at_epoch_millis IS NULL)
                                OR (state = 'CANCELLED'
                                    AND rollback_backup_operation_id IS NULL
                                    AND rollback_file_name IS NULL
                                    AND rollback_size_bytes = 0
                                    AND rollback_sha256 IS NULL
                                    AND activated_at_epoch_millis IS NULL
                                    AND cancellation_administrator_identity IS NOT NULL
                                    AND cancellation_request_id IS NOT NULL
                                    AND cancellation_reason IS NOT NULL
                                    AND cancelled_at_epoch_millis IS NOT NULL))
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS database_restore_one_staged
                        ON database_restore_operation (state)
                        WHERE state = 'STAGED'
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS database_restore_audit (
                            audit_id TEXT PRIMARY KEY,
                            operation_id TEXT NOT NULL
                                REFERENCES database_restore_operation(operation_id),
                            action TEXT NOT NULL
                                CHECK (action IN ('STAGED', 'ACTIVATED', 'CANCELLED', 'FAILED')),
                            actor_identity TEXT NOT NULL,
                            detail TEXT NOT NULL,
                            recorded_at_epoch_millis INTEGER NOT NULL
                                CHECK (recorded_at_epoch_millis >= 0)
                        )
                        """);
                statement.execute("PRAGMA user_version = 46");
            }
            if (version < 47) {
                statement.execute("DROP INDEX fiscal_service_grant_active_scope");
                statement.execute("ALTER TABLE fiscal_service_grant_revocation RENAME TO fiscal_service_grant_revocation_v46");
                statement.execute("ALTER TABLE fiscal_service_grant RENAME TO fiscal_service_grant_v46");
                statement.execute("""
                        CREATE TABLE fiscal_service_grant (
                            grant_id TEXT PRIMARY KEY,
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            service_identity TEXT NOT NULL
                                REFERENCES fiscal_service(service_identity),
                            capability TEXT NOT NULL CHECK (capability IN (
                                'READ_ACCOUNT', 'RESERVE_FUNDS', 'MANAGE_ESCROW',
                                'MANAGE_BUDGET', 'ISSUE_BILL', 'FUND_BILL',
                                'SETTLE_PAYMENT', 'REFUND_PAYMENT', 'COMPENSATE_PAYMENT',
                                'PERMANENT_DESTRUCTION', 'MANAGE_ISSUANCE'
                            )),
                            account_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            granted_at_epoch_millis INTEGER NOT NULL
                                CHECK (granted_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO fiscal_service_grant
                        SELECT * FROM fiscal_service_grant_v46
                        """);
                statement.execute("""
                        CREATE INDEX fiscal_service_grant_active_scope
                        ON fiscal_service_grant (service_identity, capability, account_id)
                        """);
                statement.execute("""
                        CREATE TABLE fiscal_service_grant_revocation (
                            revocation_id TEXT PRIMARY KEY,
                            grant_id TEXT NOT NULL UNIQUE
                                REFERENCES fiscal_service_grant(grant_id),
                            administrator_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            revoked_at_epoch_millis INTEGER NOT NULL
                                CHECK (revoked_at_epoch_millis >= 0),
                            UNIQUE (administrator_identity, request_id)
                        )
                        """);
                statement.execute("""
                        INSERT INTO fiscal_service_grant_revocation
                        SELECT * FROM fiscal_service_grant_revocation_v46
                        """);
                statement.execute("DROP TABLE fiscal_service_grant_revocation_v46");
                statement.execute("DROP TABLE fiscal_service_grant_v46");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS issuance_quota_period (
                            period_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            starts_at_epoch_millis INTEGER NOT NULL
                                CHECK (starts_at_epoch_millis >= 0),
                            ends_at_epoch_millis INTEGER NOT NULL,
                            hard_cap_minor_units INTEGER NOT NULL
                                CHECK (hard_cap_minor_units >= 0),
                            global_quota_minor_units INTEGER NOT NULL
                                CHECK (global_quota_minor_units >= 0
                                    AND global_quota_minor_units <= hard_cap_minor_units),
                            reason TEXT NOT NULL CHECK (length(reason) > 0),
                            published_at_epoch_millis INTEGER NOT NULL
                                CHECK (published_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            CHECK (ends_at_epoch_millis > starts_at_epoch_millis)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS national_issuance_quota (
                            period_id TEXT NOT NULL
                                REFERENCES issuance_quota_period(period_id),
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            ceiling_minor_units INTEGER NOT NULL
                                CHECK (ceiling_minor_units >= 0),
                            activated_minor_units INTEGER NOT NULL DEFAULT 0
                                CHECK (activated_minor_units >= 0
                                    AND activated_minor_units <= ceiling_minor_units),
                            reserved_minor_units INTEGER NOT NULL DEFAULT 0
                                CHECK (reserved_minor_units >= 0),
                            used_minor_units INTEGER NOT NULL DEFAULT 0
                                CHECK (used_minor_units >= 0),
                            PRIMARY KEY (period_id, nation_id),
                            CHECK (reserved_minor_units + used_minor_units
                                <= activated_minor_units)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS national_issuance_quota_activation (
                            activation_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            period_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL,
                            actor_player_id TEXT NOT NULL,
                            activated_minor_units INTEGER NOT NULL
                                CHECK (activated_minor_units >= 0),
                            reason TEXT NOT NULL CHECK (length(reason) > 0),
                            activated_at_epoch_millis INTEGER NOT NULL
                                CHECK (activated_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            FOREIGN KEY (period_id, nation_id)
                                REFERENCES national_issuance_quota(period_id, nation_id)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS issuance_quota_period_time
                        ON issuance_quota_period (starts_at_epoch_millis, ends_at_epoch_millis)
                        """);
                statement.execute("PRAGMA user_version = 47");
            }
            if (version < 48) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS mint_recipe_version (
                            recipe_version_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            version_number INTEGER NOT NULL UNIQUE
                                CHECK (version_number > 0),
                            processing_duration_millis INTEGER NOT NULL
                                CHECK (processing_duration_millis > 0),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            published_at_epoch_millis INTEGER NOT NULL
                                CHECK (published_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS mint_recipe_ingredient (
                            recipe_version_id TEXT NOT NULL
                                REFERENCES mint_recipe_version(recipe_version_id),
                            ingredient_index INTEGER NOT NULL
                                CHECK (ingredient_index >= 0),
                            group_index INTEGER NOT NULL CHECK (group_index >= 0),
                            matcher_kind TEXT NOT NULL
                                CHECK (matcher_kind IN ('EXACT_ITEM', 'TAG')),
                            matcher_value TEXT NOT NULL
                                CHECK (length(trim(matcher_value)) > 0),
                            quantity_units INTEGER NOT NULL CHECK (quantity_units > 0),
                            per_face_value_minor_units INTEGER NOT NULL
                                CHECK (per_face_value_minor_units >= 0),
                            PRIMARY KEY (recipe_version_id, ingredient_index),
                            UNIQUE (recipe_version_id, group_index, matcher_kind, matcher_value)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS registered_mint (
                            mint_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            block_x INTEGER NOT NULL,
                            block_y INTEGER NOT NULL,
                            block_z INTEGER NOT NULL,
                            operator_organization_id TEXT NOT NULL,
                            license_id TEXT NOT NULL,
                            automation_allowed INTEGER NOT NULL
                                CHECK (automation_allowed IN (0, 1)),
                            recipe_version_id TEXT NOT NULL
                                REFERENCES mint_recipe_version(recipe_version_id),
                            actor_player_id TEXT NOT NULL,
                            transaction_state TEXT NOT NULL
                                CHECK (transaction_state IN ('IDLE', 'PROCESSING', 'RECOVERY')),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            registered_at_epoch_millis INTEGER NOT NULL
                                CHECK (registered_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (license_id),
                            UNIQUE (dimension_id, block_x, block_y, block_z)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS registered_mint_nation_state
                        ON registered_mint (nation_id, transaction_state, mint_id)
                        """);
                statement.execute("PRAGMA user_version = 48");
            }
            if (version < 49) {
                if (!tableExists("mint_batch")) {
                    statement.execute("DROP INDEX IF EXISTS registered_mint_nation_state");
                statement.execute("ALTER TABLE registered_mint RENAME TO registered_mint_v48");
                statement.execute("""
                        CREATE TABLE registered_mint (
                            mint_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                            dimension_id TEXT NOT NULL
                                CHECK (length(trim(dimension_id)) > 0),
                            block_x INTEGER NOT NULL,
                            block_y INTEGER NOT NULL,
                            block_z INTEGER NOT NULL,
                            operator_organization_id TEXT NOT NULL,
                            license_id TEXT NOT NULL,
                            automation_allowed INTEGER NOT NULL
                                CHECK (automation_allowed IN (0, 1)),
                            recipe_version_id TEXT NOT NULL
                                REFERENCES mint_recipe_version(recipe_version_id),
                            actor_player_id TEXT NOT NULL,
                            transaction_state TEXT NOT NULL CHECK (transaction_state IN (
                                'IDLE', 'PREPARING', 'PROCESSING', 'RECOVERY'
                            )),
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            registered_at_epoch_millis INTEGER NOT NULL
                                CHECK (registered_at_epoch_millis >= 0),
                            UNIQUE (service_identity, request_id),
                            UNIQUE (license_id),
                            UNIQUE (dimension_id, block_x, block_y, block_z)
                        )
                        """);
                statement.execute("""
                        INSERT INTO registered_mint SELECT * FROM registered_mint_v48
                        """);
                statement.execute("DROP TABLE registered_mint_v48");
                statement.execute("""
                        CREATE INDEX registered_mint_nation_state
                        ON registered_mint (nation_id, transaction_state, mint_id)
                        """);
                statement.execute("""
                        CREATE TABLE mint_batch (
                            batch_id TEXT PRIMARY KEY,
                            service_identity TEXT NOT NULL,
                            request_id TEXT NOT NULL,
                            mint_id TEXT NOT NULL REFERENCES registered_mint(mint_id),
                            period_id TEXT NOT NULL,
                            nation_id TEXT NOT NULL,
                            recipe_version_id TEXT NOT NULL
                                REFERENCES mint_recipe_version(recipe_version_id),
                            issued_minor_units INTEGER NOT NULL
                                CHECK (issued_minor_units > 0),
                            actor_player_id TEXT NOT NULL,
                            state TEXT NOT NULL CHECK (state IN (
                                'PREPARING', 'PROCESSING', 'COMMITTING', 'COMMITTED',
                                'CANCELLING', 'CANCELLED', 'RECOVERY'
                            )),
                            custody_state TEXT NOT NULL CHECK (custody_state IN (
                                'EXTERNAL_PENDING', 'HELD', 'CONSUME_PENDING',
                                'CONSUMED', 'RETURN_PENDING', 'RETURNED'
                            )),
                            custody_service_identity TEXT,
                            custody_request_id TEXT,
                            custody_external_reference TEXT UNIQUE,
                            reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                            prepared_at_epoch_millis INTEGER NOT NULL
                                CHECK (prepared_at_epoch_millis >= 0),
                            processing_started_at_epoch_millis INTEGER,
                            processing_completes_at_epoch_millis INTEGER,
                            UNIQUE (service_identity, request_id),
                            UNIQUE (custody_service_identity, custody_request_id),
                            FOREIGN KEY (period_id, nation_id)
                                REFERENCES national_issuance_quota(period_id, nation_id),
                            CHECK ((processing_started_at_epoch_millis IS NULL
                                    AND processing_completes_at_epoch_millis IS NULL)
                                OR (processing_started_at_epoch_millis IS NOT NULL
                                    AND processing_completes_at_epoch_millis
                                        > processing_started_at_epoch_millis))
                        )
                        """);
                statement.execute("""
                        CREATE TABLE mint_batch_material (
                            batch_id TEXT NOT NULL REFERENCES mint_batch(batch_id),
                            material_index INTEGER NOT NULL CHECK (material_index >= 0),
                            group_index INTEGER NOT NULL CHECK (group_index >= 0),
                            matcher_kind TEXT NOT NULL
                                CHECK (matcher_kind IN ('EXACT_ITEM', 'TAG')),
                            matcher_value TEXT NOT NULL,
                            item_id TEXT NOT NULL,
                            item_count INTEGER NOT NULL CHECK (item_count > 0),
                            PRIMARY KEY (batch_id, material_index),
                            UNIQUE (batch_id, group_index)
                        )
                        """);
                    statement.execute("""
                        CREATE INDEX mint_batch_recovery
                        ON mint_batch (state, prepared_at_epoch_millis, batch_id)
                        WHERE state NOT IN ('COMMITTED', 'CANCELLED')
                        """);
                }
                statement.execute("PRAGMA user_version = 49");
            }
            if (version < 50) {
                addColumnIfMissing(
                        statement, "mint_batch", "cancellation_service_identity", "TEXT");
                addColumnIfMissing(
                        statement, "mint_batch", "cancellation_request_id", "TEXT");
                addColumnIfMissing(
                        statement, "mint_batch", "cancellation_actor_player_id", "TEXT");
                addColumnIfMissing(statement, "mint_batch", "cancellation_reason", "TEXT");
                addColumnIfMissing(
                        statement,
                        "mint_batch",
                        "cancellation_prepared_at_epoch_millis",
                        "INTEGER");
                addColumnIfMissing(statement, "mint_batch", "return_service_identity", "TEXT");
                addColumnIfMissing(statement, "mint_batch", "return_request_id", "TEXT");
                addColumnIfMissing(statement, "mint_batch", "return_external_reference", "TEXT");
                addColumnIfMissing(statement, "mint_batch", "cancelled_at_epoch_millis", "INTEGER");
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS mint_batch_cancellation_request
                        ON mint_batch (cancellation_service_identity, cancellation_request_id)
                        WHERE cancellation_request_id IS NOT NULL
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS mint_batch_return_request
                        ON mint_batch (return_service_identity, return_request_id)
                        WHERE return_request_id IS NOT NULL
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS mint_batch_return_reference
                        ON mint_batch (return_external_reference)
                        WHERE return_external_reference IS NOT NULL
                        """);
                statement.execute("PRAGMA user_version = 50");
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private boolean tableHasColumn(String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (columns.next()) {
                if (columnName.equals(columns.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void addColumnIfMissing(
            Statement statement, String tableName, String columnName, String definition)
            throws SQLException {
        if (!tableHasColumn(tableName, columnName)) {
            statement.execute(
                    "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
                """)) {
            query.setString(1, tableName);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private StoredFiscalCapabilityGrant readFiscalCapabilityGrant(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredFiscalCapabilityGrant(
                    UUID.fromString(result.getString("grant_id")),
                    result.getString("administrator_identity"),
                    result.getString("request_id"),
                    result.getString("service_identity"),
                    result.getString("capability"),
                    result.getString("account_id"),
                    result.getString("reason"),
                    result.getLong("granted_at_epoch_millis"));
        }
    }

    private StoredDatabaseBackupOperation requireDatabaseBackupOperation(UUID operationId) {
        StoredDatabaseBackupOperation operation = databaseBackupOperation(operationId);
        if (operation == null) {
            throw new IllegalArgumentException("Unknown database backup operation " + operationId);
        }
        return operation;
    }

    private StoredDatabaseRestoreOperation requireDatabaseRestoreOperation(UUID operationId) {
        StoredDatabaseRestoreOperation operation = databaseRestoreOperation(operationId);
        if (operation == null) {
            throw new IllegalArgumentException("Unknown database restore operation " + operationId);
        }
        return operation;
    }

    private StoredDatabaseRestoreOperation databaseRestoreCancellation(
            String administratorIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM database_restore_operation
                WHERE cancellation_administrator_identity = ?
                  AND cancellation_request_id = ?
                """)) {
            query.setString(1, administratorIdentity);
            query.setString(2, requestId);
            return readDatabaseRestoreOperation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read database restore cancellation", failure);
        }
    }

    private static void bindRestoreActivation(
            PreparedStatement insert,
            UUID operationId,
            String administratorIdentity,
            String requestId,
            UUID sourceBackupOperationId,
            String sourceFileName,
            long sourceSizeBytes,
            String sourceSha256,
            UUID rollbackBackupOperationId,
            String rollbackFileName,
            long rollbackSizeBytes,
            String rollbackSha256,
            String reason,
            long stagedAtEpochMillis,
            long activatedAtEpochMillis)
            throws SQLException {
        insert.setString(1, operationId.toString());
        insert.setString(2, administratorIdentity);
        insert.setString(3, requestId);
        insert.setString(4, sourceBackupOperationId.toString());
        insert.setString(5, sourceFileName);
        insert.setLong(6, sourceSizeBytes);
        insert.setString(7, sourceSha256);
        insert.setString(8, rollbackBackupOperationId.toString());
        insert.setString(9, rollbackFileName);
        insert.setLong(10, rollbackSizeBytes);
        insert.setString(11, rollbackSha256);
        insert.setString(12, reason);
        insert.setLong(13, stagedAtEpochMillis);
        insert.setLong(14, activatedAtEpochMillis);
    }

    private static void requireRestoreActivationPayload(
            StoredDatabaseRestoreOperation existing,
            String administratorIdentity,
            String requestId,
            UUID sourceBackupOperationId,
            String sourceFileName,
            long sourceSizeBytes,
            String sourceSha256,
            UUID rollbackBackupOperationId,
            String rollbackFileName,
            long rollbackSizeBytes,
            String rollbackSha256,
            String reason,
            long stagedAtEpochMillis) {
        boolean changed = !existing.administratorIdentity().equals(administratorIdentity)
                || !existing.requestId().equals(requestId)
                || !existing.sourceBackupOperationId().equals(sourceBackupOperationId)
                || !existing.sourceFileName().equals(sourceFileName)
                || existing.sourceSizeBytes() != sourceSizeBytes
                || !existing.sourceSha256().equals(sourceSha256)
                || !existing.reason().equals(reason)
                || existing.stagedAtEpochMillis() != stagedAtEpochMillis;
        if (rollbackBackupOperationId != null) {
            changed = changed
                    || !rollbackBackupOperationId.equals(existing.rollbackBackupOperationId())
                    || !rollbackFileName.equals(existing.rollbackFileName())
                    || rollbackSizeBytes != existing.rollbackSizeBytes()
                    || !rollbackSha256.equals(existing.rollbackSha256());
        }
        if (changed) {
            throw new IllegalArgumentException(
                    "Database restore activation replay changed its immutable payload");
        }
    }

    private StoredDatabaseRestoreOperation readDatabaseRestoreOperation(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? readDatabaseRestoreOperation(result) : null;
        }
    }

    private static StoredDatabaseRestoreOperation readDatabaseRestoreOperation(ResultSet result)
            throws SQLException {
        String rollbackOperationId = result.getString("rollback_backup_operation_id");
        long activatedAt = result.getLong("activated_at_epoch_millis");
        Long optionalActivatedAt = result.wasNull() ? null : activatedAt;
        long cancelledAt = result.getLong("cancelled_at_epoch_millis");
        Long optionalCancelledAt = result.wasNull() ? null : cancelledAt;
        return new StoredDatabaseRestoreOperation(
                UUID.fromString(result.getString("operation_id")),
                result.getString("administrator_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("source_backup_operation_id")),
                result.getString("source_file_name"),
                result.getLong("source_size_bytes"),
                result.getString("source_sha256"),
                rollbackOperationId == null ? null : UUID.fromString(rollbackOperationId),
                result.getString("rollback_file_name"),
                result.getLong("rollback_size_bytes"),
                result.getString("rollback_sha256"),
                result.getString("state"),
                result.getString("reason"),
                result.getLong("staged_at_epoch_millis"),
                optionalActivatedAt,
                result.getString("cancellation_administrator_identity"),
                result.getString("cancellation_request_id"),
                result.getString("cancellation_reason"),
                optionalCancelledAt);
    }

    private List<StoredDatabaseBackupOperation> databaseBackupOperations(
            String state, boolean ascending) {
        List<StoredDatabaseBackupOperation> operations = new ArrayList<>();
        String sql = "SELECT * FROM database_backup_operation"
                + (state == null ? "" : " WHERE state = ?")
                + " ORDER BY COALESCE(committed_at_epoch_millis, prepared_at_epoch_millis) "
                + (ascending ? "ASC" : "DESC")
                + ", rowid " + (ascending ? "ASC" : "DESC");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            if (state != null) {
                query.setString(1, state);
            }
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    operations.add(readDatabaseBackupOperation(result));
                }
            }
            return List.copyOf(operations);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to list database backup operations", failure);
        }
    }

    private StoredDatabaseBackupOperation readDatabaseBackupOperation(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? readDatabaseBackupOperation(result) : null;
        }
    }

    private static StoredDatabaseBackupOperation readDatabaseBackupOperation(ResultSet result)
            throws SQLException {
        long committedAt = result.getLong("committed_at_epoch_millis");
        Long optionalCommittedAt = result.wasNull() ? null : committedAt;
        long retiredAt = result.getLong("retired_at_epoch_millis");
        Long optionalRetiredAt = result.wasNull() ? null : retiredAt;
        return new StoredDatabaseBackupOperation(
                UUID.fromString(result.getString("operation_id")),
                result.getString("administrator_identity"),
                result.getString("request_id"),
                result.getString("file_name"),
                result.getString("reason"),
                result.getString("state"),
                result.getLong("size_bytes"),
                result.getString("sha256"),
                result.getLong("prepared_at_epoch_millis"),
                optionalCommittedAt,
                optionalRetiredAt);
    }

    private StoredNationFiscalPermissionGrant readNationFiscalPermissionGrant(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? storedNationFiscalPermissionGrant(result) : null;
        }
    }

    private StoredTerritoryFreeAllocationPolicy readTerritoryFreeAllocationPolicy(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredTerritoryFreeAllocationPolicy(
                    UUID.fromString(result.getString("policy_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getString("actor_identity"),
                    result.getInt("base_chunks"),
                    result.getInt("chunks_per_effective_citizen"),
                    result.getLong("effective_at_epoch_millis"),
                    result.getString("reason"),
                    result.getLong("recorded_at_epoch_millis"));
        }
    }

    private StoredTerritoryMaintenancePolicy readTerritoryMaintenancePolicy(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredTerritoryMaintenancePolicy(
                    UUID.fromString(result.getString("policy_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getString("actor_identity"),
                    result.getLong("cycle_duration_millis"),
                    result.getLong("base_maintenance_per_chargeable_claim_minor_units"),
                    result.getInt("enclave_cross_dimension_multiplier_basis_points"),
                    result.getLong("force_load_surcharge_minor_units"),
                    result.getLong("restoration_fee_minor_units"),
                    result.getLong("restoration_cooldown_millis"),
                    result.getInt("destruction_basis_points"),
                    result.getLong("effective_at_epoch_millis"),
                    result.getString("reason"),
                    result.getLong("recorded_at_epoch_millis"));
        }
    }

    private StoredTerritoryExpansionPricingPolicy readTerritoryExpansionPricingPolicy(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredTerritoryExpansionPricingPolicy(
                    UUID.fromString(result.getString("policy_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getString("actor_identity"),
                    result.getLong("first_overage_chunk_cost"),
                    result.getLong("additional_marginal_cost"),
                    result.getLong("effective_at_epoch_millis"),
                    result.getString("reason"),
                    result.getLong("recorded_at_epoch_millis"));
        }
    }

    private StoredTerritoryMaintenanceCycle readTerritoryMaintenanceCycle(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredTerritoryMaintenanceCycle(
                    UUID.fromString(result.getString("cycle_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getLong("starts_at_epoch_millis"),
                    result.getLong("ends_at_epoch_millis"),
                    result.getLong("opened_at_epoch_millis"));
        }
    }

    private StoredTerritoryMaintenanceAssessmentBatch readTerritoryMaintenanceAssessmentBatch(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredTerritoryMaintenanceAssessmentBatch(
                    UUID.fromString(result.getString("cycle_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getInt("claim_count"),
                    result.getString("snapshot_sha256"),
                    result.getLong("recorded_at_epoch_millis"));
        }
    }

    private StoredTerritoryFiscalAssessment readTerritoryFiscalAssessment(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? storedTerritoryFiscalAssessment(result) : null;
        }
    }

    private static StoredTerritoryFiscalAssessment storedTerritoryFiscalAssessment(
            ResultSet result) throws SQLException {
        return new StoredTerritoryFiscalAssessment(
                UUID.fromString(result.getString("assessment_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("cycle_id")),
                UUID.fromString(result.getString("nation_id")),
                UUID.fromString(result.getString("ftb_team_id")),
                result.getString("dimension_id"),
                result.getInt("chunk_x"),
                result.getInt("chunk_z"),
                result.getLong("maintenance_due_minor_units"),
                result.getLong("restoration_fee_minor_units"),
                result.getString("restoration_eligibility"),
                result.getObject("restoration_cooldown_ends_at_epoch_millis") == null
                        ? null
                        : result.getLong("restoration_cooldown_ends_at_epoch_millis"),
                result.getString("priority"),
                result.getString("validity"),
                result.getString("reason"),
                result.getLong("assessed_at_epoch_millis"));
    }

    private StoredTerritoryMaintenanceSettlement readTerritoryMaintenanceSettlement(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            String reservationId = result.getString("reservation_id");
            String publicFundPaymentId = result.getString("public_fund_payment_id");
            String destructionOperationId = result.getString("destruction_operation_id");
            return new StoredTerritoryMaintenanceSettlement(
                    UUID.fromString(result.getString("settlement_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("cycle_id")),
                    UUID.fromString(result.getString("nation_id")),
                    reservationId == null ? null : UUID.fromString(reservationId),
                    publicFundPaymentId == null ? null : UUID.fromString(publicFundPaymentId),
                    destructionOperationId == null
                            ? null
                            : UUID.fromString(destructionOperationId),
                    result.getString("outcome"),
                    territoryMaintenanceSettlementAssessmentIds(
                            UUID.fromString(result.getString("settlement_id")), "EFFECTIVE"),
                    territoryMaintenanceSettlementAssessmentIds(
                            UUID.fromString(result.getString("settlement_id")), "SUSPENDED"),
                    result.getString("reason"),
                    result.getLong("settled_at_epoch_millis"));
        }
    }

    private StoredTerritoryMaintenanceRestoration readTerritoryMaintenanceRestoration(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            String reservationId = result.getString("reservation_id");
            String publicFundPaymentId = result.getString("public_fund_payment_id");
            String destructionOperationId = result.getString("destruction_operation_id");
            long committedAt = result.getLong("committed_at_epoch_millis");
            Long committedAtEpochMillis = result.wasNull() ? null : committedAt;
            return new StoredTerritoryMaintenanceRestoration(
                    UUID.fromString(result.getString("restoration_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("nation_id")),
                    UUID.fromString(result.getString("ftb_team_id")),
                    UUID.fromString(result.getString("actor_player_id")),
                    result.getString("dimension_id"),
                    result.getInt("chunk_x"),
                    result.getInt("chunk_z"),
                    UUID.fromString(result.getString("source_suspended_assessment_id")),
                    UUID.fromString(result.getString("policy_id")),
                    result.getLong("next_full_cycle_starts_at_epoch_millis"),
                    result.getLong("next_cycle_prepayment_minor_units"),
                    result.getLong("restoration_fee_minor_units"),
                    result.getLong("total_due_minor_units"),
                    result.getLong("remaining_next_cycle_credit_minor_units"),
                    result.getLong("cooldown_ends_at_epoch_millis"),
                    result.getInt("destruction_basis_points"),
                    result.getString("state"),
                    reservationId == null ? null : UUID.fromString(reservationId),
                    publicFundPaymentId == null ? null : UUID.fromString(publicFundPaymentId),
                    destructionOperationId == null
                            ? null
                            : UUID.fromString(destructionOperationId),
                    result.getString("reason"),
                    result.getLong("prepared_at_epoch_millis"),
                    committedAtEpochMillis);
        }
    }

    private StoredTerritoryForceLoadEnforcement readTerritoryForceLoadEnforcement(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? storedTerritoryForceLoadEnforcement(result) : null;
        }
    }

    private static StoredTerritoryForceLoadEnforcement storedTerritoryForceLoadEnforcement(
            ResultSet result) throws SQLException {
        long externalAppliedAt = result.getLong("external_applied_at_epoch_millis");
        Long externalApplied = result.wasNull() ? null : externalAppliedAt;
        long committedAt = result.getLong("committed_at_epoch_millis");
        Long committed = result.wasNull() ? null : committedAt;
        return new StoredTerritoryForceLoadEnforcement(
                UUID.fromString(result.getString("enforcement_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("assessment_id")),
                UUID.fromString(result.getString("ftb_team_id")),
                result.getString("dimension_id"),
                result.getInt("chunk_x"),
                result.getInt("chunk_z"),
                result.getString("state"),
                result.getString("reason"),
                result.getLong("not_before_epoch_millis"),
                result.getLong("prepared_at_epoch_millis"),
                externalApplied,
                committed);
    }

    private List<UUID> territoryMaintenanceSettlementAssessmentIds(
            UUID settlementId, String validity) throws SQLException {
        List<UUID> assessmentIds = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT detail.assessment_id
                FROM territory_maintenance_settlement_assessment detail
                JOIN territory_fiscal_assessment assessment
                  ON assessment.assessment_id = detail.assessment_id
                WHERE detail.settlement_id = ? AND detail.validity = ?
                ORDER BY assessment.priority, assessment.dimension_id,
                         assessment.chunk_x, assessment.chunk_z, assessment.assessment_id
                """)) {
            query.setString(1, settlementId.toString());
            query.setString(2, validity);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    assessmentIds.add(UUID.fromString(result.getString("assessment_id")));
                }
            }
        }
        return List.copyOf(assessmentIds);
    }

    private StoredMonetarySupplyEvent readMonetarySupplyEvent(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? storedMonetarySupplyEvent(result) : null;
        }
    }

    private StoredPermanentDestructionOperation readPermanentDestructionOperation(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? storedPermanentDestructionOperation(result) : null;
        }
    }

    private static StoredPermanentDestructionOperation storedPermanentDestructionOperation(
            ResultSet result) throws SQLException {
        long committedAt = result.getLong("committed_at_epoch_millis");
        return new StoredPermanentDestructionOperation(
                UUID.fromString(result.getString("operation_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                result.getString("source_account"),
                result.getLong("amount_minor_units"),
                result.getString("reason"),
                result.getString("state"),
                result.getLong("prepared_at_epoch_millis"),
                result.wasNull() ? null : committedAt);
    }

    private static StoredMonetarySupplyEvent storedMonetarySupplyEvent(ResultSet result)
            throws SQLException {
        return new StoredMonetarySupplyEvent(
                UUID.fromString(result.getString("event_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                result.getString("change_kind"),
                result.getLong("amount_minor_units"),
                result.getString("external_reference"),
                result.getString("reason"),
                result.getLong("confirmed_at_epoch_millis"));
    }

    private StoredTerritoryClaimPermit readTerritoryClaimPermit(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? storedTerritoryClaimPermit(result) : null;
        }
    }

    private static StoredTerritoryClaimPermit storedTerritoryClaimPermit(ResultSet result)
            throws SQLException {
        return new StoredTerritoryClaimPermit(
                UUID.fromString(result.getString("permit_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("nation_id")),
                UUID.fromString(result.getString("ftb_team_id")),
                UUID.fromString(result.getString("actor_player_id")),
                result.getString("dimension_id"),
                result.getInt("chunk_x"),
                result.getInt("chunk_z"),
                result.getInt("quoted_current_claimed_chunks"),
                result.getInt("quoted_free_allocation"),
                result.getLong("prepayment_minor_units"),
                UUID.fromString(result.getString("prepayment_transaction_id")),
                result.getString("state"),
                result.getLong("issued_at_epoch_millis"),
                result.getLong("expires_at_epoch_millis"));
    }

    private StoredTerritoryClaimPermitConsumption readTerritoryClaimPermitConsumption(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredTerritoryClaimPermitConsumption(
                    UUID.fromString(result.getString("permit_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("nation_id")),
                    UUID.fromString(result.getString("ftb_team_id")),
                    UUID.fromString(result.getString("actor_player_id")),
                    result.getString("dimension_id"),
                    result.getInt("chunk_x"),
                    result.getInt("chunk_z"),
                    result.getLong("consumed_at_epoch_millis"));
        }
    }

    private StoredTerritoryClaimPermitCompensation readTerritoryClaimPermitCompensation(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? storedTerritoryClaimPermitCompensation(result) : null;
        }
    }

    private static StoredTerritoryClaimPermitCompensation
            storedTerritoryClaimPermitCompensation(ResultSet result) throws SQLException {
        String refundTransactionId = result.getString("refund_transaction_id");
        long completedAt = result.getLong("completed_at_epoch_millis");
        boolean completedAtWasNull = result.wasNull();
        return new StoredTerritoryClaimPermitCompensation(
                UUID.fromString(result.getString("compensation_id")),
                UUID.fromString(result.getString("permit_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                result.getString("actor_identity"),
                result.getString("kind"),
                result.getString("reason"),
                result.getString("refund_request_id"),
                refundTransactionId == null ? null : UUID.fromString(refundTransactionId),
                result.getLong("requested_at_epoch_millis"),
                completedAtWasNull ? null : completedAt);
    }

    private static StoredNationFiscalPermissionGrant storedNationFiscalPermissionGrant(
            ResultSet result) throws SQLException {
        return new StoredNationFiscalPermissionGrant(
                UUID.fromString(result.getString("grant_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("nation_id")),
                UUID.fromString(result.getString("actor_player_id")),
                UUID.fromString(result.getString("player_id")),
                result.getString("permission"),
                result.getString("reason"),
                result.getLong("granted_at_epoch_millis"));
    }

    private StoredNationFiscalPermissionRevocation readNationFiscalPermissionRevocation(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredNationFiscalPermissionRevocation(
                    UUID.fromString(result.getString("revocation_id")),
                    UUID.fromString(result.getString("grant_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("nation_id")),
                    UUID.fromString(result.getString("actor_player_id")),
                    result.getString("reason"),
                    result.getLong("revoked_at_epoch_millis"));
        }
    }

    private StoredNationApplication readNationApplication(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return storedNationApplication(result);
        }
    }

    private static StoredNationApplication storedNationApplication(ResultSet result)
            throws SQLException {
        return new StoredNationApplication(
                UUID.fromString(result.getString("application_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("ftb_team_id")),
                UUID.fromString(result.getString("applicant_player_id")),
                result.getLong("created_at_epoch_millis"),
                result.getLong("expires_at_epoch_millis"),
                result.getString("state"));
    }

    private StoredNationApplicationTransition readNationApplicationTransition(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredNationApplicationTransition(
                    UUID.fromString(result.getString("transition_id")),
                    UUID.fromString(result.getString("application_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getString("actor_player_id") == null
                            ? null
                            : UUID.fromString(result.getString("actor_player_id")),
                    result.getObject("observation_window_millis") == null
                            ? null
                            : result.getLong("observation_window_millis"),
                    result.getString("to_state"),
                    result.getString("reason"),
                    result.getLong("effective_at_epoch_millis"),
                    result.getLong("transitioned_at_epoch_millis"));
        }
    }

    private StoredCitizenshipCorrectionGrace readCitizenshipCorrectionGrace(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? readCitizenshipCorrectionGrace(result) : null;
        }
    }

    private static StoredCitizenshipCorrectionGrace readCitizenshipCorrectionGrace(
            ResultSet result) throws SQLException {
        return new StoredCitizenshipCorrectionGrace(
                UUID.fromString(result.getString("grace_id")),
                UUID.fromString(result.getString("citizenship_id")),
                UUID.fromString(result.getString("player_id")),
                UUID.fromString(result.getString("nation_id")),
                UUID.fromString(result.getString("ftb_team_id")),
                result.getString("start_service_identity"),
                result.getString("start_request_id"),
                result.getString("reason"),
                result.getLong("started_at_epoch_millis"),
                result.getLong("deadline_epoch_millis"),
                result.getString("resolution"),
                result.getString("resolution_service_identity"),
                result.getString("resolution_request_id"),
                result.getString("resolution_reason"),
                result.getObject("resolved_at_epoch_millis") == null
                        ? null
                        : result.getLong("resolved_at_epoch_millis"));
    }

    private StoredNationActivation readNationActivation(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredNationActivation(
                    UUID.fromString(result.getString("application_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("nation_id")),
                    UUID.fromString(result.getString("ftb_team_id")),
                    result.getString("treasury_account_id"),
                    result.getString("capital_dimension_id"),
                    result.getInt("capital_chunk_x"),
                    result.getInt("capital_chunk_z"),
                    result.getString("reason"),
                    result.getInt("minimum_effective_candidates"),
                    result.getInt("minimum_candidate_bypass_allowed") != 0,
                    result.getLong("observation_window_millis"),
                    result.getString("state"),
                    result.getLong("prepared_at_epoch_millis"),
                    result.getObject("treasury_provisioned_at_epoch_millis") == null
                            ? null
                            : result.getLong("treasury_provisioned_at_epoch_millis"),
                    result.getObject("committed_at_epoch_millis") == null
                            ? null
                            : result.getLong("committed_at_epoch_millis"));
        }
    }

    private StoredFiscalCapabilityRevocation readFiscalCapabilityRevocation(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredFiscalCapabilityRevocation(
                    UUID.fromString(result.getString("revocation_id")),
                    UUID.fromString(result.getString("grant_id")),
                    result.getString("administrator_identity"),
                    result.getString("request_id"),
                    result.getString("reason"),
                    result.getLong("revoked_at_epoch_millis"));
        }
    }

    private StoredFiscalServiceStateChange readFiscalServiceStateChange(
            PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredFiscalServiceStateChange(
                    UUID.fromString(result.getString("change_id")),
                    result.getString("administrator_identity"),
                    result.getString("request_id"),
                    result.getString("service_identity"),
                    result.getString("state"),
                    result.getString("reason"),
                    result.getLong("changed_at_epoch_millis"));
        }
    }

    private StoredReservation findReservation(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT reservation_id, service_identity, request_id, source_account,
                       amount_minor_units, settled_minor_units, purpose, state
                FROM fiscal_reservation
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredReservation(
                        UUID.fromString(result.getString("reservation_id")),
                        result.getString("service_identity"),
                        result.getString("request_id"),
                        result.getString("source_account"),
                        result.getLong("amount_minor_units"),
                        result.getLong("settled_minor_units"),
                        result.getString("purpose"),
                        result.getString("state"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Reservation", failure);
        }
    }

    private StoredReservationRelease readReservationRelease(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredReservationRelease(
                    UUID.fromString(result.getString("release_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("reservation_id")),
                    result.getString("reason"),
                    result.getLong("released_at_epoch_millis"));
        }
    }

    public synchronized StoredReservation reservationRecord(UUID reservationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT reservation_id, service_identity, request_id, source_account,
                       amount_minor_units, settled_minor_units, purpose, state
                FROM fiscal_reservation
                WHERE reservation_id = ?
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredReservation(
                        UUID.fromString(result.getString("reservation_id")),
                        result.getString("service_identity"),
                        result.getString("request_id"),
                        result.getString("source_account"),
                        result.getLong("amount_minor_units"),
                        result.getLong("settled_minor_units"),
                        result.getString("purpose"),
                        result.getString("state"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Reservation record", failure);
        }
    }

    private StoredReservation reservation(UUID reservationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT reservation_id, service_identity, request_id, source_account,
                       amount_minor_units, settled_minor_units, purpose, state
                FROM fiscal_reservation
                WHERE reservation_id = ? AND state = 'ACTIVE'
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredReservation(
                        UUID.fromString(result.getString("reservation_id")),
                        result.getString("service_identity"),
                        result.getString("request_id"),
                        result.getString("source_account"),
                        result.getLong("amount_minor_units"),
                        result.getLong("settled_minor_units"),
                        result.getString("purpose"),
                        result.getString("state"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Reservation", failure);
        }
    }

    private StoredReservation reservationForSettlement(UUID reservationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT reservation_id, service_identity, request_id, source_account,
                       amount_minor_units, settled_minor_units, purpose, state
                FROM fiscal_reservation WHERE reservation_id = ?
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredReservation(
                        UUID.fromString(result.getString("reservation_id")),
                        result.getString("service_identity"),
                        result.getString("request_id"),
                        result.getString("source_account"),
                        result.getLong("amount_minor_units"),
                        result.getLong("settled_minor_units"),
                        result.getString("purpose"),
                        result.getString("state"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read Territory settlement Reservation", failure);
        }
    }

    private int pendingTerritoryAssessmentCount(UUID cycleId, UUID nationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM territory_fiscal_assessment
                WHERE cycle_id = ? AND nation_id = ? AND validity = 'PENDING'
                """)) {
            query.setString(1, cycleId.toString());
            query.setString(2, nationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to count pending Territory assessments", failure);
        }
    }

    private boolean territoryMaintenanceOwnedBy(
            UUID cycleId, UUID nationId, String serviceIdentity, int pendingCount) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT
                    (SELECT service_identity FROM territory_maintenance_cycle
                     WHERE cycle_id = ?) AS cycle_service,
                    (SELECT COUNT(*) FROM territory_fiscal_assessment
                     WHERE cycle_id = ? AND nation_id = ? AND validity = 'PENDING'
                       AND service_identity = ?) AS owned_assessments
                """)) {
            query.setString(1, cycleId.toString());
            query.setString(2, cycleId.toString());
            query.setString(3, nationId.toString());
            query.setString(4, serviceIdentity);
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        && serviceIdentity.equals(result.getString("cycle_service"))
                        && result.getInt("owned_assessments") == pendingCount;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to verify Territory maintenance ownership", failure);
        }
    }

    private static void requireExactTerritorySettlementPartition(
            List<UUID> fundedAssessmentIds,
            List<UUID> suspendedAssessmentIds,
            List<UUID> expectedFunded,
            List<UUID> expectedSuspended) {
        if (fundedAssessmentIds == null || suspendedAssessmentIds == null) {
            throw new IllegalArgumentException(
                    "Territory maintenance Settlement partition cannot be null");
        }
        Set<UUID> provided = new HashSet<>();
        boolean unique = fundedAssessmentIds.stream().allMatch(provided::add)
                && suspendedAssessmentIds.stream().allMatch(provided::add);
        if (!unique
                || provided.size() != expectedFunded.size() + expectedSuspended.size()
                || !fundedAssessmentIds.equals(expectedFunded)
                || !suspendedAssessmentIds.equals(expectedSuspended)) {
            throw new SecurityException(
                    "Territory maintenance Settlement partition does not match persisted Priority");
        }
    }

    private static void persistTerritorySettlementAssessment(
            PreparedStatement detail,
            PreparedStatement update,
            UUID settlementId,
            UUID assessmentId,
            String validity) throws SQLException {
        detail.setString(1, settlementId.toString());
        detail.setString(2, assessmentId.toString());
        detail.setString(3, validity);
        detail.executeUpdate();
        update.setString(1, validity);
        update.setString(2, assessmentId.toString());
        if (update.executeUpdate() != 1) {
            throw new IllegalStateException(
                    "Territory maintenance assessments changed before settlement");
        }
    }

    private boolean hasIncompletePayment(UUID reservationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM payment_transaction
                WHERE reservation_id = ?
                  AND state IN ('PREPARED', 'EXTERNAL_APPLIED', 'COMPENSATING')
                LIMIT 1
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasIncompleteRefund(UUID originalTransactionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM payment_transaction
                WHERE parent_transaction_id = ?
                  AND kind = 'REFUND'
                  AND state IN ('PREPARED', 'EXTERNAL_APPLIED', 'COMPENSATING')
                LIMIT 1
                """)) {
            query.setString(1, originalTransactionId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private String reservationState(UUID reservationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT state FROM fiscal_reservation WHERE reservation_id = ?
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private String requiredRecipient(UUID reservationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT required_recipient_account FROM fiscal_escrow
                WHERE reservation_id = ?
                """)) {
            query.setString(1, reservationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read required payment recipient for Reservation " + reservationId,
                    failure);
        }
    }

    private StoredPaymentTransaction paymentTransaction(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM payment_transaction WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readPayment(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read payment", failure);
        }
    }

    public synchronized StoredPaymentTransaction paymentTransaction(UUID transactionId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM payment_transaction WHERE transaction_id = ?
                """)) {
            query.setString(1, transactionId.toString());
            return readPayment(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read payment transaction " + transactionId, failure);
        }
    }

    private StoredPaymentCompensation paymentCompensation(String serviceIdentity, String requestId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM payment_compensation
                WHERE service_identity = ? AND request_id = ?
                """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            return readPaymentCompensation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read payment compensation request", failure);
        }
    }

    public synchronized StoredPaymentCompensation paymentCompensation(UUID transactionId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM payment_compensation WHERE transaction_id = ?
                """)) {
            query.setString(1, transactionId.toString());
            return readPaymentCompensation(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read payment compensation " + transactionId, failure);
        }
    }

    private static StoredPaymentCompensation readPaymentCompensation(PreparedStatement query)
            throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredPaymentCompensation(
                    UUID.fromString(result.getString("compensation_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("transaction_id")),
                    result.getString("reason"),
                    result.getString("state"));
        }
    }

    private StoredPaymentTransaction readPayment(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? readPayment(result) : null;
        }
    }

    private StoredNation readNation(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return readNation(result);
        }
    }

    private static StoredNation readNation(ResultSet result) throws SQLException {
        return new StoredNation(
                UUID.fromString(result.getString("nation_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("ftb_team_id")),
                result.getLong("registered_at_epoch_millis"));
    }

    private StoredCitizenship citizenship(UUID citizenshipId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM citizenship_period WHERE citizenship_id = ?
                """)) {
            query.setString(1, citizenshipId.toString());
            return readCitizenship(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Citizenship " + citizenshipId, failure);
        }
    }

    private StoredCitizenship readCitizenship(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? readCitizenship(result) : null;
        }
    }

    private static StoredCitizenship readCitizenship(ResultSet result) throws SQLException {
        long endedAt = result.getLong("ended_at_epoch_millis");
        Long optionalEndedAt = result.wasNull() ? null : endedAt;
        return new StoredCitizenship(
                UUID.fromString(result.getString("citizenship_id")),
                UUID.fromString(result.getString("player_id")),
                UUID.fromString(result.getString("nation_id")),
                result.getLong("joined_at_epoch_millis"),
                optionalEndedAt,
                result.getString("join_service_identity"),
                result.getString("join_request_id"),
                result.getString("leave_service_identity"),
                result.getString("leave_request_id"));
    }

    private StoredOnlineInterval onlineTimeInterval(UUID intervalId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT * FROM online_time_interval WHERE interval_id = ?
                """)) {
            query.setString(1, intervalId.toString());
            return readOnlineInterval(query);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read online-time interval " + intervalId, failure);
        }
    }

    private StoredOnlineInterval readOnlineInterval(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            return result.next() ? readOnlineInterval(result) : null;
        }
    }

    private static StoredOnlineInterval readOnlineInterval(ResultSet result) throws SQLException {
        return new StoredOnlineInterval(
                UUID.fromString(result.getString("interval_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("player_id")),
                result.getLong("started_at_epoch_millis"),
                result.getLong("ended_at_epoch_millis"));
    }

    private static StoredEscrow readEscrow(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredEscrow(
                    UUID.fromString(result.getString("escrow_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("reservation_id")),
                    result.getString("source_account"),
                    result.getLong("amount_minor_units"),
                    result.getLong("settled_minor_units"),
                    result.getString("external_object_id"),
                    result.getString("purpose"),
                    result.getLong("expires_at_epoch_millis"),
                    result.getString("required_recipient_account"),
                    result.getString("state"));
        }
    }

    private static StoredBudget readBudget(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            String escrowId = result.getString("escrow_id");
            return new StoredBudget(
                    UUID.fromString(result.getString("budget_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getString("source_account"),
                    result.getLong("amount_minor_units"),
                    result.getString("budget_code"),
                    result.getString("purpose"),
                    result.getLong("expires_at_epoch_millis"),
                    escrowId == null ? null : UUID.fromString(escrowId),
                    result.getLong("settled_minor_units"),
                    result.getString("state"));
        }
    }

    private static StoredFiscalBill readFiscalBill(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            String escrowId = result.getString("escrow_id");
            return new StoredFiscalBill(
                    UUID.fromString(result.getString("bill_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    result.getString("payer_account"),
                    result.getString("beneficiary_account"),
                    result.getLong("amount_minor_units"),
                    result.getString("kind"),
                    result.getString("purpose"),
                    result.getLong("due_at_epoch_millis"),
                    escrowId == null ? null : UUID.fromString(escrowId),
                    result.getLong("settled_minor_units"),
                    result.getString("state"));
        }
    }

    private static StoredEscrowExpiry readEscrowExpiry(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new StoredEscrowExpiry(
                    UUID.fromString(result.getString("expiry_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("escrow_id")),
                    result.getLong("expired_at_epoch_millis"));
        }
    }

    private static StoredPaymentTransaction readPayment(ResultSet result) throws SQLException {
        String parent = result.getString("parent_transaction_id");
        return new StoredPaymentTransaction(
                UUID.fromString(result.getString("transaction_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("reservation_id")),
                result.getString("source_account"),
                result.getString("recipient_account"),
                result.getLong("amount_minor_units"),
                result.getString("kind"),
                parent == null ? null : UUID.fromString(parent),
                result.getLong("refunded_minor_units"),
                result.getString("reason"),
                result.getString("state"));
    }

    public static void validateBackup(Path backupFile, DatabaseIdentity expectedIdentity) {
        loadSqliteDriver();
        String readOnlyUrl = "jdbc:sqlite:" + backupFile.toUri() + "?mode=ro";
        try (Connection validation = DriverManager.getConnection(readOnlyUrl);
                Statement statement = validation.createStatement()) {
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.getInt(1);
            }
            if (version != SCHEMA_VERSION) {
                throw new UnsupportedDatabaseVersionException(version, SCHEMA_VERSION);
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT * FROM civic_identity WHERE singleton = 1")) {
                if (!result.next()) {
                    throw new IllegalStateException("Civic backup identity is missing");
                }
                DatabaseIdentity actual = new DatabaseIdentity(
                        UUID.fromString(result.getString("world_id")),
                        result.getString("civic_version"),
                        result.getString("lc_version"),
                        result.getString("ftb_teams_version"),
                        result.getString("ftb_chunks_version"));
                if (!actual.equals(expectedIdentity)) {
                    throw new DatabaseIdentityMismatchException(expectedIdentity, actual);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to validate Civic backup " + backupFile, failure);
        }
    }

    private void insertRecoveryAudit(
            UUID transactionId,
            String action,
            String serviceIdentity,
            String detail,
            long recordedAtEpochMillis) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO payment_recovery_audit (
                    audit_id, transaction_id, action, service_identity,
                    detail, recorded_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, transactionId.toString());
            insert.setString(3, action);
            insert.setString(4, serviceIdentity);
            insert.setString(5, detail);
            insert.setLong(6, recordedAtEpochMillis);
            insert.executeUpdate();
        }
    }

    private void insertLedgerEntries(
            StoredPaymentTransaction transaction, long recordedAtEpochMillis) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO fiscal_ledger_entry (
                    entry_id, transaction_id, account_id, counterparty_account_id,
                    amount_minor_units, direction, transaction_kind,
                    recorded_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, transaction.transactionId().toString());
            insert.setString(3, transaction.sourceAccount());
            insert.setString(4, transaction.recipientAccount());
            insert.setLong(5, transaction.amountMinorUnits());
            insert.setString(6, "OUTFLOW");
            insert.setString(7, transaction.kind());
            insert.setLong(8, recordedAtEpochMillis);
            insert.executeUpdate();

            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(3, transaction.recipientAccount());
            insert.setString(4, transaction.sourceAccount());
            insert.setString(6, "INFLOW");
            insert.executeUpdate();
        }
    }

    private void updateTransactionState(UUID transactionId, String expected, String next) {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE payment_transaction SET state = ? WHERE transaction_id = ? AND state = ?
                """)) {
            update.setString(1, next);
            update.setString(2, transactionId.toString());
            update.setString(3, expected);
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to update payment state", failure);
        }
    }

    private String queryText(String sql) {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to query Civic database", failure);
        }
    }

    private int queryInteger(String sql) {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.getInt(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to query Civic database", failure);
        }
    }
}
