package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.mapper.PermissionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL proof of the bounded queries, including shadowed and malformed definitions. */
@EnabledIfEnvironmentVariable(named = "CRAWLER_REAL_POSTGRES_URL", matches = ".+")
class PermissionCatalogLookupPostgresIT {
    @Test
    void boundedQueriesPreserveTenantDeletionAndNewestCodeResolution() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("CRAWLER_REAL_POSTGRES_URL"),
                System.getenv().getOrDefault("CRAWLER_REAL_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("CRAWLER_REAL_POSTGRES_PASSWORD", ""))) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE ab_permission (id bigint, tenant_id bigint, code text, deleted_flag boolean, created_at timestamp, extension jsonb) ON COMMIT DROP");
                statement.execute("""
                    INSERT INTO ab_permission VALUES
                    (1,100,'domain.read',false,'2026-01-01','{"domainModels":{"record":["read"]}}'),
                    (2,200,'domain.read',false,'2026-02-01','{"domainModels":{"foreign":["read"]}}'),
                    (3,100,'domain.read',true,'2026-03-01','{"domainModels":{"deleted":["read"]}}'),
                    (4,100,'shadow.read',false,'2026-01-01','{"domainModels":{"record":["read"]}}'),
                    (5,100,'SHADOW.READ',false,'2026-02-01','{}'),
                    (6,100,'malformed.read',null,'2026-01-01','{"domainModels":null}'),
                    (7,100,'ordinary.read',false,'2026-01-01','{}'),
                    (8,100,'archived.read',null,'2026-01-01','{"domainModels":{"record":["read"]}}')
                    """);
                try (var migration = getClass().getResourceAsStream(
                        "/db/migration/core/V20261111161500__index_resolvable_permission_code.sql")) {
                    if (migration == null) throw new IllegalStateException("Permission lookup migration is missing");
                    statement.execute(new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                assertThat(ids(connection, "findResolvableDomainDefinitions", 100L, null))
                        .containsExactlyInAnyOrder(1L, 6L, 8L);
                assertThat(ids(connection, "findResolvableDomainDefinitions", 200L, null)).containsExactly(2L);
                assertThat(ids(connection, "findByTenantIdAndCode", 100L, "DOMAIN.READ")).containsExactly(1L);
                assertThat(ids(connection, "findByTenantIdAndCode", 100L, "shadow.read")).containsExactly(5L);
                assertThat(ids(connection, "findByTenantIdAndCode", 100L, "missing")).isEmpty();
            } finally {
                connection.rollback();
            }
        }
    }

    private List<Long> ids(Connection connection, String method, Long tenant, String code) throws Exception {
        Class<?>[] types = code == null ? new Class<?>[]{Long.class} : new Class<?>[]{Long.class, String.class};
        String sql = String.join("\n", PermissionMapper.class.getMethod(method, types).getAnnotation(Select.class).value())
                .replace("#{tenantId}", "?").replace("#{code}", "?");
        try (var statement = connection.prepareStatement(sql)) {
            int tenantBindings = sql.length() - sql.replace("?", "").length() - (code == null ? 0 : 1);
            for (int parameter = 1; parameter <= tenantBindings; parameter++) statement.setLong(parameter, tenant);
            if (code != null) statement.setString(tenantBindings + 1, code);
            try (var rows = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rows.next()) ids.add(rows.getLong("id"));
                return ids;
            }
        }
    }
}
