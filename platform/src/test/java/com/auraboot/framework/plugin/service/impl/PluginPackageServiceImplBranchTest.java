package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.packages.PackageHistoryDTO;
import com.auraboot.framework.plugin.dto.packages.PackageInstallOptions;
import com.auraboot.framework.plugin.dto.packages.PackageInstallResult;
import com.auraboot.framework.plugin.dto.packages.PackageParseResult;
import com.auraboot.framework.plugin.dto.packages.PackageStatusDTO;
import com.auraboot.framework.plugin.dto.packages.PackageUninstallOptions;
import com.auraboot.framework.plugin.dto.packages.PackageUninstallResult;
import com.auraboot.framework.plugin.dto.uninstall.UninstallPreviewResult;
import com.auraboot.framework.plugin.entity.PluginPackageHistory;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginPackageHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.service.PluginResourceService;
import com.auraboot.framework.plugin.service.PluginSignatureVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito branch tests for {@link PluginPackageServiceImpl}, covering the
 * fail-fast / null-result / DTO-mapping paths that do not require real filesystem
 * extraction or DB access. Targets early returns in install/uninstall/rollback
 * and the simple read paths (getStatus/getHistory/canRollback/cancelInstallation).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginPackageServiceImplBranchTest {

    @Mock private PluginPackageHistoryMapper packageHistoryMapper;
    @Mock private PluginRecordMapper pluginRecordMapper;
    @Mock private PluginResourceMapper pluginResourceMapper;
    @Mock private PluginImportService pluginImportService;
    @Mock private PluginResourceService pluginResourceService;
    @Mock private AuraPluginManager auraPluginManager;
    @Mock private ExtensionRegistry extensionRegistry;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private PluginDirectoryLoader directoryLoader;
    @Mock private PluginSignatureVerifier signatureVerifier;

    @InjectMocks private PluginPackageServiceImpl service;

    @BeforeEach
    void setUpContext() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
        MetaContext.setContext(100L, 1L, "U-1", "tester");
        // tempDir is @Value-injected at runtime; populate it for stream parsing tests.
        ReflectionTestUtils.setField(service, "tempDir",
                System.getProperty("java.io.tmpdir") + "/aura-plugins-test");
    }

    @AfterEach
    void clearContext() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("install returns failure when packageId is unknown to context cache")
    void install_unknownPackageId_returnsFailure() {
        PackageInstallResult result = service.install("unknown-pid", new PackageInstallOptions());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Package not found");
        assertThat(result.getPackageId()).isEqualTo("unknown-pid");
    }

    @Test
    @DisplayName("parsePackageFromStream returns failure for unsupported file extension")
    void parsePackageFromStream_unsupportedExtension() {
        PackageParseResult result = service.parsePackageFromStream(
                new ByteArrayInputStream(new byte[0]), "evil.exe");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unsupported file format");
    }

    @Test
    @DisplayName("parsePackageFromStream returns failure when filename has no extension")
    void parsePackageFromStream_emptyFilename() {
        PackageParseResult result = service.parsePackageFromStream(
                new ByteArrayInputStream(new byte[0]), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unsupported file format");
    }

    @Test
    @DisplayName("uninstall returns failure when plugin record not found")
    void uninstall_pluginNotFound() {
        when(pluginRecordMapper.findByPid("missing")).thenReturn(null);

        PackageUninstallResult result = service.uninstall("missing", new PackageUninstallOptions());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("Plugin not found");
        assertThat(result.getPluginPid()).isEqualTo("missing");
    }

    @Test
    @DisplayName("getUninstallPreview delegates to pluginResourceService")
    void getUninstallPreview_delegates() {
        UninstallPreviewResult expected = UninstallPreviewResult.builder().build();
        when(pluginResourceService.generateUninstallPreview(eq("pid-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);

        UninstallPreviewResult actual = service.getUninstallPreview("pid-1");

        assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("rollback returns failure when history not found")
    void rollback_historyNotFound() {
        when(packageHistoryMapper.findByPid("nope")).thenReturn(null);

        PackageUninstallResult result = service.rollback("nope");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Package history not found");
    }

    @Test
    @DisplayName("rollback returns failure when history is not a successful install")
    void rollback_notSuccess() {
        PluginPackageHistory history = PluginPackageHistory.builder()
                .pid("h1")
                .pluginPid("p1")
                .pluginId("plug-1")
                .status(PluginPackageHistory.PackageStatus.FAILED.code())
                .canRollback(true)
                .build();
        when(packageHistoryMapper.findByPid("h1")).thenReturn(history);

        PackageUninstallResult result = service.rollback("h1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Can only rollback successful installations");
    }

    @Test
    @DisplayName("rollback returns failure when canRollback flag is false")
    void rollback_cannotRollback() {
        PluginPackageHistory history = PluginPackageHistory.builder()
                .pid("h2")
                .pluginPid("p2")
                .pluginId("plug-2")
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                .canRollback(false)
                .build();
        when(packageHistoryMapper.findByPid("h2")).thenReturn(history);

        PackageUninstallResult result = service.rollback("h2");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Rollback not available");
    }

    @Test
    @DisplayName("canRollback false when history not found")
    void canRollback_noHistory() {
        when(packageHistoryMapper.findByPid("x")).thenReturn(null);
        assertThat(service.canRollback("x")).isFalse();
    }

    @Test
    @DisplayName("canRollback false when history is not success")
    void canRollback_notSuccess() {
        PluginPackageHistory history = PluginPackageHistory.builder()
                .status(PluginPackageHistory.PackageStatus.FAILED.code())
                .canRollback(true)
                .build();
        when(packageHistoryMapper.findByPid("x")).thenReturn(history);
        assertThat(service.canRollback("x")).isFalse();
    }

    @Test
    @DisplayName("canRollback false when canRollback flag absent")
    void canRollback_flagFalse() {
        PluginPackageHistory history = PluginPackageHistory.builder()
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                .canRollback(false)
                .build();
        when(packageHistoryMapper.findByPid("x")).thenReturn(history);
        assertThat(service.canRollback("x")).isFalse();
    }

    @Test
    @DisplayName("canRollback true on success+canRollback")
    void canRollback_true() {
        PluginPackageHistory history = PluginPackageHistory.builder()
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                .canRollback(true)
                .build();
        when(packageHistoryMapper.findByPid("x")).thenReturn(history);
        assertThat(service.canRollback("x")).isTrue();
    }

    @Test
    @DisplayName("getStatus returns null when plugin record missing")
    void getStatus_null() {
        when(pluginRecordMapper.findByPid("missing")).thenReturn(null);
        assertThat(service.getStatus("missing")).isNull();
    }

    @Test
    @DisplayName("getStatus builds DTO from PluginRecord")
    void getStatus_returnsDto() {
        PluginRecord record = PluginRecord.builder()
                .pid("p1")
                .pluginId("com.demo")
                .namespace("demo")
                .version("1.0.0")
                .displayName("Demo")
                .status("active")
                .hasConfig(true)
                .hasBackend(false)
                .hasFrontend(true)
                .build();
        when(pluginRecordMapper.findByPid("p1")).thenReturn(record);

        PackageStatusDTO dto = service.getStatus("p1");

        assertThat(dto).isNotNull();
        assertThat(dto.getPluginPid()).isEqualTo("p1");
        assertThat(dto.getPluginId()).isEqualTo("com.demo");
        assertThat(dto.isHasConfig()).isTrue();
        assertThat(dto.isHasBackend()).isFalse();
        assertThat(dto.isHasFrontend()).isTrue();
    }

    @Test
    @DisplayName("getStatusByPluginId returns null when not found")
    void getStatusByPluginId_null() {
        when(pluginRecordMapper.findByTenantAndPluginId("ghost")).thenReturn(null);
        assertThat(service.getStatusByPluginId("ghost")).isNull();
    }

    @Test
    @DisplayName("getHistoryRecord returns null when not found")
    void getHistoryRecord_null() {
        when(packageHistoryMapper.findByPid("missing")).thenReturn(null);
        assertThat(service.getHistoryRecord("missing")).isNull();
    }

    @Test
    @DisplayName("getHistoryRecord maps to DTO when found")
    void getHistoryRecord_dto() {
        PluginPackageHistory history = PluginPackageHistory.builder()
                .pid("h")
                .pluginPid("pp")
                .pluginId("plug")
                .namespace("ns")
                .version("1.0.0")
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                .canRollback(true)
                .build();
        when(packageHistoryMapper.findByPid("h")).thenReturn(history);

        PackageHistoryDTO dto = service.getHistoryRecord("h");

        assertThat(dto).isNotNull();
        assertThat(dto.getPid()).isEqualTo("h");
        assertThat(dto.getPluginId()).isEqualTo("plug");
        assertThat(dto.isCanRollback()).isTrue();
    }

    @Test
    @DisplayName("cancelInstallation returns false when no context cached")
    void cancelInstallation_noContext() {
        assertThat(service.cancelInstallation("nope")).isFalse();
        verify(packageHistoryMapper, never()).updateStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("getPluginHistory returns empty when no latest history found")
    void getPluginHistory_empty() {
        when(packageHistoryMapper.findLatestByTenantAndPluginId(org.mockito.ArgumentMatchers.any(), eq("ghost")))
                .thenReturn(null);

        List<PackageHistoryDTO> result = service.getPluginHistory("ghost");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getHistory returns empty when mapper returns empty list")
    void getHistory_empty() {
        when(packageHistoryMapper.findRecentByTenant(org.mockito.ArgumentMatchers.any(), eq(10)))
                .thenReturn(Collections.emptyList());

        List<PackageHistoryDTO> result = service.getHistory(10);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getHistory maps records to DTOs")
    void getHistory_mapsRecords() {
        PluginPackageHistory h1 = PluginPackageHistory.builder()
                .pid("a").pluginId("p1").status(PluginPackageHistory.PackageStatus.SUCCESS.code()).build();
        PluginPackageHistory h2 = PluginPackageHistory.builder()
                .pid("b").pluginId("p2").status(PluginPackageHistory.PackageStatus.FAILED.code()).build();
        when(packageHistoryMapper.findRecentByTenant(org.mockito.ArgumentMatchers.any(), eq(5)))
                .thenReturn(Arrays.asList(h1, h2));

        List<PackageHistoryDTO> result = service.getHistory(5);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PackageHistoryDTO::getPid).containsExactly("a", "b");
    }
}
