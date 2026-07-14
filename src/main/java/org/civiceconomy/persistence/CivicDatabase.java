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
import java.util.List;
import java.util.UUID;
import org.sqlite.SQLiteConnection;

public final class CivicDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 24;

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

    private static void validateBackup(Path backupFile, DatabaseIdentity expectedIdentity) {
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
