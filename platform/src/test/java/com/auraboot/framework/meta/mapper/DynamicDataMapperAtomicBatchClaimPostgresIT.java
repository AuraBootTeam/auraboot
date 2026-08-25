package com.auraboot.framework.meta.mapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL proof for SKIP LOCKED lease-claim concurrency semantics. */
@EnabledIfEnvironmentVariable(named = "CRAWLER_REAL_POSTGRES_URL", matches = ".+")
class DynamicDataMapperAtomicBatchClaimPostgresIT {

    private DataSource dataSource;
    private SqlSessionFactory sessions;
    private final String tableName = "mt_background_claim_it_"
            + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(System.getenv("CRAWLER_REAL_POSTGRES_URL"));
        postgres.setUser(System.getenv().getOrDefault("CRAWLER_REAL_POSTGRES_USER", "postgres"));
        String password = System.getenv("CRAWLER_REAL_POSTGRES_PASSWORD");
        if (password != null) postgres.setPassword(password);
        dataSource = postgres;

        Environment environment = new Environment(
                "background-claim-postgres-it", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(DynamicDataMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE %s (
                      pid varchar(32) PRIMARY KEY,
                      tenant_id bigint NOT NULL,
                      lane_no integer NOT NULL,
                      status_code varchar(20) NOT NULL,
                      due_at timestamptz NOT NULL,
                      leased_until timestamptz,
                      row_version bigint NOT NULL DEFAULT 0,
                      updated_at timestamptz NOT NULL DEFAULT now(),
                      updated_by bigint NOT NULL DEFAULT 0
                    )
                    """.formatted(tableName));
            statement.execute("""
                    INSERT INTO %s
                      (pid, tenant_id, lane_no, status_code, due_at)
                    VALUES
                      ('p1', 7, 1, 'pending', '2026-08-24T00:00:00Z'),
                      ('p2', 7, 1, 'retry',   '2026-08-24T00:00:01Z'),
                      ('p3', 7, 1, 'pending', '2026-08-24T00:00:02Z'),
                      ('p4', 7, 1, 'pending', '2026-08-24T00:00:03Z'),
                      ('other-tenant', 8, 1, 'pending', '2026-08-24T00:00:00Z')
                    """.formatted(tableName));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource == null) return;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + tableName);
        }
    }

    @Test
    void twoOpenTransactionsClaimDisjointRowsWithoutWaiting() throws Exception {
        Instant due = Instant.parse("2026-08-24T00:01:00Z");
        Instant lease = Instant.parse("2026-08-24T00:03:00Z");
        Map<String, Object> claimValues = new LinkedHashMap<>();
        claimValues.put("status_code", "publishing");
        claimValues.put("leased_until", Timestamp.from(lease));

        try (SqlSession firstSession = sessions.openSession(false);
             SqlSession secondSession = sessions.openSession(false)) {
            DynamicDataMapper firstMapper = firstSession.getMapper(DynamicDataMapper.class);
            DynamicDataMapper secondMapper = secondSession.getMapper(DynamicDataMapper.class);

            List<Map<String, Object>> first = claim(firstMapper, due, claimValues);
            // The first transaction remains uncommitted and keeps its two row locks. The second
            // statement must skip those locks and immediately claim the remaining eligible rows.
            List<Map<String, Object>> second = claim(secondMapper, due, claimValues);

            Set<String> firstPids = pids(first);
            Set<String> secondPids = pids(second);
            assertThat(firstPids).hasSize(2);
            assertThat(secondPids).hasSize(2);
            assertThat(firstPids).doesNotContainAnyElementsOf(secondPids);

            firstSession.commit();
            secondSession.commit();
        }

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("""
                    SELECT count(*) AS claimed_count,
                           min(row_version) AS min_version,
                           min(updated_by) AS min_actor
                    FROM %s
                    WHERE tenant_id = 7 AND status_code = 'publishing'
                    """.formatted(tableName))) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("claimed_count")).isEqualTo(4);
                assertThat(rows.getLong("min_version")).isEqualTo(1L);
                assertThat(rows.getLong("min_actor")).isEqualTo(0L);
            }
            try (ResultSet rows = statement.executeQuery("""
                    SELECT status_code FROM %s WHERE pid = 'other-tenant'
                    """.formatted(tableName))) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("pending");
            }
        }
    }

    private List<Map<String, Object>> claim(DynamicDataMapper mapper,
                                            Instant due,
                                            Map<String, Object> claimValues) {
        return mapper.atomicBatchClaimReturning(
                tableName,
                "pid",
                Map.of("lane_no", 1),
                Map.of("status_code", List.of("pending", "retry", "publishing")),
                Map.of("due_at", Timestamp.from(due)),
                claimValues,
                List.of("due_at"),
                false,
                2,
                7L,
                0L);
    }

    private static Set<String> pids(List<Map<String, Object>> rows) {
        Set<String> result = new HashSet<>();
        rows.forEach(row -> result.add(String.valueOf(row.get("pid"))));
        return result;
    }
}
