package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.entity.PluginImportHistory;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginImportHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.service.impl.PluginImportServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginCompositionRemovalPreflightTest {
    @AfterEach void clear(){MetaContext.clear();}

    @Test void configurationControllerCannotDirectlyDisableTheSharedApplicationRecord() {
        MetaContext.setContext(1L,7L,"operator","Operator");
        var records=mock(PluginRecordMapper.class);var manager=mock(AuraPluginManager.class);
        var record=new PluginRecord();record.setPid("shared");record.setTenantId(1L);
        when(records.findByPid("shared")).thenReturn(record);
        doThrow(BusinessException.i18n("plugin.error.composition_lifecycle")).when(manager).requireTenantLifecycleChange(record);
        var catalogPolicy=new com.auraboot.framework.application.tenant.ApplicationCatalogPolicy(mock(com.auraboot.framework.tenant.service.TenantPreferenceService.class),"");
        var controller=new PluginController(mock(com.auraboot.framework.plugin.service.PlatformVersionChecker.class),
                mock(com.auraboot.framework.plugin.config.PlatformProperties.class),manager,
                new com.auraboot.framework.plugin.service.impl.PluginRecordStateServiceImpl(records,manager,catalogPolicy));
        assertThatThrownBy(()->controller.disablePlugin("shared")).isInstanceOf(BusinessException.class);
        verify(records,never()).markAsDisabled(anyString());
    }

    @Test void productPluginListsDoNotChangeSharedInstallationRecords() {
        MetaContext.setContext(42L,7L,"operator","Operator");
        var records=mock(PluginRecordMapper.class);
        var preferences=mock(com.auraboot.framework.tenant.service.TenantPreferenceService.class);
        var value=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        value.put("version",1).put("applicationCode","bom-quote");value.putArray("pluginIds").add("com.auraboot.core-meta");
        value.putArray("menuCodes");value.putArray("capabilityCodes");value.putArray("pageKeys");
        when(preferences.getPreference(42L,com.auraboot.framework.application.tenant.ApplicationCatalogPolicy.key("bom-quote"))).thenReturn(value);
        var retained=new PluginRecord();retained.setPluginId("com.auraboot.core-meta");retained.setStatus("enabled");
        var historical=new PluginRecord();historical.setPluginId("com.example.other-product");historical.setStatus("enabled");
        when(records.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class))).thenReturn(java.util.List.of(retained));
        var properties=new com.auraboot.framework.plugin.config.PlatformProperties();
        var controller=new PluginController(new com.auraboot.framework.plugin.service.PlatformVersionChecker(properties),
                properties,mock(AuraPluginManager.class),
                new com.auraboot.framework.plugin.service.impl.PluginRecordStateServiceImpl(records,mock(AuraPluginManager.class),
                        new com.auraboot.framework.application.tenant.ApplicationCatalogPolicy(preferences,"bom-quote")));
        assertThat(controller.listPlugins().getData()).extracting(PluginController.PluginDTO::pluginId).containsExactly("com.auraboot.core-meta");
        assertThat(controller.checkVersionCompatibility().getData().plugins()).extracting(PluginController.PluginCompatibilityDTO::pluginId).containsExactly("com.auraboot.core-meta");
        var capture=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(records,times(2)).selectList(capture.capture());
        for(var query:capture.getAllValues()) {
            assertThat(query.getSqlSegment()).contains("tenant_id","deleted_flag","plugin_id","ORDER BY created_at DESC");
            assertThat(query.getParamNameValuePairs()).containsValue(42L).containsValue(false)
                    .containsValue("com.auraboot.core-meta").doesNotContainValue("com.example.other-product");
        }
        verifyNoMoreInteractions(records);
        assertThat(historical.getStatus()).isEqualTo("enabled");
    }

    @Test void importRollbackRejectsBeforeResourceRestorationDeletionOrHistoryMutation() {
        MetaContext.setContext(1L,7L,"operator","Operator");
        var service=mock(PluginImportServiceImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"authorizationWriteLock",mock(com.auraboot.framework.permission.service.RoleAuthorizationWriteLock.class));
        var imports=mock(PluginImportHistoryMapper.class);var records=mock(PluginRecordMapper.class);
        var resources=mock(PluginResourceMapper.class);var manager=mock(AuraPluginManager.class);
        ReflectionTestUtils.setField(service,"importHistoryMapper",imports);
        ReflectionTestUtils.setField(service,"pluginRecordMapper",records);
        ReflectionTestUtils.setField(service,"pluginResourceMapper",resources);
        ReflectionTestUtils.setField(service,"auraPluginManager",manager);
        ReflectionTestUtils.setField(service,"agentDefinitionResources",mock(com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle.class));
        var history=new PluginImportHistory();history.setImportId("accepted-import");history.setPluginPid("shared");history.setTenantId(1L);history.setStatus("success");
        when(imports.findByImportId("accepted-import")).thenReturn(history);
        var record=new PluginRecord();record.setPid("shared");record.setTenantId(1L);
        when(records.lockIdentityIncludingDeleted(1L,"shared")).thenReturn(record);
        doThrow(BusinessException.i18n("plugin.error.composition_lifecycle")).when(manager).requireTenantLifecycleChange(record);
        assertThatThrownBy(()->service.rollback("accepted-import")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(resources);
        verify(imports,never()).updateStatus(anyString(),anyString());
        verify(records,never()).softDelete(anyString());
    }
}
