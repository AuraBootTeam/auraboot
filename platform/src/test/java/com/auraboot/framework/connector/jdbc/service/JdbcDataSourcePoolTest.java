package com.auraboot.framework.connector.jdbc.service;

import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcDataSourcePoolTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    private final JdbcDataSourcePool pool = new JdbcDataSourcePool();

    @AfterEach
    void cleanup() {
        pool.shutdown();
    }

    private JdbcConnector buildConnector(String pid) {
        JdbcConnector c = new JdbcConnector();
        c.setId(1L);
        c.setPid(pid);
        c.setName("test-connector");
        c.setJdbcUrl(mysql.getJdbcUrl());
        c.setUsername(mysql.getUsername());
        c.setPassword(mysql.getPassword());
        c.setMaxPoolSize(2);
        c.setConnectionTimeoutMs(5000);
        c.setEnabled(true);
        return c;
    }

    @Test
    void acquire_returnsWorkingDataSource() throws Exception {
        JdbcConnector connector = buildConnector("pid-working");
        HikariDataSource ds = pool.acquire(connector);

        assertThat(ds).isNotNull();
        assertThat(ds.getConnection().isValid(2)).isTrue();
    }

    @Test
    void acquire_caches() {
        JdbcConnector connector = buildConnector("pid-cache");
        HikariDataSource first = pool.acquire(connector);
        HikariDataSource second = pool.acquire(connector);

        assertThat(first).isSameAs(second);
    }

    @Test
    void evict_releasesPool() throws Exception {
        JdbcConnector connector = buildConnector("pid-evict");
        HikariDataSource ds = pool.acquire(connector);

        pool.evict("pid-evict");

        // The map no longer contains the pid
        // Verify by acquiring again — should be a NEW (different) instance
        HikariDataSource ds2 = pool.acquire(connector);
        assertThat(ds2).isNotSameAs(ds);
        // The original datasource should be closed
        assertThat(ds.isClosed()).isTrue();
    }

    @Test
    void acquire_badCredentialsFailFast() {
        JdbcConnector bad = new JdbcConnector();
        bad.setPid("pid-bad-creds");
        bad.setName("bad");
        bad.setJdbcUrl(mysql.getJdbcUrl());
        bad.setUsername("wrong_user");
        bad.setPassword("wrong_pass");
        bad.setMaxPoolSize(1);
        bad.setConnectionTimeoutMs(3000);

        // MySQL HikariCP fail-fast: either acquire() itself throws (PoolInitializationException
        // wrapping SQLException), or getConnection() throws — either way a SQLException-caused
        // exception must surface. We assert both paths in one assertThatThrownBy block.
        assertThatThrownBy(() -> {
            HikariDataSource ds = pool.acquire(bad);
            ds.getConnection();
        }).satisfies(ex -> {
            // Root or nearest cause must be SQLException
            Throwable t = ex;
            boolean hasSql = false;
            while (t != null) {
                if (t instanceof SQLException) {
                    hasSql = true;
                    break;
                }
                t = t.getCause();
            }
            assertThat(hasSql)
                    .as("Expected a SQLException in the cause chain, got: %s", ex)
                    .isTrue();
        });
    }
}
