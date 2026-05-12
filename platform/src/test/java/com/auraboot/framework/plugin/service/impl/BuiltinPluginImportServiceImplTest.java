package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BuiltinPluginImportServiceImpl. Filesystem operations are isolated
 * via @TempDir; PluginImportService and PluginRecordMapper are mocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BuiltinPluginImportServiceImpl Unit Tests")
class BuiltinPluginImportServiceImplTest {

    @Mock private PluginImportService pluginImportService;
    @Mock private PluginRecordMapper pluginRecordMapper;
    @Mock private UserService userService;

    @InjectMocks private BuiltinPluginImportServiceImpl service;

    @BeforeEach
    void setupImportUser() {
        User user = new User();
        user.setId(1L);
        user.setPid("user-pid-1");
        user.setEmail("admin@auraboot.com");
        lenient().when(userService.findByUserId(anyLong())).thenReturn(user);
    }

    @AfterEach
    void teardown() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("importForTenant returns silently when builtin dir cannot be resolved")
    void shouldNoOpWhenBaseDirNotResolved(@TempDir Path tempDir) {
        // user.dir is the JVM CWD; with no override, both ../plugins and ./plugins
        // typically don't exist in the test workspace. We force-resolve via blank config.
        ReflectionTestUtils.setField(service, "builtinPluginsDir", "");

        // We can't fully guarantee absence of ../plugins from CWD, so test the explicit fallback:
        // set a non-existent path and verify execute is never called.
        ReflectionTestUtils.setField(service, "builtinPluginsDir",
                tempDir.resolve("definitely-not-here").toString());

        service.importForTenant(100L, 1L);

        verify(pluginImportService, never()).parseDirectory(anyString());
    }

    @Test
    @DisplayName("importForTenant skips a plugin when its directory is missing")
    void shouldSkipMissingPluginDir(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(service, "builtinPluginsDir", tempDir.toString());

        service.importForTenant(100L, 1L);

        // No org-management or platform-admin subdirs exist, so parse is never called.
        verify(pluginImportService, never()).parseDirectory(anyString());
    }

    @Test
    @DisplayName("importForTenant skips when on-disk version equals DB version")
    void shouldSkipUpToDate(@TempDir Path tempDir) throws IOException {
        // Create the org-management subdir that the service expects.
        Path pluginDir = tempDir.resolve("org-management");
        Files.createDirectories(pluginDir);
        Path adminDir = tempDir.resolve("platform-admin");
        Files.createDirectories(adminDir);

        ReflectionTestUtils.setField(service, "builtinPluginsDir", tempDir.toString());

        ImportPreviewResult validPreview = ImportPreviewResult.builder()
                .valid(true)
                .importId("IMP-OM")
                .pluginId("com.auraboot.org-management")
                .version("1.0.0")
                .build();
        when(pluginImportService.parseDirectory(anyString())).thenReturn(validPreview);

        PluginRecord upToDate = PluginRecord.builder()
                .pluginId("com.auraboot.org-management")
                .version("1.0.0")
                .build();
        when(pluginRecordMapper.findByTenantAndPluginId(anyString())).thenReturn(upToDate);

        service.importForTenant(100L, 1L);

        // parseDirectory called once per plugin in BUILTIN_PLUGINS but execute should NOT
        // be invoked since versions match.
        verify(pluginImportService, never()).execute(anyString(), any(ImportRequest.class));
    }

    @Test
    @DisplayName("importForTenant invokes execute when version differs from DB")
    void shouldImportWhenVersionDiffers(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("org-management"));
        Files.createDirectories(tempDir.resolve("platform-admin"));
        ReflectionTestUtils.setField(service, "builtinPluginsDir", tempDir.toString());

        ImportPreviewResult preview = ImportPreviewResult.builder()
                .valid(true).importId("IMP").pluginId("any").version("2.0.0").build();
        when(pluginImportService.parseDirectory(anyString())).thenReturn(preview);

        PluginRecord oldVersion = PluginRecord.builder()
                .pluginId("any").version("1.0.0").build();
        when(pluginRecordMapper.findByTenantAndPluginId(anyString())).thenReturn(oldVersion);

        ImportExecuteResult successResult = ImportExecuteResult.builder()
                .success(true).importId("IMP").pluginId("any").durationMs(50L).build();
        when(pluginImportService.execute(eq("IMP"), any(ImportRequest.class))).thenReturn(successResult);

        service.importForTenant(100L, 1L);

        verify(pluginImportService, org.mockito.Mockito.atLeastOnce())
                .execute(eq("IMP"), any(ImportRequest.class));
    }

    @Test
    @DisplayName("importForTenant sets user pid in MetaContext during plugin execution")
    void shouldSetUserPidInMetaContextDuringExecute(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("org-management"));
        ReflectionTestUtils.setField(service, "builtinPluginsDir", tempDir.toString());

        ImportPreviewResult preview = ImportPreviewResult.builder()
                .valid(true)
                .importId("IMP")
                .pluginId("com.auraboot.org-management")
                .version("1.0.0")
                .build();
        when(pluginImportService.parseDirectory(anyString())).thenReturn(preview);
        when(pluginRecordMapper.findByTenantAndPluginId(anyString())).thenReturn(null);
        when(pluginImportService.execute(eq("IMP"), any(ImportRequest.class))).thenAnswer(invocation -> {
            org.assertj.core.api.Assertions.assertThat(MetaContext.get().getUserPid())
                    .isEqualTo("user-pid-1");
            org.assertj.core.api.Assertions.assertThat(MetaContext.get().getUsername())
                    .isEqualTo("admin@auraboot.com");
            return ImportExecuteResult.builder()
                    .success(true)
                    .importId("IMP")
                    .pluginId("com.auraboot.org-management")
                    .durationMs(50L)
                    .build();
        });

        service.importForTenant(100L, 1L);

        verify(pluginImportService).execute(eq("IMP"), any(ImportRequest.class));
    }

    @Test
    @DisplayName("importForTenant skips invalid preview (validation failures)")
    void shouldSkipInvalidPreview(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("org-management"));
        Files.createDirectories(tempDir.resolve("platform-admin"));
        ReflectionTestUtils.setField(service, "builtinPluginsDir", tempDir.toString());

        ImportPreviewResult invalid = ImportPreviewResult.builder()
                .valid(false)
                .errors(java.util.List.of("missing manifest"))
                .build();
        when(pluginImportService.parseDirectory(anyString())).thenReturn(invalid);

        service.importForTenant(100L, 1L);

        verify(pluginImportService, never()).execute(anyString(), any(ImportRequest.class));
    }

    @Test
    @DisplayName("demo import ignores removed acp-showcase script-only directory")
    void shouldNotImportRemovedAcpShowcaseDirectory(@TempDir Path tempDir) throws IOException {
        String[] pluginDirs = {
                "core-meta",
                "core-bpm",
                "core-aurabot",
                "page-manager",
                "org-management",
                "platform-admin",
                "crm-starter",
                "showcase",
                "agent-control-plane",
                "workflow-demo",
                "acp-showcase"
        };
        for (String pluginDir : pluginDirs) {
            Files.createDirectories(tempDir.resolve(pluginDir));
        }
        ReflectionTestUtils.setField(service, "builtinPluginsDir", tempDir.toString());

        ImportPreviewResult validPreview = ImportPreviewResult.builder()
                .valid(true)
                .importId("IMP")
                .pluginId("any")
                .version("1.0.0")
                .build();
        when(pluginImportService.parseDirectory(anyString())).thenReturn(validPreview);

        PluginRecord upToDate = PluginRecord.builder()
                .pluginId("any")
                .version("1.0.0")
                .build();
        when(pluginRecordMapper.findByTenantAndPluginId(anyString())).thenReturn(upToDate);

        service.importForTenant(100L, 1L, true);

        verify(pluginImportService, never())
                .parseDirectory(argThat(path -> path != null && path.endsWith("acp-showcase")));
    }
}
