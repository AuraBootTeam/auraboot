package com.auraboot.framework.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.tenant.controller.SystemPreferencesController;
import com.auraboot.framework.tenant.dao.mapper.TenantPreferenceMapper;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.auraboot.framework.tenant.service.impl.TenantPreferenceServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real PostgreSQL persistence for tenant system preferences.
 *
 * Fake-DB compensation required by testing-layering.md §2.1: the hermetic
 * {@code SystemPreferencesControllerTest} only proves controller wiring on an
 * in-memory stand-in; the persistence claims (insert/update per tenant key,
 * tenant scoping, JSONB value round-trip) are proven here against the real
 * mapper/service through {@code TEST_DATABASE_URL}. Missing environment fails
 * the suite — never skips.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SystemPreferencesPersistenceIT.Fixture.class)
class SystemPreferencesPersistenceIT {
    private static final long TENANT_A = 991_300_101L;
    private static final long TENANT_B = 991_300_102L;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired TenantPreferenceService preferences;
    @Autowired SystemPreferencesController controller;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void identity() {
        jdbc.update("DELETE FROM ab_tenant_preference WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        MetaContext.setSystemTenantContext(TENANT_A);
    }

    @AfterEach
    void cleanup() {
        try {
            jdbc.update("DELETE FROM ab_tenant_preference WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void insertReadsBackThroughJsonbColumnWithIdentityRow() {
        ObjectNode payload = JSON.createObjectNode()
            .put("datetimeFormat", "YYYY/MM/DD HH:mm")
            .put("sidebarCollapsed", true);
        ObjectNode features = JSON.createObjectNode();
        features.put("lang", "zh-CN");
        payload.set("features", features);
        String key = "ui.payload." + SEQUENCE.incrementAndGet();

        preferences.setPreference(TENANT_A, key, payload);

        JsonNode readBack = preferences.getPreference(TENANT_A, key);
        assertThat(readBack).isEqualTo(payload);

        var row = jdbc.queryForMap(
            "SELECT pid, tenant_id, preference_key, pg_typeof(preference_value)::text AS column_type, "
                + "preference_value #>> '{features,lang}' AS lang FROM ab_tenant_preference "
                + "WHERE tenant_id = ? AND preference_key = ?", TENANT_A, key);
        assertThat(row.get("column_type")).isEqualTo("jsonb");
        assertThat(row.get("lang")).isEqualTo("zh-CN");
        assertThat((String) row.get("pid")).hasSize(26);
        assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(TENANT_A);
    }

    @Test
    void settingSameKeyAgainUpdatesInPlace() {
        String key = "ui.datetime.format." + SEQUENCE.incrementAndGet();
        preferences.setPreference(TENANT_A, key, TextNode.valueOf("YYYY/MM/DD HH:mm"));
        preferences.setPreference(TENANT_A, key, TextNode.valueOf("YYYY-MM-DD HH:mm:ss"));

        Integer rows = jdbc.queryForObject(
            "SELECT count(*) FROM ab_tenant_preference WHERE tenant_id = ? AND preference_key = ?",
            Integer.class, TENANT_A, key);
        assertThat(rows).isEqualTo(1);
        assertThat(preferences.getPreference(TENANT_A, key).asText()).isEqualTo("YYYY-MM-DD HH:mm:ss");
    }

    @Test
    void tenantScopeIsolatesIdenticalKeysAndHonorsPrefix() {
        MetaContext.setSystemTenantContext(TENANT_A);
        preferences.setPreference(TENANT_A, "ui.timezone", TextNode.valueOf("Asia/Shanghai"));
        preferences.setPreference(TENANT_A, "ui.datetime.format", TextNode.valueOf("YYYY/MM/DD HH:mm"));

        MetaContext.setSystemTenantContext(TENANT_B);
        preferences.setPreference(TENANT_B, "ui.timezone", TextNode.valueOf("Europe/Berlin"));

        assertThat(preferences.getPreference(TENANT_B, "ui.timezone").asText()).isEqualTo("Europe/Berlin");
        // The tenant interceptor enforces the context tenant on every statement:
        // an explicit foreign tenantId cannot leak another tenant's row — a real
        // predicate the in-memory fake never exercised.
        assertThat(preferences.getPreference(TENANT_A, "ui.timezone")).isNull();

        MetaContext.setSystemTenantContext(TENANT_A);
        var tenantAPreferences = preferences.getPreferencesByPrefix(TENANT_A, "ui.");
        assertThat(tenantAPreferences).containsOnlyKeys("ui.timezone", "ui.datetime.format");
        assertThat(tenantAPreferences.get("ui.timezone").asText()).isEqualTo("Asia/Shanghai");

        MetaContext.setSystemTenantContext(TENANT_B);
        var tenantBPreferences = preferences.getPreferencesByPrefix(TENANT_B, "ui.");
        assertThat(tenantBPreferences).containsOnlyKeys("ui.timezone");
        assertThat(tenantBPreferences.get("ui.timezone").asText()).isEqualTo("Europe/Berlin");

        Integer tenantARows = jdbc.queryForObject(
            "SELECT count(*) FROM ab_tenant_preference WHERE tenant_id = ?", Integer.class, TENANT_A);
        Integer tenantBRows = jdbc.queryForObject(
            "SELECT count(*) FROM ab_tenant_preference WHERE tenant_id = ?", Integer.class, TENANT_B);
        assertThat(tenantARows).isEqualTo(2);
        assertThat(tenantBRows).isEqualTo(1);
    }

    @Test
    void controllerUpdatePersistsBothKeysThroughRealServiceAndReflectsConfiguredState() {
        var request = new SystemPreferencesController.SystemPreferencesRequest();
        request.setDatetimeFormat("YYYY-MM-DD HH:mm:ss");
        request.setTimezone("Europe/London");

        var response = controller.updateSystemPreferences(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(preferences.getPreference(TENANT_A, "ui.datetime.format").asText())
            .isEqualTo("YYYY-MM-DD HH:mm:ss");
        assertThat(preferences.getPreference(TENANT_A, "ui.timezone").asText()).isEqualTo("Europe/London");
        Integer persistedRows = jdbc.queryForObject(
            "SELECT count(*) FROM ab_tenant_preference WHERE tenant_id = ? "
                + "AND preference_key IN ('ui.datetime.format', 'ui.timezone')",
            Integer.class, TENANT_A);
        assertThat(persistedRows).isEqualTo(2);

        var updated = controller.getSystemPreferences();
        assertThat(updated.isSuccess()).isTrue();
        assertThat(updated.getData().getDatetimeFormat()).isEqualTo("YYYY-MM-DD HH:mm:ss");
        assertThat(updated.getData().getTimezone()).isEqualTo("Europe/London");
        assertThat(updated.getData().isTimezoneConfigured()).isTrue();
        assertThat(updated.getData().getTimezoneStatusText()).contains("已设置");

        MetaContext.setSystemTenantContext(TENANT_B);
        var untouched = controller.getSystemPreferences();
        assertThat(untouched.getData().getDatetimeFormat()).isEqualTo("YYYY-MM-DD HH:mm:ss");
        assertThat(untouched.getData().getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(untouched.getData().isTimezoneConfigured()).isFalse();
        assertThat(untouched.getData().getTimezoneStatusText()).contains("尚未配置");
    }

    @Configuration
    @EnableTransactionManagement
    static class Fixture {
        @Bean
        DataSource dataSource() {
            String url = System.getenv("TEST_DATABASE_URL");
            if (url == null || !url.startsWith("jdbc:postgresql:")) {
                throw new IllegalStateException("Managed migrated PostgreSQL TEST_DATABASE_URL is required; never skip");
            }
            return new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        DataSourceTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            var interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
                @Override
                public Expression getTenantId() {
                    return new LongValue(MetaContext.getCurrentTenantId());
                }
            }));
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            var sessions = factory.getObject();
            sessions.getConfiguration().addMapper(TenantPreferenceMapper.class);
            return sessions;
        }

        @Bean
        TenantPreferenceMapper tenantPreferenceMapper(org.apache.ibatis.session.SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(TenantPreferenceMapper.class);
        }

        @Bean
        TenantPreferenceService tenantPreferenceService(TenantPreferenceMapper mapper) {
            return new TenantPreferenceServiceImpl(mapper);
        }

        @Bean
        SystemPreferencesController systemPreferencesController(TenantPreferenceService service) {
            return new SystemPreferencesController(service);
        }
    }
}
