package org.civiceconomy.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public final class CivicDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 5;

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
                    reservationId, serviceIdentity, requestId, sourceAccount, amountMinorUnits, purpose);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to create Reservation", failure);
        }
    }

    public synchronized StoredReservation reservation(String serviceIdentity, String requestId) {
        return findReservation(serviceIdentity, requestId);
    }

    public synchronized long activeReservedMinorUnits(String sourceAccount) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount_minor_units), 0)
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
        if (reservation.amountMinorUnits() != amountMinorUnits) {
            throw new IllegalArgumentException("This settlement must consume the full Reservation");
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

    public synchronized void markExternalApplied(UUID transactionId) {
        updateTransactionState(transactionId, "PREPARED", "EXTERNAL_APPLIED");
    }

    public synchronized void commitPayment(UUID transactionId, UUID reservationId) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement settle = connection.prepareStatement("""
                        UPDATE fiscal_reservation SET state = 'SETTLED'
                        WHERE reservation_id = ? AND state = 'ACTIVE'
                        """);
                    PreparedStatement commit = connection.prepareStatement("""
                        UPDATE payment_transaction SET state = 'CIVIC_COMMITTED'
                        WHERE transaction_id = ? AND state = 'EXTERNAL_APPLIED'
                        """)) {
                settle.setString(1, reservationId.toString());
                settle.executeUpdate();
                commit.setString(1, transactionId.toString());
                commit.executeUpdate();
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
                    WHERE state IN ('PREPARED', 'EXTERNAL_APPLIED')
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
                SELECT reservation_id, service_identity, request_id, source_account, amount_minor_units, purpose
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
                        result.getString("purpose"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Reservation", failure);
        }
    }

    private StoredReservation reservation(UUID reservationId) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT reservation_id, service_identity, request_id, source_account, amount_minor_units, purpose
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
                        result.getString("purpose"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Reservation", failure);
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

    private static StoredPaymentTransaction readPayment(ResultSet result) throws SQLException {
        return new StoredPaymentTransaction(
                UUID.fromString(result.getString("transaction_id")),
                result.getString("service_identity"),
                result.getString("request_id"),
                UUID.fromString(result.getString("reservation_id")),
                result.getString("source_account"),
                result.getString("recipient_account"),
                result.getLong("amount_minor_units"),
                result.getString("state"));
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
