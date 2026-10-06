package com.auraboot.framework.i18n.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class I18nTenantReadTest {
    private final I18nResourceMapper mapper = mock(I18nResourceMapper.class);
    private final I18nResourceServiceImpl service = new I18nResourceServiceImpl(mapper);

    @AfterEach void clearContext() { MetaContext.clear(); }

    private I18nResource resource(String key, String value) {
        I18nResource r = new I18nResource();
        r.setI18nKey(key);
        r.setValue(value);
        return r;
    }

    @Test void anonymousReadsOnlyFixedSystemTranslations() {
        MetaContext.clear();
        when(mapper.selectSystemByLang("zh-CN")).thenReturn(List.of(resource("label", "system")));
        assertEquals("system", service.getResourceMapByLang("zh-CN").get("label"));
        verify(mapper).selectSystemByLang("zh-CN");
        verifyNoMoreInteractions(mapper);
        assertFalse(MetaContext.exists());
    }

    @Test void tenantOverridesSystemWithoutReadingOtherTenants() {
        MetaContext.setSystemTenantContext(100L);
        when(mapper.selectSystemByLang("zh-CN")).thenReturn(List.of(
            resource("label", "system"), resource("fallback", "base")));
        when(mapper.selectAllByLang(100L, "zh-CN")).thenReturn(List.of(resource("label", "tenantA")));
        assertEquals(java.util.Map.of("label", "tenantA", "fallback", "base"),
            service.getResourceMapByLang("zh-CN"));
        verify(mapper).selectSystemByLang("zh-CN");
        verify(mapper).selectAllByLang(100L, "zh-CN");
        verifyNoMoreInteractions(mapper);
        assertEquals(100L, MetaContext.getCurrentTenantId());
    }

    @Test void switchingTenantDoesNotCarryPriorOverrides() {
        when(mapper.selectSystemByLang("zh-CN")).thenReturn(List.of());
        when(mapper.selectAllByLang(100L, "zh-CN")).thenReturn(List.of(resource("label", "tenantA")));
        when(mapper.selectAllByLang(200L, "zh-CN")).thenReturn(List.of(resource("label", "tenantB")));
        MetaContext.setSystemTenantContext(100L);
        assertEquals("tenantA", service.getResourceMapByLang("zh-CN").get("label"));
        MetaContext.clear();
        MetaContext.setSystemTenantContext(200L);
        assertEquals("tenantB", service.getResourceMapByLang("zh-CN").get("label"));
        verify(mapper, times(2)).selectSystemByLang("zh-CN");
        verify(mapper).selectAllByLang(100L, "zh-CN");
        verify(mapper).selectAllByLang(200L, "zh-CN");
        verifyNoMoreInteractions(mapper);
    }
}
