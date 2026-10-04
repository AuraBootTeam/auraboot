package com.auraboot.framework.i18n.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.auraboot.framework.i18n.service.impl.I18nResourceServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class I18nSystemDictionaryScopeTest {
    private final I18nResourceMapper mapper = mock(I18nResourceMapper.class);
    private final I18nResourceServiceImpl service = new I18nResourceServiceImpl(mapper);

    @AfterEach
    void clearContext() { MetaContext.clear(); }

    @Test
    void systemDictionaryUsesAnExplicitZeroScopeAndTenantOverridesStayIsolated() {
        MetaContext.setSystemTenantContext(42L);
        when(mapper.selectAllByLang(42L, "en-US")).thenAnswer(call -> {
            assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
            return List.of(resource("action.back", "Tenant back"));
        });
        when(mapper.selectAllByLang(0L, "en-US")).thenAnswer(call -> {
            assertThat(MetaContext.isTenantFilterBypassed()).isTrue();
            return List.of(resource("action.back", "Back"), resource("ai.fill.banner_title", "Fill form from text"));
        });
        assertThat(service.getResourceMapByLang("en-US"))
            .containsEntry("action.back", "Tenant back")
            .containsEntry("ai.fill.banner_title", "Fill form from text");
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(42L);
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
        verify(mapper).selectAllByLang(42L, "en-US");
        verify(mapper).selectAllByLang(0L, "en-US");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void anonymousDictionaryReadsOnlyTheSystemScope() {
        when(mapper.selectAllByLang(0L, "en-US")).thenAnswer(call -> {
            assertThat(MetaContext.isTenantFilterBypassed()).isTrue();
            return List.of(resource("action.back", "Back"));
        });
        assertThat(service.getResourceMapByLang("en-US")).containsOnlyKeys("action.back");
        verify(mapper).selectAllByLang(0L, "en-US");
        verifyNoMoreInteractions(mapper);
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }

    @Test
    void failingSystemReadRestoresTheCallerScope() {
        MetaContext.setSystemTenantContext(42L);
        when(mapper.selectAllByLang(42L, "en-US")).thenReturn(List.of());
        when(mapper.selectAllByLang(0L, "en-US")).thenAnswer(call -> {
            assertThat(MetaContext.isTenantFilterBypassed()).isTrue();
            throw new IllegalStateException("dictionary unavailable");
        });
        assertThatThrownBy(() -> service.getResourceMapByLang("en-US"))
            .isInstanceOf(IllegalStateException.class).hasMessage("dictionary unavailable");
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(42L);
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }

    private static I18nResource resource(String key, String value) {
        I18nResource resource = new I18nResource();
        resource.setI18nKey(key);
        resource.setValue(value);
        return resource;
    }
}
