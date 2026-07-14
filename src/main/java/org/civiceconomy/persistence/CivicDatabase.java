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
    private static final int SCHEMA_VERSION = 11;

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
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
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
            return new StoredNation(
                    UUID.fromString(result.getString("nation_id")),
                    result.getString("service_identity"),
                    result.getString("request_id"),
                    UUID.fromString(result.getString("ftb_team_id")),
                    result.getLong("registered_at_epoch_millis"));
        }
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
