package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionalIntegrationInstallationTest {
    private static final String OWNER = "com.auraboot.manufacturing-operations";
    private final PluginRecordMapper plugins = mock(PluginRecordMapper.class);
    private final ReliableIntegrationAccessorImpl accessor = new ReliableIntegrationAccessorImpl(
            null, null, null, null, mock(ApplicationEventPublisher.class), plugins);
    @BeforeEach void bind() {
        MetaContext.setSystemTenantContext(7L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() {
        MetaContext.clear();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    private PluginRecord record(long tenant, String status) {
        return PluginRecord.builder().tenantId(tenant).pluginId(OWNER).status(status).build();
    }
    @Test void absentOwnerIsTheOnlyFalseResult() {
        assertFalse(accessor.isPluginInstalled(7L, OWNER));
        verify(plugins).findIntegrationIdentity(7L, OWNER);
    }
    @Test void importedOrEnabledOwnerSelectsTheIntegration() {
        when(plugins.findIntegrationIdentity(7L, OWNER)).thenReturn(record(7, "installed"), record(7, "enabled"));
        assertTrue(accessor.isPluginInstalled(7L, OWNER));
        assertTrue(accessor.isPluginInstalled(7L, OWNER));
    }
    @Test void disabledFailedAndUnknownOwnerCannotSilentlyDropFacts() {
        for (String status : new String[]{"disabled", "failed", "unexpected"}) {
            when(plugins.findIntegrationIdentity(7L, OWNER)).thenReturn(record(7, status));
            assertThrows(IllegalStateException.class, () -> accessor.isPluginInstalled(7L, OWNER));
        }
    }
    @Test void retiredIdentityCannotBecomeAnImplicitOptOut() {
        var retired = record(7, "installed"); retired.setDeletedFlag(true);
        when(plugins.findIntegrationIdentity(7L, OWNER)).thenReturn(retired);
        assertThrows(IllegalStateException.class, () -> accessor.isPluginInstalled(7L, OWNER));
    }
    @Test void wrongTenantAndMissingTransactionAreRejectedBeforeLookup() {
        assertThrows(SecurityException.class, () -> accessor.isPluginInstalled(8L, OWNER));
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(SecurityException.class, () -> accessor.isPluginInstalled(7L, OWNER));
        verifyNoInteractions(plugins);
    }
    @Test void foreignRowsAndDatabaseFailuresFailClosed() {
        when(plugins.findIntegrationIdentity(7L, OWNER)).thenReturn(record(8, "installed"));
        assertThrows(SecurityException.class, () -> accessor.isPluginInstalled(7L, OWNER));
        when(plugins.findIntegrationIdentity(7L, OWNER)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> accessor.isPluginInstalled(7L, OWNER));
    }
}
