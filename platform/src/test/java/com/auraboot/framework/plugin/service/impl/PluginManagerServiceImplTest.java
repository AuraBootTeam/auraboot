package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.plugin.api.Plugin;
import com.auraboot.framework.plugin.dto.PluginInfo;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.PluginOperationResult;
import com.auraboot.framework.plugin.dto.PluginStatus;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.exception.PluginLifecycleException;
import com.auraboot.framework.plugin.exception.PluginNotFoundException;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PluginManagerServiceImpl covering install / enable / disable /
 * uninstall lifecycle, registry queries, and PF4J sync paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginManagerServiceImpl Unit Tests")
class PluginManagerServiceImplTest {

    @Mock private PluginRecordMapper pluginRecordMapper;
    @Mock private AuraPluginManager auraPluginManager;
    @Mock private ExtensionRegistry extensionRegistry;
    @Mock private AdminEventLogService adminEventLogService;

    @InjectMocks private PluginManagerServiceImpl service;

    @BeforeEach
    void setup() {
        MetaContext.setContext(100L, 1L, "U-1", "tester");
    }

    @AfterEach
    void teardown() {
        MetaContext.clear();
    }

    private PluginManifest validManifest() {
        return PluginManifest.builder()
                .pluginId("com.example.p")
                .namespace("ex")
                .version("1.0.0")
                .displayName("Example")
                .build();
    }

    private PluginRecord recordWithStatus(PluginStatus status) {
        PluginRecord r = PluginRecord.builder()
                .pid("PID-1")
                .pluginId("com.example.p")
                .namespace("ex")
                .version("1.0.0")
                .status(status.code())
                .build();
        return r;
    }

    @Test
    @DisplayName("install should reject manifest missing required fields")
    void installShouldRejectInvalidManifest() {
        PluginManifest bad = PluginManifest.builder().pluginId("p").build(); // no ns/version

        PluginOperationResult result = service.install(bad);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOperation()).isEqualTo(PluginOperationResult.OperationType.INSTALL);
        verify(pluginRecordMapper, never()).insert(any(PluginRecord.class));
    }

    @Test
    @DisplayName("install should fail when namespace already in use")
    void installShouldFailWhenNamespaceTaken() {
        PluginManifest m = validManifest();
        when(pluginRecordMapper.findByTenantAndNamespace("ex"))
                .thenReturn(recordWithStatus(PluginStatus.INSTALLED));

        PluginOperationResult result = service.install(m);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("already installed");
        verify(pluginRecordMapper, never()).insert(any(PluginRecord.class));
    }

    @Test
    @DisplayName("install should persist record and emit success audit log")
    void installShouldPersistAndAudit() {
        PluginManifest m = validManifest();
        when(pluginRecordMapper.findByTenantAndNamespace("ex")).thenReturn(null);

        PluginOperationResult result = service.install(m);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOperation()).isEqualTo(PluginOperationResult.OperationType.INSTALL);
        ArgumentCaptor<PluginRecord> captor = ArgumentCaptor.forClass(PluginRecord.class);
        verify(pluginRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getPluginId()).isEqualTo("com.example.p");
        assertThat(captor.getValue().getStatus()).isEqualTo(PluginStatus.INSTALLED.code());
        verify(adminEventLogService).record(any(AdminEventLog.class));
    }

    @Test
    @DisplayName("install should call onInstall when plugin instance registered")
    void installShouldInvokeOnInstallHook() throws Exception {
        PluginManifest m = validManifest();
        Plugin plugin = org.mockito.Mockito.mock(Plugin.class);
        when(plugin.getPluginId()).thenReturn("com.example.p");
        service.registerPluginInstance(plugin);

        when(pluginRecordMapper.findByTenantAndNamespace("ex")).thenReturn(null);

        PluginOperationResult result = service.install(m);

        assertThat(result.isSuccess()).isTrue();
        verify(plugin, times(1)).onInstall(any());
    }

    @Test
    @DisplayName("enable should throw PluginNotFoundException when no record")
    void enableShouldThrowWhenMissing() {
        when(pluginRecordMapper.findByTenantAndPluginId("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.enable("nope"))
                .isInstanceOf(PluginNotFoundException.class);
    }

    @Test
    @DisplayName("enable on already-enabled plugin returns success without status change")
    void enableShouldShortCircuitWhenEnabled() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.ENABLED));

        PluginOperationResult result = service.enable("com.example.p");

        assertThat(result.isSuccess()).isTrue();
        verify(pluginRecordMapper, never()).markAsEnabled(anyString());
    }

    @Test
    @DisplayName("enable should reject invalid transition (e.g. ENABLED is special-cased; FAILED->ENABLED allowed)")
    void enableShouldUpdateStatusOnInstalled() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.INSTALLED));

        PluginOperationResult result = service.enable("com.example.p");

        assertThat(result.isSuccess()).isTrue();
        verify(pluginRecordMapper).markAsEnabled("PID-1");
    }

    @Test
    @DisplayName("disable should throw PluginNotFoundException when no record")
    void disableShouldThrowWhenMissing() {
        when(pluginRecordMapper.findByTenantAndPluginId("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.disable("nope"))
                .isInstanceOf(PluginNotFoundException.class);
    }

    @Test
    @DisplayName("disable on already-disabled returns success without status change")
    void disableShouldShortCircuitWhenDisabled() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.DISABLED));

        PluginOperationResult result = service.disable("com.example.p");

        assertThat(result.isSuccess()).isTrue();
        verify(pluginRecordMapper, never()).markAsDisabled(anyString());
    }

    @Test
    @DisplayName("disable on ENABLED should call markAsDisabled")
    void disableShouldMarkDisabled() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.ENABLED));

        PluginOperationResult result = service.disable("com.example.p");

        assertThat(result.isSuccess()).isTrue();
        verify(pluginRecordMapper).markAsDisabled("PID-1");
    }

    @Test
    @DisplayName("disable invalid transition (INSTALLED->DISABLED) should throw")
    void disableShouldRejectInvalidTransition() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.INSTALLED));

        assertThatThrownBy(() -> service.disable("com.example.p"))
                .isInstanceOf(PluginLifecycleException.class);
    }

    @Test
    @DisplayName("uninstall should throw when plugin not found")
    void uninstallShouldThrowWhenMissing() {
        when(pluginRecordMapper.findByTenantAndPluginId("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.uninstall("nope", false))
                .isInstanceOf(PluginNotFoundException.class);
    }

    @Test
    @DisplayName("uninstall should reject ENABLED plugin (must be disabled first)")
    void uninstallShouldRejectEnabled() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.ENABLED));
        assertThatThrownBy(() -> service.uninstall("com.example.p", false))
                .isInstanceOf(PluginLifecycleException.class);
    }

    @Test
    @DisplayName("uninstall on INSTALLED should soft-delete and emit audit log")
    void uninstallShouldSoftDelete() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.INSTALLED));

        PluginOperationResult result = service.uninstall("com.example.p", true);

        assertThat(result.isSuccess()).isTrue();
        verify(pluginRecordMapper).softDelete("PID-1");
        verify(adminEventLogService).record(any(AdminEventLog.class));
    }

    @Test
    @DisplayName("updateSettings should throw when plugin missing")
    void updateSettingsShouldThrowWhenMissing() {
        when(pluginRecordMapper.findByTenantAndPluginId("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.updateSettings("nope", Map.of("a", 1)))
                .isInstanceOf(PluginNotFoundException.class);
    }

    @Test
    @DisplayName("updateSettings should persist new settings via updateById")
    void updateSettingsShouldPersist() {
        PluginRecord r = recordWithStatus(PluginStatus.INSTALLED);
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p")).thenReturn(r);

        PluginOperationResult result = service.updateSettings("com.example.p", Map.of("k", "v"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(r.getSettings()).containsEntry("k", "v");
        verify(pluginRecordMapper).updateById(r);
    }

    @Test
    @DisplayName("getPluginByPid returns Optional based on mapper result")
    void getPluginByPidShouldReturnOptional() {
        when(pluginRecordMapper.findByPid("PID-1"))
                .thenReturn(recordWithStatus(PluginStatus.INSTALLED));

        Optional<PluginInfo> info = service.getPluginByPid("PID-1");

        assertThat(info).isPresent();
        assertThat(info.get().getPluginId()).isEqualTo("com.example.p");
    }

    @Test
    @DisplayName("getPluginByPid returns empty when mapper returns null")
    void getPluginByPidEmpty() {
        when(pluginRecordMapper.findByPid("X")).thenReturn(null);
        assertThat(service.getPluginByPid("X")).isEmpty();
    }

    @Test
    @DisplayName("getPluginByNamespace delegates to mapper findByTenantAndNamespace")
    void getPluginByNamespace() {
        when(pluginRecordMapper.findByTenantAndNamespace("ex"))
                .thenReturn(recordWithStatus(PluginStatus.INSTALLED));

        Optional<PluginInfo> info = service.getPluginByNamespace("ex");

        assertThat(info).isPresent();
        assertThat(info.get().getNamespace()).isEqualTo("ex");
    }

    @Test
    @DisplayName("getAllPlugins maps every record into PluginInfo")
    void getAllPluginsShouldMap() {
        when(pluginRecordMapper.findByTenant())
                .thenReturn(List.of(recordWithStatus(PluginStatus.INSTALLED),
                        recordWithStatus(PluginStatus.DISABLED)));

        List<PluginInfo> all = service.getAllPlugins();

        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("getPluginsByStatus filters by status code")
    void getPluginsByStatusShouldDelegate() {
        when(pluginRecordMapper.findByTenantAndStatus(PluginStatus.ENABLED.code()))
                .thenReturn(List.of(recordWithStatus(PluginStatus.ENABLED)));

        List<PluginInfo> enabled = service.getPluginsByStatus(PluginStatus.ENABLED);

        assertThat(enabled).hasSize(1);
    }

    @Test
    @DisplayName("getEnabledPlugins delegates to findEnabledByTenant")
    void getEnabledPluginsShouldDelegate() {
        when(pluginRecordMapper.findEnabledByTenant())
                .thenReturn(List.of(recordWithStatus(PluginStatus.ENABLED)));

        assertThat(service.getEnabledPlugins()).hasSize(1);
    }

    @Test
    @DisplayName("isPluginInstalled returns true when mapper has record")
    void isPluginInstalled() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.INSTALLED));
        assertThat(service.isPluginInstalled("com.example.p")).isTrue();
        assertThat(service.isPluginInstalled("missing")).isFalse();
    }

    @Test
    @DisplayName("isPluginEnabled checks both presence and ENABLED status")
    void isPluginEnabled() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p"))
                .thenReturn(recordWithStatus(PluginStatus.ENABLED));
        when(pluginRecordMapper.findByTenantAndPluginId("disabled"))
                .thenReturn(recordWithStatus(PluginStatus.DISABLED));

        assertThat(service.isPluginEnabled("com.example.p")).isTrue();
        assertThat(service.isPluginEnabled("disabled")).isFalse();
        assertThat(service.isPluginEnabled("absent")).isFalse();
    }

    @Test
    @DisplayName("isNamespaceAvailable delegates to mapper")
    void namespaceAvailable() {
        when(pluginRecordMapper.isNamespaceAvailable("ns")).thenReturn(true);
        assertThat(service.isNamespaceAvailable("ns")).isTrue();
    }

    @Test
    @DisplayName("registerPluginInstance and getPluginInstance round-trip")
    void registerAndGetInstance() {
        Plugin plugin = org.mockito.Mockito.mock(Plugin.class);
        when(plugin.getPluginId()).thenReturn("com.example.p");

        service.registerPluginInstance(plugin);

        assertThat(service.getPluginInstance("com.example.p")).isPresent();
        assertThat(service.getPluginInstance("missing")).isEmpty();

        service.unregisterPluginInstance("com.example.p");
        assertThat(service.getPluginInstance("com.example.p")).isEmpty();
    }

    @Test
    @DisplayName("removePf4jPlugin should soft-delete and clear extension cache")
    void removePf4jShouldCleanup() {
        PluginRecord r = recordWithStatus(PluginStatus.DISABLED);
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.p")).thenReturn(r);

        service.removePf4jPlugin("com.example.p");

        verify(pluginRecordMapper).softDelete("PID-1");
        verify(extensionRegistry).removePluginFromCache("com.example.p");
    }

    @Test
    @DisplayName("removePf4jPlugin tolerates absent record")
    void removePf4jWithMissing() {
        when(pluginRecordMapper.findByTenantAndPluginId("ghost")).thenReturn(null);

        service.removePf4jPlugin("ghost");

        verify(pluginRecordMapper, never()).softDelete(anyString());
        verify(extensionRegistry).removePluginFromCache("ghost");
    }

    @Test
    @DisplayName("syncPf4jPlugin returns empty when wrapper not found")
    void syncPf4jMissingWrapper() {
        when(auraPluginManager.getPluginWrapper("ghost")).thenReturn(null);
        assertThat(service.syncPf4jPlugin("ghost")).isEmpty();
    }

    @Test
    @DisplayName("getPf4jManager and getExtensionRegistry expose collaborators")
    void getCollaborators() {
        assertThat(service.getPf4jManager()).isSameAs(auraPluginManager);
        assertThat(service.getExtensionRegistry()).isSameAs(extensionRegistry);
    }

    @Test
    @DisplayName("install failure path captures audit log with error reason")
    void installFailurePathRecordsFailure() {
        PluginManifest m = validManifest();
        when(pluginRecordMapper.findByTenantAndNamespace("ex")).thenReturn(null);
        // Force insert failure
        org.mockito.Mockito.doThrow(new RuntimeException("db boom"))
                .when(pluginRecordMapper).insert(any(PluginRecord.class));

        PluginOperationResult result = service.install(m);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Installation failed");
        verify(adminEventLogService).record(any(AdminEventLog.class));
    }
}
