package com.auraboot.framework.application.release;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL proof for the release, explicit-tenant and JSONB mapper contract. */
@EnabledIfEnvironmentVariable(named = "CRAWLER_REAL_POSTGRES_URL", matches = ".+")
class ApplicationDefinitionMapperPostgresIT {
    private static final String RELEASE_ID = "01K60000000000000000000000";
    private static final String LOCK_IDENTITY = "sha256:" + "a".repeat(64);
    private static final String RELEASE_DIGEST = "sha256:" + "b".repeat(64);
    private static final String COMPONENT_DIGEST = "sha256:" + "c".repeat(64);

    private final String schema = "definition_resolver_it_" + UUID.randomUUID().toString().replace("-", "");
    private DataSource admin;
    private SqlSessionFactory sessions;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource postgres = postgres();
        admin = postgres;
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        postgres.setCurrentSchema(schema);

        Environment environment = new Environment("application-definition-postgres-it",
                new JdbcTransactionFactory(), postgres);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ApplicationDefinitionMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ab_application (id bigint PRIMARY KEY, code text NOT NULL)");
            statement.execute("CREATE TABLE ab_application_release (release_id varchar(26) PRIMARY KEY, application_id bigint NOT NULL, digest text NOT NULL, source_lock_identity text NOT NULL)");
            statement.execute("CREATE TABLE ab_tenant_application_binding (tenant_id bigint NOT NULL, application_id bigint NOT NULL, current_release_id varchar(26) NOT NULL, status text NOT NULL, binding_version bigint NOT NULL)");
            statement.execute("CREATE TABLE ab_application_channel_target (application_id bigint NOT NULL, channel text NOT NULL, release_id varchar(26) NOT NULL)");
            statement.execute("CREATE TABLE ab_application_release_publication (application_id bigint NOT NULL, release_id varchar(26) NOT NULL)");
            statement.execute("CREATE TABLE ab_application_release_component (release_id varchar(26) NOT NULL, component_key text NOT NULL, component_type text NOT NULL, component_version text NOT NULL, component_digest text NOT NULL)");
            statement.execute("CREATE TABLE ab_plugin (pid varchar(26) PRIMARY KEY, tenant_id bigint NOT NULL, plugin_id text NOT NULL, version text NOT NULL, deleted_flag boolean NOT NULL)");
            statement.execute("CREATE TABLE ab_plugin_resource (pid varchar(26) PRIMARY KEY, tenant_id bigint NOT NULL, plugin_pid varchar(26) NOT NULL, resource_type text NOT NULL, resource_code text NOT NULL, import_snapshot jsonb, user_modified boolean NOT NULL)");
            statement.execute("INSERT INTO ab_application VALUES (9, 'aura-edu')");
            statement.execute("INSERT INTO ab_application_release VALUES ('" + RELEASE_ID + "', 9, '" + RELEASE_DIGEST + "', '" + LOCK_IDENTITY + "')");
            statement.execute("INSERT INTO ab_tenant_application_binding VALUES (42, 9, '" + RELEASE_ID + "', 'shadow', 3)");
            statement.execute("INSERT INTO ab_application_channel_target VALUES (9, 'stable', '" + RELEASE_ID + "')");
            statement.execute("INSERT INTO ab_application_release_publication VALUES (9, '" + RELEASE_ID + "')");
            statement.execute("INSERT INTO ab_application_release_component VALUES ('" + RELEASE_ID + "', 'edu-core', 'definition', '1.2.3', '" + COMPONENT_DIGEST + "')");
            statement.execute("INSERT INTO ab_plugin VALUES ('01K60000000000000000000001', 42, 'com.auraboot.edu', '1.2.3', false)");
            statement.execute("INSERT INTO ab_plugin_resource VALUES ('01K60000000000000000000002', 42, '01K60000000000000000000001', 'command', 'edu:enroll', '{\"code\":\"edu:enroll\",\"displayName\":\"Enroll\"}'::jsonb, false)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin == null) return;
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void mapsExactReleaseAndTenantScopedLegacySnapshot() {
        try (var session = sessions.openSession()) {
            ApplicationDefinitionMapper mapper = session.getMapper(ApplicationDefinitionMapper.class);

            var bound = mapper.findBoundRelease(42L, "aura-edu");
            assertThat(bound.releaseId).isEqualTo(RELEASE_ID);
            assertThat(bound.status).isEqualTo("shadow");
            assertThat(bound.bindingVersion).isEqualTo(3L);
            assertThat(mapper.findPublishedStableRelease("aura-edu").releaseDigest).isEqualTo(RELEASE_DIGEST);
            assertThat(mapper.findDefinitionComponents(RELEASE_ID)).singleElement()
                    .satisfies(component -> assertThat(component.componentDigest).isEqualTo(COMPONENT_DIGEST));

            var plugin = mapper.findTenantPlugin(42L, "com.auraboot.edu");
            assertThat(plugin.getPid()).isEqualTo("01K60000000000000000000001");
            assertThat(mapper.findTenantPluginByPid(42L, plugin.getPid()).getPluginId())
                    .isEqualTo("com.auraboot.edu");
            assertThat(mapper.findTenantPluginByPid(43L, plugin.getPid())).isNull();
            assertThat(mapper.findTenantPlugin(43L, "com.auraboot.edu")).isNull();
            assertThat(mapper.findComparableResources(42L, plugin.getPid())).singleElement()
                    .satisfies(resource -> {
                        assertThat(resource.getResourceType()).isEqualTo("command");
                        assertThat(resource.getImportSnapshot()).containsEntry("code", "edu:enroll");
                    });
            assertThat(mapper.findComparableResources(43L, plugin.getPid())).isEmpty();
        }
    }

    private static PGSimpleDataSource postgres() {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(System.getenv("CRAWLER_REAL_POSTGRES_URL"));
        postgres.setUser(System.getenv().getOrDefault("CRAWLER_REAL_POSTGRES_USER", "postgres"));
        String password = System.getenv("CRAWLER_REAL_POSTGRES_PASSWORD");
        if (password != null) postgres.setPassword(password);
        return postgres;
    }
}
