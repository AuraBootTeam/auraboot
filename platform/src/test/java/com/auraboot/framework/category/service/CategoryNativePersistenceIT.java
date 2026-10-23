package com.auraboot.framework.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.category.config.CategoryProperties;
import com.auraboot.framework.category.entity.Category;
import com.auraboot.framework.category.mapper.CategoryMapper;
import com.auraboot.framework.category.service.impl.CategoryServiceImpl;
import com.auraboot.framework.category.service.impl.CategoryTreePolicy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.session.SqlSessionFactory;
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

/** Real native mapper/service persistence, independent of the application-module test layout. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CategoryNativePersistenceIT.Fixture.class)
class CategoryNativePersistenceIT {
    private static final long TENANT = 991_200_101L;
    private static final long ACTOR = 991_200_102L;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    @Autowired CategoryService categories;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void identity() { MetaContext.setContext(TENANT, ACTOR, "category-native-actor", "category-native-it"); }
    @AfterEach void cleanup() {
        try { jdbc.update("DELETE FROM ab_category WHERE tenant_id = ?", TENANT); }
        finally { MetaContext.clear(); }
    }

    @Test void promotingToRootClearsParentAndPreservesPartialUpdateSemantics() {
        var parent = node(null); var child = node(parent.getId()); var leaf = node(child.getId());
        assertThat(categories.moveCategory(child.getId(), null)).isTrue();
        var promoted = categories.getById(child.getId());
        assertThat(promoted.getParentId()).isNull(); assertThat(promoted.getLevel()).isEqualTo(1);
        assertThat(promoted.getMaterializedPath()).isEqualTo("/" + child.getPid());
        assertThat(promoted.getUpdatedBy()).isEqualTo(ACTOR);
        var descendant = categories.getById(leaf.getId());
        assertThat(descendant.getParentId()).isEqualTo(child.getId()); assertThat(descendant.getLevel()).isEqualTo(2);
        assertThat(descendant.getMaterializedPath()).isEqualTo("/" + child.getPid() + "/" + leaf.getPid());
        var patch = new Category(); patch.setId(leaf.getId()); patch.setTenantId(TENANT);
        patch.setCode(leaf.getCode()); patch.setName("Partial rename"); categories.updateCategory(patch);
        assertThat(categories.getById(leaf.getId()).getParentId()).isEqualTo(child.getId());
    }

    @Test void allNativeJsonFieldsRoundTripThroughInsertAndUpdate() {
        var node = draft(null);
        node.setI18nName("{\"en\":\"Tops\",\"zh-CN\":\"上装\"}");
        node.setExternalTaxonomy("{\"provider\":\"external-123\"}"); node.setExtra("{\"featured\":true}");
        categories.createCategory(node);
        var stored = categories.getById(node.getId());
        assertThat(stored.getI18nName()).contains("Tops", "上装");
        assertThat(stored.getExternalTaxonomy()).contains("external-123"); assertThat(stored.getExtra()).contains("true");
        stored.setI18nName("{\"en\":\"New tops\"}"); categories.updateCategory(stored);
        assertThat(categories.getById(node.getId()).getI18nName()).contains("New tops");
        assertThat(jdbc.queryForObject("SELECT pg_typeof(i18n_name)::text FROM ab_category WHERE id=?", String.class, node.getId())).isEqualTo("jsonb");
    }

    private Category node(Long parent) { return categories.createCategory(draft(parent)); }
    private Category draft(Long parent) {
        var node = new Category(); node.setTenantId(TENANT); node.setParentId(parent);
        node.setCode("native-boundary-" + SEQUENCE.incrementAndGet()); node.setName("Native category"); node.setCategoryType("commerce_product");
        return node;
    }

    @Configuration
    @EnableTransactionManagement
    static class Fixture {
        @Bean DataSource dataSource() {
            String url = System.getenv("TEST_DATABASE_URL");
            if (url == null || !url.startsWith("jdbc:postgresql:")) throw new IllegalStateException("Managed migrated PostgreSQL TEST_DATABASE_URL is required; never skip");
            return new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean CategoryTreePolicy categoryTreePolicy() { return new CategoryTreePolicy(new CategoryProperties()); }
        @Bean CategoryService categoryService() { return new CategoryServiceImpl(); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            var interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
                @Override public Expression getTenantId() { return new LongValue(MetaContext.getCurrentTenantId()); }
            }));
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig().setLogicDeleteValue("true").setLogicNotDeleteValue("false")));
            factory.setPlugins(interceptor);
            var sessions = factory.getObject(); sessions.getConfiguration().addMapper(CategoryMapper.class);
            return sessions;
        }
        @Bean CategoryMapper categoryMapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(CategoryMapper.class); }
    }
}
