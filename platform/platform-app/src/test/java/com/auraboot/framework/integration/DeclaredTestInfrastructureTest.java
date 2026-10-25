package com.auraboot.framework.integration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeclaredTestInfrastructureTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final RedisProperties redis = new RedisProperties();
    private final Map<String, String> environment = new HashMap<>(Map.of(
            "TEST_EXPECTED_DATABASE", "isolated_ci",
            "TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:25432/isolated_ci?charSet=UTF8",
            "TEST_REDIS_DATABASE", "0"));

    private void actualConnection() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getCatalog()).thenReturn("isolated_ci");
        when(metadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:25432/isolated_ci");
    }

    @Test void acceptsRunnerDeclaredNamespaceAndClosesConnection() throws Exception {
        actualConnection();
        DeclaredTestInfrastructure.assertSelected(source, redis, environment);
        verify(connection).close();
    }

    @Test void rejectsForeignActualCatalogEvenWhenEnvironmentMatches() throws Exception {
        actualConnection(); when(connection.getCatalog()).thenReturn("another_task");
        assertThatThrownBy(() -> DeclaredTestInfrastructure.assertSelected(source, redis, environment))
                .isInstanceOf(AssertionError.class).hasMessageContaining("another_task");
        verify(connection).close();
    }

    @Test void rejectsForeignActualJdbcPathEvenWhenCatalogMatches() throws Exception {
        actualConnection(); when(metadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:25432/another_task");
        assertThatThrownBy(() -> DeclaredTestInfrastructure.assertSelected(source, redis, environment))
                .isInstanceOf(AssertionError.class).hasMessageContaining("another_task");
        verify(connection).close();
    }

    @Test void rejectsForeignEndpointEvenWhenDatabaseMatches() throws Exception {
        actualConnection(); when(metadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:25433/isolated_ci");
        assertThatThrownBy(() -> DeclaredTestInfrastructure.assertSelected(source, redis, environment))
                .isInstanceOf(AssertionError.class).hasMessageContaining("25433");
        verify(connection).close();
    }

    @Test void rejectsConfiguredRedisDatabaseDrift() {
        redis.setDatabase(13);
        assertThatThrownBy(() -> DeclaredTestInfrastructure.assertSelected(source, redis, environment))
                .isInstanceOf(AssertionError.class);
        verifyNoInteractions(source);
    }

    @Test void rejectsMissingRunnerDatabaseDeclarationInsteadOfInferringIt() {
        environment.remove("TEST_EXPECTED_DATABASE");
        assertThatThrownBy(() -> DeclaredTestInfrastructure.assertSelected(source, redis, environment))
                .isInstanceOf(AssertionError.class);
        verifyNoInteractions(source);
    }

    @Test void rejectsDeclaredJdbcPathDriftBeforeConnecting() {
        environment.put("TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:25432/another_task");
        assertThatThrownBy(() -> DeclaredTestInfrastructure.assertSelected(source, redis, environment))
                .isInstanceOf(AssertionError.class).hasMessageContaining("another_task");
        verifyNoInteractions(source);
    }

    @Test void rejectsMissingRedisDeclarationInsteadOfDefaulting() {
        environment.remove("TEST_REDIS_DATABASE");
        assertThatThrownBy(() -> DeclaredTestInfrastructure.assertSelected(source, redis, environment))
                .isInstanceOf(AssertionError.class);
        verifyNoInteractions(source);
    }
}
