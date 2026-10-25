package com.auraboot.framework.cloudconfig.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.database.dialect.PostgresDialect;
import com.auraboot.framework.application.database.mybatis.MybatisPlusConfig;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.mapper.CloudConfigMapper;
import com.auraboot.framework.cloudconfig.service.impl.CloudConfigServiceImpl;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL service round trips; unique records are retained as evidence. */
@SpringJUnitConfig(CloudConfigIsolationIT.Config.class)
@TestPropertySource(properties = {
        "security.field-encryption.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.profiles.active=test"
})
class CloudConfigIsolationIT {
    private static final long OWNER_TENANT = 991_110_001L;
    private static final long OTHER_TENANT = 991_110_002L;

    @Autowired private CloudConfigService service;
    @Autowired private JdbcTemplate jdbc;
    private String serviceType;
    private String provider;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        serviceType = "cc_it_" + suffix;
        provider = "provider_" + suffix;
        owner();
    }

    @AfterEach
    void clearContext() { MetaContext.clear(); }

    private void owner() {
        MetaContext.setContext(OWNER_TENANT, 991_110_003L, "cloud-config-it", "config owner");
    }

    private void other() {
        MetaContext.setContext(OTHER_TENANT, 991_110_004L, "cloud-config-other", "other tenant");
    }

    private CloudConfigSaveRequest request(String level, String pid) {
        CloudConfigSaveRequest request = new CloudConfigSaveRequest();
        request.setConfigLevel(level);
        request.setPid(pid);
        request.setServiceType(serviceType);
        request.setProviderCode(provider);
        request.setConfig("{\"region\":\"initial\"}");
        request.setEnabled(true);
        request.setPriority(1);
        return request;
    }

    private String create(String level) {
        service.saveConfig(request(level, null));
        return jdbc.queryForObject("SELECT pid FROM ab_cloud_config WHERE service_type = ?", String.class, serviceType);
    }

    @Test
    void platformCreateListUpdateAndSoftDeletePreserveNullTenant() {
        String pid = create("PLATFORM");
        assertNull(jdbc.queryForObject("SELECT tenant_id FROM ab_cloud_config WHERE pid = ?", Long.class, pid));
        assertTrue(service.listConfigs("PLATFORM").stream().anyMatch(c -> pid.equals(c.getPid())));
        CloudConfigSaveRequest update = request("platform", pid);
        update.setConfig("{\"region\":\"updated\"}");
        update.setPriority(9);
        service.saveConfig(update);
        assertEquals(9, service.getByPidDecrypted(pid).getPriority());
        assertTrue(service.getByPidDecrypted(pid).getConfig().contains("updated"));
        assertNull(jdbc.queryForObject("SELECT tenant_id FROM ab_cloud_config WHERE pid = ?", Long.class, pid));
        service.deleteConfig(pid);
        assertNull(service.getConfigMasked(pid));
        assertTrue(jdbc.queryForObject("SELECT deleted_flag FROM ab_cloud_config WHERE pid = ?", Boolean.class, pid));
        assertTrue(service.listConfigs("platform").stream().noneMatch(c -> pid.equals(c.getPid())));
    }

    @Test
    void tenantCrudRemainsScoped() {
        String pid = create("TENANT");
        assertEquals(OWNER_TENANT, jdbc.queryForObject("SELECT tenant_id FROM ab_cloud_config WHERE pid = ?", Long.class, pid));
        CloudConfigSaveRequest update = request("tenant", pid);
        update.setPriority(7);
        service.saveConfig(update);
        assertEquals(7, service.getConfigMasked(pid).getPriority());
        service.deleteConfig(pid);
        assertNull(service.getConfigMasked(pid));
        assertTrue(jdbc.queryForObject("SELECT deleted_flag FROM ab_cloud_config WHERE pid = ?", Boolean.class, pid));
    }

    @Test
    void anotherTenantCannotReadMaskedOrDecryptedConfig() {
        String pid = create("tenant");
        other();
        assertNull(service.getConfigMasked(pid));
        assertNull(service.getByPidDecrypted(pid));
        assertTrue(service.listConfigs("tenant").stream().noneMatch(c -> pid.equals(c.getPid())));
    }

    @Test
    void anotherTenantCannotUpdateConfigOrReceiveFalseSuccess() {
        String pid = create("tenant");
        other();
        CloudConfigSaveRequest update = request("tenant", pid);
        update.setPriority(99);
        assertThrows(BusinessException.class, () -> service.saveConfig(update));
        assertEquals(1, jdbc.queryForObject("SELECT priority FROM ab_cloud_config WHERE pid = ?", Integer.class, pid));
    }

    @Test
    void anotherTenantCannotDeleteConfigOrReceiveFalseSuccess() {
        String pid = create("tenant");
        other();
        assertThrows(BusinessException.class, () -> service.deleteConfig(pid));
        assertFalse(jdbc.queryForObject("SELECT deleted_flag FROM ab_cloud_config WHERE pid = ?", Boolean.class, pid));
    }

    @Test
    void updateCannotConvertTenantConfigIntoPlatformConfig() {
        String pid = create("tenant");
        assertThrows(BusinessException.class, () -> service.saveConfig(request("platform", pid)));
        assertEquals("tenant", jdbc.queryForObject("SELECT config_level FROM ab_cloud_config WHERE pid = ?", String.class, pid));
        assertEquals(OWNER_TENANT, jdbc.queryForObject("SELECT tenant_id FROM ab_cloud_config WHERE pid = ?", Long.class, pid));
    }

    @Test
    void unknownConfigurationLevelIsRejected() {
        assertThrows(BusinessException.class, () -> service.saveConfig(request("unknown", null)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_cloud_config WHERE service_type = ?", Integer.class, serviceType));
    }

    @Test
    void maskedEditPreservesEncryptedSecretAndExplicitReplacementRotatesIt() {
        CloudConfigSaveRequest initial = request("tenant", null);
        initial.setConfig("{\"apiKey\":\"fixture-original-key\",\"region\":\"initial\"}");
        service.saveConfig(initial);
        String pid = jdbc.queryForObject("SELECT pid FROM ab_cloud_config WHERE service_type = ?", String.class, serviceType);
        String encrypted = jdbc.queryForObject("SELECT config->>'apiKey' FROM ab_cloud_config WHERE pid = ?", String.class, pid);
        assertTrue(encrypted.startsWith("ENC:"));
        CloudConfigSaveRequest edit = request("tenant", pid);
        edit.setConfig(service.getConfigMasked(pid).getConfig());
        edit.setEnabled(false);
        service.saveConfig(edit);
        assertEquals(encrypted, jdbc.queryForObject("SELECT config->>'apiKey' FROM ab_cloud_config WHERE pid = ?", String.class, pid));
        assertTrue(service.getByPidDecrypted(pid).getConfig().contains("fixture-original-key"));
        edit.setConfig("{\"apiKey\":\"fixture-replacement-key\",\"region\":\"updated\"}");
        service.saveConfig(edit);
        String rotated = jdbc.queryForObject("SELECT config->>'apiKey' FROM ab_cloud_config WHERE pid = ?", String.class, pid);
        assertTrue(rotated.startsWith("ENC:"));
        assertNotEquals(encrypted, rotated);
        assertTrue(service.getByPidDecrypted(pid).getConfig().contains("fixture-replacement-key"));
    }

    @EnableTransactionManagement
    @MapperScan(basePackageClasses = CloudConfigMapper.class, annotationClass = Mapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getenv("TEST_DATABASE_URL");
            if (url == null || !url.matches("jdbc:postgresql://[^/]+/auraboot_[0-9]+(?:\\?.*)?")) {
                throw new IllegalStateException("TEST_DATABASE_URL must identify an owned isolated auraboot slot database");
            }
            return new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"),
                    System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        }
        @Bean org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource source, ApplicationContext context) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()
                    .setLogicDeleteValue("true").setLogicNotDeleteValue("false")));
            factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(new PostgresDialect(), context, new MockEnvironment()));
            return factory.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean FieldEncryptionService fieldEncryptionService() { return new FieldEncryptionService(); }
        @Bean CloudConfigService cloudConfigService(CloudConfigMapper mapper, FieldEncryptionService encryption) {
            return new CloudConfigServiceImpl(mapper, encryption, new ObjectMapper());
        }
    }
}
