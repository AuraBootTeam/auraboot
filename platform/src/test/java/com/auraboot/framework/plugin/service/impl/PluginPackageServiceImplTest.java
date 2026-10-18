package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.packages.PackageStatusDTO;
import com.auraboot.framework.plugin.dto.packages.PackageUninstallResult;
import com.auraboot.framework.plugin.dto.packages.PackageManifest;
import com.auraboot.framework.plugin.dto.packages.PackageParseResult;
import com.auraboot.framework.plugin.entity.PluginPackageHistory;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.pf4j.PluginExtensionRegistryBridge;
import com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader;
import com.auraboot.framework.plugin.mapper.PluginPackageHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.service.PluginSignatureVerifier;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.service.PluginResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginPackageServiceImpl 分支覆盖单元测试（无 DB、无真实插件运行时）：
 * 包解析链（格式分发/清单缺失/清单校验失败/成功解析落历史）、卸载守卫
 * （插件缺失/组件跳过/软删/失败吞咽）、回滚三重守卫、状态查询。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginPackageServiceImpl 分支覆盖")
class PluginPackageServiceImplTest {

    @Mock
    private PluginPackageHistoryMapper packageHistoryMapper;
    @Mock
    private PluginRecordMapper pluginRecordMapper;
    @Mock
    private PluginResourceMapper pluginResourceMapper;
    @Mock
    private PluginImportService pluginImportService;
    @Mock
    private PluginResourceService pluginResourceService;
    @Mock
    private AuraPluginManager auraPluginManager;
    @Mock
    private ExtensionRegistry extensionRegistry;
    @Mock
    private PluginExtensionRegistryBridge pluginExtensionRegistryBridge;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private PluginDirectoryLoader directoryLoader;
    @Mock
    private PluginSignatureVerifier signatureVerifier;

    @InjectMocks
    private PluginPackageServiceImpl service;

    @org.junit.jupiter.api.io.TempDir
    Path sharedTemp;

    @BeforeEach
    void setUp() {
        MetaContext.setCurrentTenantId(42L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tempDir", sharedTemp.toString());
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private String manifestJson(String pluginId, String namespace, String version) {
        return "{\"pluginId\":\"" + pluginId + "\",\"namespace\":\"" + namespace
                + "\",\"version\":\"" + version + "\",\"displayName\":\"Demo Plugin\"}";
    }

    private PluginRecord pluginRecord(boolean hasComponents) {
        PluginRecord record = new PluginRecord();
        record.setPid("plg-1");
        record.setPluginId("demo-plugin");
        record.setNamespace("demo");
        record.setHasFrontend(hasComponents);
        record.setHasBackend(hasComponents);
        record.setHasConfig(hasComponents);
        return record;
    }

    @Nested
    @DisplayName("包解析")
    class Parse {

        @Test
        @DisplayName("不支持的扩展名失败")
        void unsupportedExtension(@TempDir Path tempDir) throws Exception {
            Path file = tempDir.resolve("plugin.tar");
            Files.writeString(file, "not a zip");

            PackageParseResult result = service.parsePackageFromPath(file);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("Unsupported file format");
        }

        @Test
        @DisplayName("zip 无清单 → Missing plugin.json manifest")
        void zipWithoutManifestFails(@TempDir Path tempDir) throws Exception {
            Path zip = tempDir.resolve("plugin.zip");
            try (var zipOut = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
                zipOut.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
                zipOut.write("hello".getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }

            PackageParseResult result = service.parsePackageFromStream(
                    new ByteArrayInputStream(Files.readAllBytes(zip)), "plugin.zip");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("Missing plugin.json manifest");
        }

        @Test
        @DisplayName("json 单文件包解析成功并落历史")
        void jsonManifestParses(@TempDir Path tempDir) throws Exception {
            String json = manifestJson("demo-plugin", "demo", "1.0.0");

            PackageParseResult result = service.parsePackageFromStream(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "plugin.json");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getManifest().getPluginId()).isEqualTo("demo-plugin");
            assertThat(result.getManifest().getVersion()).isEqualTo("1.0.0");
            ArgumentCaptor<PluginPackageHistory> captor =
                    ArgumentCaptor.forClass(PluginPackageHistory.class);
            verify(packageHistoryMapper).insert(captor.capture());
            assertThat(captor.getValue().getStatus())
                    .isEqualTo(PluginPackageHistory.PackageStatus.PENDING.code());
            assertThat(captor.getValue().getTenantId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("清单缺 namespace → 校验失败并列出错误，不落历史")
        void validationFailureListsErrors(@TempDir Path tempDir) throws Exception {
            Path dir = tempDir.resolve("pkg");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("plugin.json"),
                    manifestJson("demo-plugin", "", "1.0.0"), StandardCharsets.UTF_8);

            PackageParseResult result = service.parsePackageFromDirectory(dir);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getValidationErrors()).contains("namespace is required");
            verify(packageHistoryMapper, never()).insert(any(PluginPackageHistory.class));
        }

        @Test
        @DisplayName("目录无清单文件失败")
        void directoryWithoutManifest(@TempDir Path tempDir) {
            PackageParseResult result = service.parsePackageFromDirectory(tempDir);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("Missing plugin.json manifest");
        }
    }

    @Nested
    @DisplayName("卸载")
    class Uninstall {

        @Test
        @DisplayName("插件不存在失败")
        void missingPluginFails() {
            when(pluginRecordMapper.findByPid("ghost")).thenReturn(null);

            PackageUninstallResult result = service.uninstall("ghost",
                    com.auraboot.framework.plugin.dto.packages.PackageUninstallOptions.builder().build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("Plugin not found");
        }

        @Test
        @DisplayName("无组件插件卸载：软删并成功")
        void uninstallWithoutComponents() {
            when(pluginRecordMapper.findByPid("plg-1")).thenReturn(pluginRecord(false));

            PackageUninstallResult result = service.uninstall("plg-1",
                    com.auraboot.framework.plugin.dto.packages.PackageUninstallOptions.builder().build());

            assertThat(result.isSuccess()).isTrue();
            verify(pluginRecordMapper).softDelete("plg-1");
        }

        @Test
        @DisplayName("软删异常时失败并带错误信息")
        void softDeleteFailureCaptured() {
            when(pluginRecordMapper.findByPid("plg-1")).thenReturn(pluginRecord(false));
            when(pluginRecordMapper.softDelete("plg-1"))
                    .thenThrow(new IllegalStateException("delete refused"));

            PackageUninstallResult result = service.uninstall("plg-1",
                    com.auraboot.framework.plugin.dto.packages.PackageUninstallOptions.builder().build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("delete refused");
        }
    }

    @Nested
    @DisplayName("回滚与状态")
    class RollbackAndStatus {

        private PluginPackageHistory history(boolean success, boolean canRollback) {
            PluginPackageHistory history = PluginPackageHistory.builder()
                    .pid("pkg-1").pluginPid("plg-1").pluginId("demo-plugin")
                    .status(PluginPackageHistory.PackageStatus.SUCCESS.code())
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build();
            history.setStatus(success
                    ? PluginPackageHistory.PackageStatus.SUCCESS.code()
                    : PluginPackageHistory.PackageStatus.FAILED.code());
            history.setCanRollback(canRollback);
            return history;
        }

        @Test
        @DisplayName("canRollback 三条件")
        void canRollbackConditions() {
            when(packageHistoryMapper.findByPid("ghost")).thenReturn(null);
            assertThat(service.canRollback("ghost")).isFalse();

            when(packageHistoryMapper.findByPid("pkg-1")).thenReturn(history(true, true));
            assertThat(service.canRollback("pkg-1")).isTrue();

            when(packageHistoryMapper.findByPid("pkg-2")).thenReturn(history(true, false));
            assertThat(service.canRollback("pkg-2")).isFalse();
        }

        @Test
        @DisplayName("回滚守卫：历史缺失/非成功安装/不可回滚")
        void rollbackGuards() {
            when(packageHistoryMapper.findByPid("ghost")).thenReturn(null);
            assertThat(service.rollback("ghost").getError()).contains("Package history not found");

            when(packageHistoryMapper.findByPid("pkg-1")).thenReturn(history(false, true));
            assertThat(service.rollback("pkg-1").getError()).contains("Can only rollback successful installations");

            when(packageHistoryMapper.findByPid("pkg-2")).thenReturn(history(true, false));
            assertThat(service.rollback("pkg-2").getError()).contains("Rollback not available");
            verify(pluginRecordMapper, never()).softDelete(anyString());
        }

        @Test
        @DisplayName("状态查询：缺失返回 null，命中构建 DTO")
        void statusLookup() {
            when(pluginRecordMapper.findByPid("ghost")).thenReturn(null);
            assertThat(service.getStatus("ghost")).isNull();

            when(pluginRecordMapper.findByPid("plg-1")).thenReturn(pluginRecord(true));
            PackageStatusDTO status = service.getStatus("plg-1");
            assertThat(status).isNotNull();
            assertThat(status.getPluginId()).isEqualTo("demo-plugin");
        }
    }
}
