package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real PostgreSQL proof that current and outbox writes share one host transaction. */
@EnabledIfEnvironmentVariable(named = "CRAWLER_REAL_POSTGRES_URL", matches = ".+")
class BackgroundDataAccessorTransactionPostgresIT {

    private final String tableName = "mt_background_tx_it_"
            + UUID.randomUUID().toString().replace("-", "");
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private BackgroundDataAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(System.getenv("CRAWLER_REAL_POSTGRES_URL"));
        postgres.setUser(System.getenv().getOrDefault("CRAWLER_REAL_POSTGRES_USER", "postgres"));
        String password = System.getenv("CRAWLER_REAL_POSTGRES_PASSWORD");
        if (password != null) postgres.setPassword(password);
        dataSource = postgres;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE " + tableName + " (kind varchar(32) PRIMARY KEY)");

        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        when(dynamicDataService.create(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> data = invocation.getArgument(1);
            jdbc.update("INSERT INTO " + tableName + " (kind) VALUES (?)", data.get("kind"));
            return data;
        });
        accessor = new BackgroundDataAccessorImpl(
                dynamicDataService,
                null,
                dataSource,
                new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) jdbc.execute("DROP TABLE IF EXISTS " + tableName);
    }

    @Test
    void failureAfterOutboxWriteRollsBackCurrentAndOutboxTogether() {
        assertThatThrownBy(() -> accessor.executeInTransaction(() -> {
            accessor.create(7L, "current_model", Map.of("kind", "current"));
            accessor.create(7L, "outbox_model", Map.of("kind", "outbox"));
            throw new IllegalStateException("crash after both writes");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tableName, Integer.class))
                .isZero();

        accessor.executeInTransaction(() -> {
            accessor.create(7L, "current_model", Map.of("kind", "current"));
            accessor.create(7L, "outbox_model", Map.of("kind", "outbox"));
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tableName, Integer.class))
                .isEqualTo(2);
    }
}
