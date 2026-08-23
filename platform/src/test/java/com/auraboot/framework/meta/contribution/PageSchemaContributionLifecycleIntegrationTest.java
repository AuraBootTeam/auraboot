package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.contribution.mapper.PageSchemaContributionMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PageSchemaContributionLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void createSchemaFromMigration() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ab_environment (id BIGINT PRIMARY KEY);
                    CREATE TABLE ab_plugin (
                        pid VARCHAR(26) PRIMARY KEY,
                        tenant_id BIGINT NOT NULL,
                        plugin_id VARCHAR(128) NOT NULL,
                        namespace VARCHAR(64) NOT NULL,
                        version VARCHAR(32) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        manifest JSONB NOT NULL,
                        deleted_flag BOOLEAN NOT NULL DEFAULT FALSE
                    );
                    INSERT INTO ab_environment (id) VALUES (73);
                    """);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/core/V20260824024000__page_schema_contributions.sql"));
        }
        org.apache.ibatis.datasource.pooled.PooledDataSource dataSource =
                new org.apache.ibatis.datasource.pooled.PooledDataSource(
                        "org.postgresql.Driver", POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(), POSTGRES.getPassword());
        Configuration configuration = new Configuration(new Environment(
                "page-contribution-it", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PageSchemaContributionMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void activeLookupUsesStablePluginIdAndHidesDisabledOrDeletedInstallations() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime());
        String stablePluginId = "com.auraboot.test.sales." + suffix;
        String firstPid = UniqueIdGenerator.generate();
        String secondPid = UniqueIdGenerator.generate();
        try (Connection connection = connection()) {
            insertPlugin(connection, firstPid, stablePluginId, "sales_a_" + suffix);
            insertPlugin(connection, secondPid, stablePluginId, "sales_b_" + suffix);
            insertContribution(connection, firstPid, "first", 20);
            insertContribution(connection, secondPid, "second", 10);
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            PageSchemaContributionMapper mapper = session.getMapper(PageSchemaContributionMapper.class);
            List<PersistedPageSchemaContribution> active =
                    mapper.findActiveForPage(41L, 73L, "crm-opportunity-detail");

            assertThat(active).extracting(PersistedPageSchemaContribution::getContributionId)
                    .containsExactly("first", "second");
            assertThat(active).extracting(PersistedPageSchemaContribution::getContributorId)
                    .containsOnly(stablePluginId);
            assertThat(active).extracting(PersistedPageSchemaContribution::getPayload)
                    .allSatisfy(payload -> assertThat(payload).containsKey("code"));

            updatePlugin(session.getConnection(), firstPid, "status = 'disabled'");
            assertThat(mapper.findActiveForPage(41L, 73L, "crm-opportunity-detail"))
                    .extracting(PersistedPageSchemaContribution::getContributionId)
                    .containsExactly("second");

            updatePlugin(session.getConnection(), secondPid, "deleted_flag = TRUE");
            assertThat(mapper.findActiveForPage(41L, 73L, "crm-opportunity-detail")).isEmpty();
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertPlugin(Connection connection, String pid, String pluginId, String namespace)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ab_plugin
                    (pid, tenant_id, plugin_id, namespace, version, status, manifest, deleted_flag)
                VALUES (?, 41, ?, ?, '1.0.0', 'enabled', '{}'::jsonb, FALSE)
                """)) {
            statement.setString(1, pid);
            statement.setString(2, pluginId);
            statement.setString(3, namespace);
            statement.executeUpdate();
        }
    }

    private void insertContribution(Connection connection, String pluginPid,
                                    String contributionId, int priority) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ab_page_schema_contribution
                    (pid, tenant_id, env_id, plugin_pid, plugin_version, contribution_id,
                     target_page_key, slot_id, kind, priority, payload, active, deleted_flag)
                VALUES (?, 41, 73, ?, '1.0.0', ?, 'crm-opportunity-detail', 'product-actions',
                        'action', ?, jsonb_build_object('code', ?), TRUE, FALSE)
                """)) {
            statement.setString(1, UniqueIdGenerator.generate());
            statement.setString(2, pluginPid);
            statement.setString(3, contributionId);
            statement.setInt(4, priority);
            statement.setString(5, "action_" + contributionId);
            statement.executeUpdate();
        }
    }

    private void updatePlugin(Connection connection, String pluginPid, String assignment)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ab_plugin SET " + assignment + " WHERE pid = ?")) {
            statement.setString(1, pluginPid);
            statement.executeUpdate();
        }
    }
}
