package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.mapper.CloudConfigMapper;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.plugin.extension.TenantSecretAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real CloudConfig service/mapper/encryption evidence on the explicitly selected procurement database. */
@SpringBootTest(classes=TestApplication.class, properties={
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "security.field-encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("integration-test")
class TenantSecretAccessorRealDbIT {
    @Autowired CloudConfigService configs;
    @Autowired CloudConfigMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @AfterEach void clearContext() { MetaContext.clear(); }

    private String provider() { return "ps_"+UUID.randomUUID().toString().replace("-","").substring(0,24); }
    private void context() { MetaContext.setContext(1L,2L,"secret-it-buyer","Buyer"); }
    private void save(String provider,String level,String value) {
        var request=new CloudConfigSaveRequest();request.setConfigLevel(level);request.setServiceType("integration");
        request.setProviderCode(provider);request.setConfig("{\"appSecret\":\""+value+"\"}");request.setEnabled(true);request.setPriority(1);
        configs.saveConfig(request);
    }
    private TenantSecretAccessor accessor() {
        var settings=new HashMap<String,Object>(Map.of("tenantSecretRead",true,
                "tenantSecretServiceTypes",List.of("integration"),"tenantSecretFields",List.of("appSecret")));
        TenantSecretAccessorImpl.install(settings,1L,configs,json);return (TenantSecretAccessor)settings.get(TenantSecretAccessor.SETTINGS_KEY);
    }
    private void assertDatabase() throws Exception {
        assertNotNull(System.getenv("TEST_DATABASE_URL"));
        assertEquals("13",System.getenv("TEST_REDIS_DATABASE"));
        try(var connection=dataSource.getConnection()) { assertEquals("enterprise_22",connection.getCatalog()); }
    }
    @Test void actualEncryptedTenantConfigurationResolvesOnlyItsGrantedField() throws Exception {
        assertDatabase();context();String provider=provider();save(provider,"tenant","fixture-secret-value");
        var stored=mapper.getEffectiveConfig(1L,"integration",provider);
        assertNotNull(stored);
        assertTrue(stored.getConfig().contains("ENC:"));assertFalse(stored.getConfig().contains("fixture-secret-value"));
        assertFalse(configs.getConfigMasked(stored.getPid()).getConfig().contains("fixture-secret-value"));
        var cap=accessor();assertEquals("fixture-secret-value",cap.readCloudSecret("integration",provider,"appSecret"));
        assertThrows(SecurityException.class,()->cap.readCloudSecret("integration",provider,"password"));
        MetaContext.setContext(2L,2L,"other-tenant-buyer","Other");
        assertThrows(SecurityException.class,()->cap.readCloudSecret("integration",provider,"appSecret"));
    }
    @Test void actualPlatformDefaultCannotSupplyAMissingTenantSecret() throws Exception {
        assertDatabase();context();String provider=provider();save(provider,"platform","platform-fixture-secret");
        var effective=configs.getEffectiveConfig(1L,"integration",provider);
        assertEquals("platform",effective.getConfigLevel());assertNull(effective.getTenantId());
        assertThrows(SecurityException.class,()->accessor().readCloudSecret("integration",provider,"appSecret"));
    }
}
