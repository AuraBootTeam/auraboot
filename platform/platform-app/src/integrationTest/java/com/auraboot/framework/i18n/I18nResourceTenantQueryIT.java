package com.auraboot.framework.i18n;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.auraboot.framework.i18n.service.impl.I18nResourceServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import java.util.Objects;
import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL mapper/interceptor contract; fixtures are connection-local TEMP rows. */
class I18nResourceTenantQueryIT {
    private SingleConnectionDataSource dataSource;
    private SqlSession session;
    private I18nResourceServiceImpl service;

    @BeforeEach void setup() throws Exception {
        MetaContext.clear();
        dataSource = new SingleConnectionDataSource(DriverManager.getConnection(
                Objects.requireNonNull(System.getenv("TEST_DATABASE_URL")),
                Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")),
                System.getenv("TEST_DATABASE_PASSWORD")), true);
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        sql.execute("CREATE TEMP TABLE ab_i18n_resource (LIKE public.ab_i18n_resource INCLUDING DEFAULTS)");
        for (long tenant : new long[] { 0, 99, 199 }) {
            sql.update("INSERT INTO ab_i18n_resource (pid, tenant_id, i18n_key, lang, value, source, status, deleted_flag) VALUES (?, ?, ?, 'zh-CN', ?, 'test', 'approved', false)",
                    "it-i18n-" + tenant, tenant, "shared.label", "tenant-" + tenant);
        }
        sql.update("INSERT INTO ab_i18n_resource (pid, tenant_id, i18n_key, lang, value, source, status, deleted_flag) VALUES ('it-foreign', 199, 'foreign.private', 'zh-CN', 'private', 'test', 'approved', false)");
        sql.update("INSERT INTO ab_i18n_resource (pid, tenant_id, i18n_key, lang, value, source, status, deleted_flag) VALUES ('it-system', 0, 'system.label', 'zh-CN', 'public', 'test', 'approved', false)");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("i18n-tenant-it", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new LongValue(MetaContext.getCurrentTenantId()); }
        }));
        config.addInterceptor(interceptor);
        config.addMapper(I18nResourceMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        service = new I18nResourceServiceImpl(session.getMapper(I18nResourceMapper.class));
    }

    @AfterEach void cleanup() {
        MetaContext.clear();
        if (session != null) session.close();
        if (dataSource != null) dataSource.destroy();
    }

    @Test void publicLocaleReadsOnlySystemResourcesWithoutRequestContext() {
        assertThat(service.getResourceMapByLang("zh-CN")).containsExactly(
                java.util.Map.entry("shared.label", "tenant-0"), java.util.Map.entry("system.label", "public"));
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test void authenticatedLocaleOverridesSystemWithoutReadingAnotherTenant() {
        MetaContext.setContext(99L, 1L, "user", "user");
        assertThat(service.getResourceMapByLang("zh-CN")).containsExactly(
                java.util.Map.entry("shared.label", "tenant-99"), java.util.Map.entry("system.label", "public"));
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(99L);
    }
}
