package com.auraboot.framework.permission.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The claim the memo rests on, checked against a real PostgreSQL and Spring's own transaction
 * manager. Without Docker this class is skipped; {@link SnapshotTransactionMemoGivenPostgresTest}
 * runs the same proofs against a database named by the environment.
 */
@Testcontainers(disabledWithoutDocker = true)
class SnapshotTransactionMemoPostgresTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static DataSource dataSource() {
        return new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
