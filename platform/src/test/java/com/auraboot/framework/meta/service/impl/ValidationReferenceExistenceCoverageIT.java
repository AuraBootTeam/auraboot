package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-PostgreSQL coverage for field-level business reference existence checks.
 * Uses the legacy explicit targetTable path so no model registration is needed;
 * the target rows live in a per-run scratch table dropped afterwards.
 */
@Slf4j
@SpringJUnitConfig(ValidationReferenceExistenceCoverageIT.Stack.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ValidationService reference existence — real PostgreSQL")
class ValidationReferenceExistenceCoverageIT {

    private static final long TENANT_ID = UUID.randomUUID().getMostSignificantBits() & 0x001fffffffffffffL;
    private static final String TABLE = "mt_refcheck_" + (TENANT_ID & 0xffffffL);

    @Autowired
    private ValidationServiceImpl validationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, TENANT_ID + 1, "refcheck", "refcheck-user");
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLE);
            jdbcTemplate.execute("CREATE TABLE " + TABLE
                    + " (pid varchar(64) PRIMARY KEY, tenant_id bigint NOT NULL)");
            jdbcTemplate.update("INSERT INTO " + TABLE + " (pid, tenant_id) VALUES (?, ?)",
                    "01REAL", TENANT_ID);
        });
    }

    @AfterEach
    void tearDown() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLE);
        } finally {
            MetaContext.clear();
        }
    }

    private FieldDefinition referenceField(String code) {
        FieldDefinition field = new FieldDefinition();
        field.setCode(code);
        field.setName(code);
        field.setDataType("reference");
        FieldDefinition.RefTarget refTarget = new FieldDefinition.RefTarget();
        refTarget.setTargetEntity("legacy_model");
        refTarget.setTargetTable(TABLE);
        refTarget.setTargetField("pid");
        field.setRefTarget(refTarget);
        return field;
    }

    @Test
    @DisplayName("existing referenced pid passes against the real table")
    void existingReferencePasses() {
        var result = validationService.validateField(
                referenceField("sc_ref"), "01REAL", ValidationContext.CREATE);
        assertTrue(result.isValid(), () -> "unexpected errors: " + result.getErrors());
    }

    @Test
    @DisplayName("dangling referenced pid is rejected against the real table")
    void danglingReferenceRejected() {
        var result = validationService.validateField(
                referenceField("sc_ref"), "01MISSING", ValidationContext.CREATE);
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains(TABLE));
        assertTrue(result.getErrors().get(0).contains("01MISSING"));
    }

    @Test
    @DisplayName("records from another tenant do not satisfy the reference")
    void crossTenantReferenceRejected() {
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO " + TABLE + " (pid, tenant_id) VALUES (?, ?)",
                        "01OTHERTENANT", TENANT_ID + 999));
        var result = validationService.validateField(
                referenceField("sc_ref"), "01OTHERTENANT", ValidationContext.CREATE);
        assertFalse(result.isValid());
    }

    @Configuration
    static class Stack {
        @Bean DataSource dataSource() {
            String url = System.getenv("TEST_DATABASE_URL");
            if (url == null || !url.matches("jdbc:postgresql://[^/]+/aura_bpm_[0-9]+(?:\\?.*)?")) {
                throw new IllegalStateException("TEST_DATABASE_URL must identify an owned aura_bpm slot database");
            }
            return new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"),
                    System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        }
        @Bean org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean MapperFactoryBean<DynamicDataMapper> dynamicDataMapper(org.apache.ibatis.session.SqlSessionFactory sql) {
            var bean = new MapperFactoryBean<>(DynamicDataMapper.class);
            bean.setSqlSessionFactory(sql);
            return bean;
        }
        @Bean MapperFactoryBean<UserMapper> userMapper(org.apache.ibatis.session.SqlSessionFactory sql) {
            var bean = new MapperFactoryBean<>(UserMapper.class);
            bean.setSqlSessionFactory(sql);
            return bean;
        }
        @Bean MetaModelService metaModelService() {
            return Mockito.mock(MetaModelService.class);
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean TransactionTemplate transactionTemplate(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean UserService userService() {
            return Mockito.mock(UserService.class);
        }
        @Bean ValidationServiceImpl validationService(DynamicDataMapper mapper, UserService users, MetaModelService models) {
            return new ValidationServiceImpl(mapper, users, models);
        }
    }
}
