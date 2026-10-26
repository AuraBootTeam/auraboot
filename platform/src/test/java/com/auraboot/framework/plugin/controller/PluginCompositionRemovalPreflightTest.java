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
        var controller=new PluginController(records,mock(com.auraboot.framework.plugin.service.PlatformVersionChecker.class),
                mock(com.auraboot.framework.plugin.config.PlatformProperties.class),manager);
        assertThatThrownBy(()->controller.disablePlugin("shared")).isInstanceOf(BusinessException.class);
        verify(records,never()).markAsDisabled(anyString());
    }

    @Test void importRollbackRejectsBeforeResourceRestorationDeletionOrHistoryMutation() {
        MetaContext.setContext(1L,7L,"operator","Operator");
        var service=mock(PluginImportServiceImpl.class,CALLS_REAL_METHODS);
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
