package com.auraboot.framework.permission.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The same two proofs as {@link SnapshotTransactionMemoPostgresTest}, for a machine that has a
 * PostgreSQL but no Docker. It runs only when the environment names a database to use, and it
 * creates and drops a table {@code memo_probe} there, so that database must be a throwaway one:
 *
 * <pre>
 * SNAPSHOT_MEMO_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5433/postgres
 * SNAPSHOT_MEMO_TEST_USER=… SNAPSHOT_MEMO_TEST_PASSWORD=…
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SNAPSHOT_MEMO_TEST_JDBC_URL", matches = ".+")
class SnapshotTransactionMemoGivenPostgresTest {

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                System.getenv("SNAPSHOT_MEMO_TEST_JDBC_URL"),
                System.getenv("SNAPSHOT_MEMO_TEST_USER"),
                System.getenv("SNAPSHOT_MEMO_TEST_PASSWORD"));
    }

    @Test
    void insideAReadOnlySnapshotTransactionTheFirstAnswerStaysWhatADirectReadReturns() throws Exception {
        SnapshotTransactionMemoPostgresProbe.readOnlySnapshotTransactionReusesExactlyWhatAReadReturns(
                dataSource(), new Object(), new AtomicInteger());
    }

    @Test
    void aWritableTransactionIsNotAnsweredFromAnEarlierRead() throws Exception {
        SnapshotTransactionMemoPostgresProbe.writableTransactionIsNotAnsweredFromAnEarlierRead(
                dataSource(), new Object(), new AtomicInteger());
    }
}
