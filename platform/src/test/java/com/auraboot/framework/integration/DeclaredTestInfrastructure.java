package com.auraboot.framework.integration;

import java.net.URI;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies real test connections against the namespace selected by the runner. */
public final class DeclaredTestInfrastructure {
    private DeclaredTestInfrastructure() {}

    public static void assertSelected(DataSource dataSource, RedisProperties redis) throws Exception {
        assertSelected(dataSource, redis, System.getenv());
    }

    static void assertSelected(DataSource dataSource, RedisProperties redis, Map<String, String> environment) throws Exception {
        String expectedDatabase = environment.get("TEST_EXPECTED_DATABASE");
        String jdbcUrl = environment.get("TEST_DATABASE_URL");
        String expectedRedis = environment.get("TEST_REDIS_DATABASE");
        assertThat(expectedDatabase).as("runner-selected PostgreSQL database")
                .isNotBlank().matches("[a-z][a-z0-9_]*");
        assertThat(jdbcUrl).as("runner-selected JDBC URL").startsWith("jdbc:postgresql://");
        URI declaredJdbc = URI.create(jdbcUrl.substring(5));
        assertThat(declaredJdbc.getPath()).isEqualTo("/" + expectedDatabase);
        assertThat(expectedRedis).as("runner-selected Redis database").isNotBlank().matches("[0-9]+");
        assertThat(redis.getDatabase()).isEqualTo(Integer.parseInt(expectedRedis));
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo(expectedDatabase);
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql://");
            URI actualJdbc = URI.create(connection.getMetaData().getURL().substring(5));
            assertThat(actualJdbc.getAuthority()).isEqualTo(declaredJdbc.getAuthority());
            assertThat(actualJdbc.getPath()).isEqualTo("/" + expectedDatabase);
        }
    }
}
