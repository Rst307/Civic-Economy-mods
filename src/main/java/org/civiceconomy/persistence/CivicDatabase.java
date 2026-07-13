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
    private static final int SCHEMA_VERSION = 3;

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
