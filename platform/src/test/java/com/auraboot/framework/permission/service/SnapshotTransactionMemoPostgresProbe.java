package com.auraboot.framework.permission.service;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two proofs about {@link SnapshotTransactionMemo} against a real PostgreSQL, through Spring's own
 * transaction manager. Shared by the test that starts a container and the test that takes a database
 * from the environment.
 */
final class SnapshotTransactionMemoPostgresProbe {

    private SnapshotTransactionMemoPostgresProbe() {
    }

    /** A transaction as Spring runs it: read-only ones at repeatable read, the others as configured. */
    static TransactionTemplate transaction(DataSource dataSource, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setReadOnly(readOnly);
        if (readOnly) {
            template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
        return template;
    }

    static void createProbe(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS memo_probe");
            statement.execute("CREATE TABLE memo_probe (id integer PRIMARY KEY, granted boolean NOT NULL)");
            statement.execute("INSERT INTO memo_probe (id, granted) VALUES (1, true)");
        }
    }

    /** Reads the grant through the connection of the current thread's transaction, if it has one. */
    static boolean granted(DataSource dataSource) throws SQLException {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT granted FROM memo_probe WHERE id = 1");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).as("the probe row exists").isTrue();
            return rows.getBoolean(1);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** One lookup through the memo; counts how often the loader really ran. */
    static boolean throughMemo(DataSource dataSource, Object key, AtomicInteger lookups) {
        Boolean answer = SnapshotTransactionMemo.get(key, () -> {
            lookups.incrementAndGet();
            try {
                return granted(dataSource);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        return Boolean.TRUE.equals(answer);
    }

    /** Commits a revocation from another thread: another transaction, with a connection of its own. */
    static void revokeFromAnotherTransaction(DataSource dataSource) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread revoker = new Thread(() -> {
            try {
                transaction(dataSource, false).executeWithoutResult(status -> {
                    // The connection of this thread's own transaction, the way Spring's JDBC support obtains it.
                    Connection connection = DataSourceUtils.getConnection(dataSource);
                    try (Statement statement = connection.createStatement()) {
                        assertThat(statement.executeUpdate("UPDATE memo_probe SET granted = false WHERE id = 1"))
                                .isEqualTo(1);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        DataSourceUtils.releaseConnection(connection, dataSource);
                    }
                });
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "memo-probe-revoker");
        revoker.start();
        revoker.join();
        if (failure.get() != null) {
            throw new IllegalStateException("the revoking transaction failed", failure.get());
        }
    }

    /**
     * Inside a read-only snapshot transaction the reused answer is what a repeated lookup returns,
     * even while another transaction commits a change; the next transaction sees that change.
     */
    static void readOnlySnapshotTransactionReusesExactlyWhatAReadReturns(
            DataSource dataSource, Object key, AtomicInteger lookups) throws Exception {
        createProbe(dataSource);
        TransactionTemplate readOnlySnapshot = transaction(dataSource, true);

        readOnlySnapshot.executeWithoutResult(status -> {
            try {
                assertThat(throughMemo(dataSource, key, lookups)).isTrue();
                assertThat(SnapshotTransactionMemo.isActive())
                        .as("Spring binds read-only and the isolation level for this transaction").isTrue();

                revokeFromAnotherTransaction(dataSource);

                boolean reused = throughMemo(dataSource, key, lookups);
                assertThat(reused)
                        .as("the reused answer is what a repeated lookup returns in this transaction")
                        .isEqualTo(granted(dataSource));
                assertThat(lookups).hasValue(1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        boolean afterEnd = readOnlySnapshot.execute(status -> throughMemo(dataSource, key, lookups));
        assertThat(afterEnd).as("the next transaction sees the revocation").isFalse();
        assertThat(lookups).hasValue(2);
    }

    /** A writable transaction (read committed) is not answered from an earlier read. */
    static void writableTransactionIsNotAnsweredFromAnEarlierRead(
            DataSource dataSource, Object key, AtomicInteger lookups) throws Exception {
        createProbe(dataSource);
        TransactionTemplate writable = transaction(dataSource, false);

        writable.executeWithoutResult(status -> {
            try {
                assertThat(throughMemo(dataSource, key, lookups)).isTrue();
                assertThat(SnapshotTransactionMemo.isActive()).isFalse();

                revokeFromAnotherTransaction(dataSource);

                assertThat(throughMemo(dataSource, key, lookups))
                        .as("read committed sees the revocation: nothing was reused").isFalse();
                assertThat(lookups).hasValue(2);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
