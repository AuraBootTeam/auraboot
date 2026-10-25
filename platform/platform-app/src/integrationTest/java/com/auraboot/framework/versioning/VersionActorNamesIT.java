package com.auraboot.framework.versioning;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.versioning.mapper.DesignVersionHistoryMapper;
import com.auraboot.framework.versioning.service.impl.VersionHistoryServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Production version and user mappers against the migrated PostgreSQL runtime. */
class VersionActorNamesIT {
    private static long id() { return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE; }
    private static String pid() { return UUID.randomUUID().toString().replace("-", "").substring(0, 26); }

    @Test void resolvesOnlyActiveTenantMembersAndPreservesAuditIdentifiers() throws Exception {
        var ds = new UnpooledDataSource("org.postgresql.Driver",
                Objects.requireNonNull(System.getenv("TEST_DATABASE_URL")),
                Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")), System.getenv("TEST_DATABASE_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("version-actors", new SpringManagedTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(DynamicDataMapper.class);
        config.addMapper(UserMapper.class);
        config.addMapper(DesignVersionHistoryMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var data = session.getMapper(DynamicDataMapper.class);
        var users = session.getMapper(UserMapper.class);
        var versions = new VersionHistoryServiceImpl(session.getMapper(DesignVersionHistoryMapper.class), Map.of(), users);
        long tenant = id();
        long otherTenant = id();
        for (long tenantId : List.of(tenant, otherTenant)) {
            assertThat(data.insert("ab_tenant", Map.of("id", tenantId, "pid", pid(), "name", "Version actors " + tenantId,
                    "status", "active"))).isEqualTo(1);
        }
        String resource = pid();
        List<String> actors = new ArrayList<>();
        try {
            for (String scenario : List.of("active", "foreign", "inactive", "deleted-member", "deleted-user")) {
                long userId = id();
                String userPid = pid();
                actors.add(userPid);
                assertThat(data.insert("ab_user", Map.of("id", userId, "pid", userPid,
                        "user_name", "actor_" + userPid, "nick_name", "Version editor " + scenario,
                        "deleted_flag", scenario.equals("deleted-user")))).isEqualTo(1);
                assertThat(data.insert("ab_tenant_member", Map.of("id", id(), "pid", pid(), "user_id", userId,
                        "tenant_id", scenario.equals("foreign") ? otherTenant : tenant,
                        "status", scenario.equals("inactive") ? "inactive" : "active",
                        "deleted_flag", scenario.equals("deleted-member")))).isEqualTo(1);
                MetaContext.setContext(tenant, userId, userPid, "fixture");
                var recorded = versions.recordVersionWithSnapshot("report", resource,
                        new ObjectMapper().readTree("{}"), "update", "Actor visibility fixture");
                assertThat(recorded.getOperationBy()).isEqualTo(userPid);
                assertThat(recorded.getOperationByDisplayName())
                        .isEqualTo(scenario.equals("active") ? "Version editor active" : null);
                var detail = versions.getVersion(recorded.getPid());
                assertThat(detail.getOperationByDisplayName()).isEqualTo(recorded.getOperationByDisplayName());
                assertThat(detail.getSchemaSnapshot()).isNotNull();
            }
            var history = versions.getHistory("report", resource);
            assertThat(history).hasSize(5);
            assertThat(history).allSatisfy(entry -> assertThat(entry.getSchemaSnapshot()).isNull());
            assertThat(history.stream().map(v -> v.getOperationByDisplayName()).filter(Objects::nonNull).toList())
                    .containsExactly("Version editor active");
            assertThat(users.findDisplayNamesByPidsInTenant(otherTenant, actors))
                    .singleElement().satisfies(row -> assertThat(row).containsEntry("display_name", "Version editor foreign"));
            MetaContext.setSystemTenantContext(otherTenant);
            assertThat(versions.getHistory("report", resource)).isEmpty();
            assertThat(versions.getVersion(history.getFirst().getPid())).isNull();
        } finally {
            MetaContext.clear();
        }
    }
}
