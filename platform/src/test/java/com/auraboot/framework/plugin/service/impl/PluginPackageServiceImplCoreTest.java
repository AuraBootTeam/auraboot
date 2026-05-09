package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.packages.PackageHistoryDTO;
import com.auraboot.framework.plugin.dto.packages.PackageInstallOptions;
import com.auraboot.framework.plugin.dto.packages.PackageInstallResult;
import com.auraboot.framework.plugin.dto.packages.PackageStatusDTO;
import com.auraboot.framework.plugin.dto.packages.PackageUninstallResult;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Core orchestration-path tests for {@link PluginPackageServiceImpl} that complement
 * {@link PluginPackageServiceImplBranchTest}. Covers:
 * <ul>
 *   <li>installFromFile / installFromPath / installFromStream failure delegation</li>
 *   <li>rollback when uninstall fails because plugin no longer exists</li>
 *   <li>cleanupTempFiles when temp dir is absent</li>
 *   <li>cancelInstallation happy path with seeded cache</li>
 *   <li>getStatus full DTO mapping with backend & frontend fields populated</li>
 *   <li>getPluginHistory happy path mapping</li>
 *   <li>parsePackage MultipartFile unsupported extension</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginPackageServiceImplCoreTest {

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
    void setUp() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
        MetaContext.setContext(100L, 1L, "U-1", "tester");
        ReflectionTestUtils.setField(service, "tempDir",
                System.getProperty("java.io.tmpdir") + "/aura-plugins-core-test");
        ReflectionTestUtils.setField(service, "pluginsDir", "plugins");
        ReflectionTestUtils.setField(service, "frontendPluginsDir", "frontend-plugins");
    }

    @AfterEach
    void cleanup() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
    }

    // ==================== installFromFile / Path / Stream ====================

    @Test
    @DisplayName("installFromFile delegates failure when parse rejects unsupported file")
    void installFromFile_unsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[0]);

        PackageInstallResult result = service.installFromFile(file, new PackageInstallOptions());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unsupported file format");
    }

    @Test
    @DisplayName("installFromStream delegates failure when filename missing")
    void installFromStream_unsupportedExtension() {
        PackageInstallResult result = service.installFromStream(
                new ByteArrayInputStream(new byte[0]), "noextension", new PackageInstallOptions());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unsupported file format");
    }

    @Test
    @DisplayName("installFromPath fails parse when path is unsupported file extension")
    void installFromPath_unsupportedExtension() throws Exception {
        Path tempFile = Files.createTempFile("aura-test-", ".bogus");
        try {
            PackageInstallResult result = service.installFromPath(tempFile, new PackageInstallOptions());
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("Unsupported file format");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("parsePackage from MultipartFile: unsupported extension returns failure")
    void parsePackage_unsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "weird.txt", "text/plain", "hello".getBytes());

        var result = service.parsePackage(file);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unsupported file format");
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback proceeds for success+canRollback history but fails on missing plugin record")
    void rollback_uninstallMissingPlugin() {
        PluginPackageHistory history = PluginPackageHistory.builder()
                .pid("hist-1")
                .pluginPid("plug-pid")
                .pluginId("com.demo")
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                .canRollback(true)
                .build();
        when(packageHistoryMapper.findByPid("hist-1")).thenReturn(history);
        when(pluginRecordMapper.findByPid("plug-pid")).thenReturn(null);

        PackageUninstallResult result = service.rollback("hist-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("Plugin not found");
        // markRolledBack must not be called when uninstall fails
        verify(packageHistoryMapper, org.mockito.Mockito.never()).markRolledBack(anyString());
    }

    // ==================== cleanupTempFiles ====================

    @Test
    @DisplayName("cleanupTempFiles returns 0 when temp directory does not exist")
    void cleanupTempFiles_missingDir() {
        ReflectionTestUtils.setField(service, "tempDir",
                System.getProperty("java.io.tmpdir") + "/aura-no-such-dir-" + System.nanoTime());

        int cleaned = service.cleanupTempFiles();

        assertThat(cleaned).isEqualTo(0);
    }

    // ==================== cancelInstallation ====================

    @Test
    @DisplayName("cancelInstallation removes cached context and updates status")
    @SuppressWarnings("unchecked")
    void cancelInstallation_happyPath() throws Exception {
        // Seed the package context cache via reflection.
        Map<String, Object> cache = (Map<String, Object>)
                ReflectionTestUtils.getField(service, "packageContextCache");
        assertThat(cache).isNotNull();

        // Use the inner PackageContext class via reflection — a simple stub object isn't
        // the right type, but we just need a non-null entry. Use the real type.
        Class<?> contextClass = Class.forName(
                "com.auraboot.framework.plugin.service.impl.PluginPackageServiceImpl$PackageContext");
        java.lang.reflect.Constructor<?> ctor = contextClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object context = ctor.newInstance("pkg-1", null, Paths.get("/tmp"), null, null);
        cache.put("pkg-1", context);

        boolean result = service.cancelInstallation("pkg-1");

        assertThat(result).isTrue();
        assertThat(cache).doesNotContainKey("pkg-1");
        verify(packageHistoryMapper).updateStatus("pkg-1", "cancelled");
    }

    // ==================== getStatus full mapping ====================

    @Test
    @DisplayName("getStatus maps all backend & frontend fields onto DTO")
    void getStatus_fullFieldsMapped() {
        PluginRecord record = PluginRecord.builder()
                .pid("p1")
                .pluginId("com.demo")
                .namespace("demo")
                .version("2.0.0")
                .displayName("Demo")
                .status("active")
                .hasConfig(true)
                .hasBackend(true)
                .backendStatus("started")
                .backendPluginId("backend-1")
                .backendError(null)
                .hasFrontend(true)
                .frontendStatus("deployed")
                .frontendRemoteUrl("/plugins/demo/remoteEntry.js")
                .frontendError(null)
                .build();
        when(pluginRecordMapper.findByPid("p1")).thenReturn(record);

        PackageStatusDTO dto = service.getStatus("p1");

        assertThat(dto).isNotNull();
        assertThat(dto.getPluginPid()).isEqualTo("p1");
        assertThat(dto.getPluginId()).isEqualTo("com.demo");
        assertThat(dto.getNamespace()).isEqualTo("demo");
        assertThat(dto.getVersion()).isEqualTo("2.0.0");
        assertThat(dto.isHasBackend()).isTrue();
        assertThat(dto.getBackendPluginId()).isEqualTo("backend-1");
        assertThat(dto.getBackendStatus()).isEqualTo("started");
        assertThat(dto.isHasFrontend()).isTrue();
        assertThat(dto.getFrontendRemoteUrl()).isEqualTo("/plugins/demo/remoteEntry.js");
    }

    @Test
    @DisplayName("getStatusByPluginId maps DTO when found via tenant lookup")
    void getStatusByPluginId_found() {
        PluginRecord record = PluginRecord.builder()
                .pid("p2")
                .pluginId("com.x")
                .namespace("x")
                .version("1.0.0")
                .status("active")
                .build();
        when(pluginRecordMapper.findByTenantAndPluginId("com.x")).thenReturn(record);

        PackageStatusDTO dto = service.getStatusByPluginId("com.x");

        assertThat(dto).isNotNull();
        assertThat(dto.getPluginPid()).isEqualTo("p2");
    }

    // ==================== getPluginHistory ====================

    @Test
    @DisplayName("getPluginHistory returns mapped DTOs for the plugin's history chain")
    void getPluginHistory_happyPath() {
        PluginPackageHistory latest = PluginPackageHistory.builder()
                .pid("h-latest")
                .pluginPid("plug-1")
                .pluginId("com.demo")
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                .build();
        when(packageHistoryMapper.findLatestByTenantAndPluginId(any(), eq("com.demo"))).thenReturn(latest);

        PluginPackageHistory entry1 = PluginPackageHistory.builder()
                .pid("h1").pluginPid("plug-1").pluginId("com.demo")
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code()).build();
        PluginPackageHistory entry2 = PluginPackageHistory.builder()
                .pid("h2").pluginPid("plug-1").pluginId("com.demo")
                .status(PluginPackageHistory.PackageStatus.FAILED.code()).build();
        when(packageHistoryMapper.findByPluginPid("plug-1")).thenReturn(Arrays.asList(entry1, entry2));

        List<PackageHistoryDTO> result = service.getPluginHistory("com.demo");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PackageHistoryDTO::getPid).containsExactly("h1", "h2");
    }

    // ==================== getHistoryRecord with config counts ====================

    @Test
    @DisplayName("getHistoryRecord copies numeric config resource counts into DTO map")
    void getHistoryRecord_configCounts() {
        Map<String, Object> counts = new HashMap<>();
        counts.put("models_create", 3);
        counts.put("fields_create", 7);
        counts.put("ignored_string", "not-a-number"); // filtered out — not a Number

        PluginPackageHistory history = PluginPackageHistory.builder()
                .pid("h1")
                .pluginId("com.demo")
                .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                .canRollback(true)
                .configResourceCounts(counts)
                .build();
        when(packageHistoryMapper.findByPid("h1")).thenReturn(history);

        PackageHistoryDTO dto = service.getHistoryRecord("h1");

        assertThat(dto).isNotNull();
        assertThat(dto.getConfigResourceCounts()).containsEntry("models_create", 3)
                .containsEntry("fields_create", 7)
                .doesNotContainKey("ignored_string");
    }
}
