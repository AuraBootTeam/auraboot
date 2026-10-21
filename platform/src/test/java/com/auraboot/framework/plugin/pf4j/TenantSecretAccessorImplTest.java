package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.plugin.extension.TenantSecretAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic capability checks; actual encryption and database storage need separate real-stack proof. */
class TenantSecretAccessorImplTest {
    @AfterEach void clearContext() { MetaContext.clear(); }
    private Map<String,Object> settings() {
        return new HashMap<>(Map.of("tenantSecretRead", true, "tenantSecretServiceTypes", List.of("integration"),
                "tenantSecretFields", List.of("appSecret")));
    }
    private TenantSecretAccessor accessor(CloudConfigService service) {
        MetaContext.setContext(1L, 2L, "buyer-test", "Buyer");
        var settings = settings(); TenantSecretAccessorImpl.install(settings,1L,service,new ObjectMapper());
        return (TenantSecretAccessor)settings.get(TenantSecretAccessor.SETTINGS_KEY);
    }
    private CloudConfig config(String json) {
        var config = new CloudConfig(); config.setTenantId(1L); config.setConfigLevel("tenant");
        config.setServiceType("integration"); config.setProviderCode("kingdee_a"); config.setEnabled(true);
        config.setDeletedFlag(false); config.setConfig(json); return config;
    }
    @Test void readsOnlyExplicitScopedFieldFromEnabledTenantConfiguration() {
        var service=mock(CloudConfigService.class); var cap=accessor(service);
        when(service.getEffectiveConfig(1L,"integration","kingdee_a")).thenReturn(config("{\"appSecret\":\"test-resolved-value\",\"password\":\"not-granted\"}"));
        assertEquals("test-resolved-value",cap.readCloudSecret("integration","kingdee_a","appSecret"));
        verify(service).getEffectiveConfig(1L,"integration","kingdee_a");
        assertThrows(SecurityException.class,()->cap.readCloudSecret("integration","kingdee_a","password"));
        verifyNoMoreInteractions(service);
    }
    @Test void metadataRequiresLiteralOptInAndCannotSupplyTheHostAccessor() {
        var service=mock(CloudConfigService.class);
        for(Object value:List.of(false,"true",1)) {
            var settings=settings(); settings.put("tenantSecretRead",value);settings.put(TenantSecretAccessor.SETTINGS_KEY,new Object());
            TenantSecretAccessorImpl.install(settings,1L,service,new ObjectMapper());assertFalse(settings.containsKey(TenantSecretAccessor.SETTINGS_KEY));
        }
        verifyNoInteractions(service);
    }
    @Test void invalidMetadataOrAbsentStoreFailsBeforeReadingAnySecret() {
        var service=mock(CloudConfigService.class);
        for(Object scope:Arrays.asList(null,List.of(),List.of("integration","integration"),List.of(1),List.of("*"),"integration")) {
            var settings=settings();settings.put("tenantSecretServiceTypes",scope);
            assertThrows(IllegalArgumentException.class,()->TenantSecretAccessorImpl.install(settings,1L,service,new ObjectMapper()));
        }
        assertThrows(IllegalStateException.class,()->TenantSecretAccessorImpl.install(settings(),1L,null,new ObjectMapper()));
        assertThrows(SecurityException.class,()->TenantSecretAccessorImpl.install(settings(),null,service,new ObjectMapper()));
        verifyNoInteractions(service);
    }
    @Test void contextChangesAndUngrantableSelectorsRejectBeforeStoreLookup() {
        var service=mock(CloudConfigService.class);var cap=accessor(service);
        MetaContext.setContext(2L,2L,"buyer-test","Buyer");
        assertThrows(SecurityException.class,()->cap.readCloudSecret("integration","kingdee_a","appSecret"));
        MetaContext.setContext(1L,2L,null,"Buyer");
        assertThrows(SecurityException.class,()->cap.readCloudSecret("integration","kingdee_a","appSecret"));
        MetaContext.setContext(1L,2L,"buyer-test","Buyer");
        for(String provider:Arrays.asList(null,"","*","../tenant-b"))
            assertThrows(SecurityException.class,()->cap.readCloudSecret("integration",provider,"appSecret"));
        assertThrows(SecurityException.class,()->cap.readCloudSecret("oauth","kingdee_a","appSecret"));
        verifyNoInteractions(service);
    }
    @Test void platformForeignDisabledDeletedOrMismatchedConfigurationNeverSuppliesSecrets() {
        for(int mode=0;mode<7;mode++) {
            var service=mock(CloudConfigService.class);var cap=accessor(service);var c=config("{\"appSecret\":\"must-not-return\"}");
            switch(mode) {case 0 -> c=null;case 1 -> {c.setConfigLevel("platform");c.setTenantId(null);}case 2 -> c.setTenantId(2L);
                case 3 -> c.setEnabled(false);case 4 -> c.setDeletedFlag(true);case 5 -> c.setServiceType("oauth");case 6 -> c.setProviderCode("other");}
            when(service.getEffectiveConfig(1L,"integration","kingdee_a")).thenReturn(c);
            assertThrows(SecurityException.class,()->cap.readCloudSecret("integration","kingdee_a","appSecret"));
        }
    }
    @Test void malformedDocumentsAndUnavailableValuesFailWithoutParserCredentialFragments() {
        var service=mock(CloudConfigService.class);var cap=accessor(service);
        for(String raw:Arrays.asList(null,""," ","[]","{bad-secret-fragment","{\"appSecret\":\"value\"} {}","{}",
                "{\"appSecret\":null}","{\"appSecret\":42}","{\"appSecret\":\" \"}","{\"appSecret\":\"ENC:ciphertext\"}","{\"appSecret\":\"********\"}","{\"appSecret\":\"****cret\"}")) {
            when(service.getEffectiveConfig(1L,"integration","kingdee_a")).thenReturn(config(raw));
            var failure=assertThrows(IllegalStateException.class,()->cap.readCloudSecret("integration","kingdee_a","appSecret"));
            assertFalse(failure.toString().contains("bad-secret-fragment")); assertNull(failure.getCause());
        }
    }
    @Test void storeFailureRemainsFailureWithoutFallback() {
        var service=mock(CloudConfigService.class);var cap=accessor(service);var error=new IllegalStateException("test storage failure");
        when(service.getEffectiveConfig(1L,"integration","kingdee_a")).thenThrow(error);
        assertSame(error,assertThrows(IllegalStateException.class,()->cap.readCloudSecret("integration","kingdee_a","appSecret")));
        verify(service,times(1)).getEffectiveConfig(1L,"integration","kingdee_a"); verifyNoMoreInteractions(service);
    }
}
