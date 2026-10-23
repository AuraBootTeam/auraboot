package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.lock.DistributedLock;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.dto.uninstall.UninstallRequest;
import com.auraboot.framework.plugin.entity.PluginImportHistory;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginImportHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the public orchestration before a mixed batch reaches any resource writer. */
class PluginOptionalAgentCapabilityTest {
    @AfterEach
    void clearTenant() { MetaContext.clear(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedPreviewAndDirectApplyRejectBeforeHistoryOrOrdinaryResourcesAreWritten(boolean apply) {
        var service = mock(PluginImportServiceImpl.class, CALLS_REAL_METHODS);
        var history = mock(PluginImportHistoryMapper.class);
        var resources = mock(PluginResourceImporter.class);
        var plugins = mock(PluginRecordMapper.class);
        ReflectionTestUtils.setField(service, "importHistoryMapper", history);
        ReflectionTestUtils.setField(service, "resourceImporter", resources);
        ReflectionTestUtils.setField(service, "pluginRecordMapper", plugins);
        assertMissing(() -> {
            if (apply) service.executeFromManifest(mixedManifest(), new ImportRequest());
            else service.previewFromManifest(mixedManifest());
        });
        verifyNoInteractions(history, resources, plugins);
    }

    @Test
    void navigationOnlyPluginWithRequiredAgentRejectsBeforeAnyResourceWrites() {
        var service = mock(PluginImportServiceImpl.class, CALLS_REAL_METHODS);
        var history = mock(PluginImportHistoryMapper.class);
        var resources = mock(PluginResourceImporter.class);
        ReflectionTestUtils.setField(service, "importHistoryMapper", history);
        ReflectionTestUtils.setField(service, "resourceImporter", resources);
        var manifest = new PluginManifestExtended();
        manifest.setPluginId("com.auraboot.core-aurabot");
        manifest.setNamespace("core-aurabot");
        manifest.setVersion("1.0.0");
        manifest.setApplicationModules(List.of("agent"));
        assertMissing(() -> service.executeFromManifest(manifest, new ImportRequest()));
        verifyNoInteractions(history, resources);
    }

    @Test
    void installedModuleDoesNotRejectOrdinaryOrRequiredAgentManifests() {
        var adapter = mock(com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle.class);
        var manifest = new PluginManifestExtended();
        PluginAgentResourceGuard.requireManifest(manifest, null);
        manifest.setApplicationModules(List.of("agent"));
        PluginAgentResourceGuard.requireManifest(manifest, adapter);
        manifest.setApplicationModules(List.of("unknown"));
        assertThatThrownBy(() -> PluginAgentResourceGuard.requireManifest(manifest, adapter))
                .isInstanceOf(PluginException.class).hasMessageContaining("Unknown required application module");
    }

    @Test
    void cachedApplyRejectsBeforeStaleImportCleanupOrDistributedLock() throws Exception {
        var service = mock(PluginImportServiceImpl.class, CALLS_REAL_METHODS);
        var history = mock(PluginImportHistoryMapper.class);
        var lock = mock(DistributedLock.class);
        ReflectionTestUtils.setField(service, "importHistoryMapper", history);
        ReflectionTestUtils.setField(service, "distributedLock", lock);
        var type = Class.forName(PluginImportServiceImpl.class.getName() + "$ImportContext");
        var constructor = type.getDeclaredConstructor(PluginManifestExtended.class, PluginImportHistory.class,
                ImportPreviewResult.class);
        constructor.setAccessible(true);
        var cache = new ConcurrentHashMap<String, Object>();
        cache.put("import", constructor.newInstance(mixedManifest(), new PluginImportHistory(), null));
        ReflectionTestUtils.setField(service, "importContextCache", cache);
        assertMissing(() -> service.execute("import", new ImportRequest()));
        verifyNoInteractions(history, lock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledger", "created", "updated"})
    void rollbackPreflightsEveryLedgerBeforeTheFirstOrdinaryRollback(String owner) {
        var service = mock(PluginImportServiceImpl.class, CALLS_REAL_METHODS);
        var history = mock(PluginImportHistoryMapper.class);
        var resources = mock(PluginResourceMapper.class);
        var importer = mock(PluginResourceImporter.class);
        var rename = mock(ModelFieldRenameMigrator.class);
        ReflectionTestUtils.setField(service, "importHistoryMapper", history);
        ReflectionTestUtils.setField(service, "pluginResourceMapper", resources);
        ReflectionTestUtils.setField(service, "resourceImporter", importer);
        ReflectionTestUtils.setField(service, "modelFieldRenameMigrator", rename);
        MetaContext.setContext(42L, 7L, null, null);
        var record = PluginImportHistory.builder().importId("import").tenantId(42L)
                .pluginPid("plugin").status(ImportStatus.SUCCESS.code()).build();
        when(history.findByImportId("import")).thenReturn(record);
        var ordinary = resource(ResourceType.MODEL);
        var mixed = List.of(ordinary, resource(ResourceType.AGENT_DEFINITION));
        when(resources.findByPluginPid("plugin")).thenReturn("ledger".equals(owner) ? mixed : List.of(ordinary));
        when(resources.findCreatedResourcesForRollback(42L, "plugin", "import"))
                .thenReturn("created".equals(owner) ? mixed : List.of(ordinary));
        when(resources.findUpdatedResourcesForRollback(42L, "plugin", "import"))
                .thenReturn("updated".equals(owner) ? mixed : List.of(ordinary));
        assertMissing(() -> service.rollback("import"));
        verifyNoInteractions(importer, rename);
        verify(history, never()).updateById(any(PluginImportHistory.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uninstallPreflightsTheWholeMixedLedgerBeforeAnyTableReadOrWrite(boolean apply) {
        var service = mock(PluginResourceServiceImpl.class, CALLS_REAL_METHODS);
        var resources = mock(PluginResourceMapper.class);
        var plugins = mock(PluginRecordMapper.class);
        var jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(service, "resourceMapper", resources);
        ReflectionTestUtils.setField(service, "pluginRecordMapper", plugins);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        when(plugins.findByPid("plugin")).thenReturn(new PluginRecord());
        when(resources.findByPluginPid("plugin")).thenReturn(List.of(
                resource(ResourceType.MODEL), resource(ResourceType.AGENT_DEFINITION)));
        assertMissing(() -> {
            if (apply) service.executeUninstall("plugin", 42L, new UninstallRequest());
            else service.generateUninstallPreview("plugin", 42L);
        });
        verifyNoInteractions(jdbc);
        verify(resources, never()).updateById(any(PluginResource.class));
        verify(plugins, never()).updateById(any(PluginRecord.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "detach", "snapshot", "rollback", "restore", "exists", "import"})
    void individualLifecycleEntryPointsAlsoRejectWithoutAnAdapter(String operation) {
        var service = mock(PluginResourceServiceImpl.class, CALLS_REAL_METHODS);
        var importer = mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);
        var jdbc = mock(JdbcTemplate.class);
        var resources = mock(PluginResourceMapper.class);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "resourceMapper", resources);
        ReflectionTestUtils.setField(importer, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(importer, "pluginResourceMapper", resources);
        var agent = resource(ResourceType.AGENT_DEFINITION);
        assertMissing(() -> {
            switch (operation) {
                case "delete" -> service.deleteResource(agent);
                case "detach" -> service.detachResource(agent);
                case "snapshot" -> service.getCurrentDatabaseState(42L, ResourceType.AGENT_DEFINITION, "agent");
                case "rollback" -> importer.rollbackResource(agent);
                case "restore" -> importer.restoreResource(agent);
                case "exists" -> importer.checkAgentDefinitionExists(42L, "agent");
                case "import" -> importer.importAgentDefinition(new AgentDefinitionDTO(), "plugin", "import", 42L,
                        ImportRequest.ConflictStrategy.OVERWRITE);
                default -> throw new AssertionError(operation);
            }
        });
        verifyNoInteractions(jdbc, resources);
    }

    private PluginManifestExtended mixedManifest() {
        return PluginManifestExtended.builder().pluginId("test-mixed").namespace("test").version("1.0.0")
                .models(List.of(ModelDefinitionDTO.builder().code("ordinary").build()))
                .agentDefinitions(List.of(AgentDefinitionDTO.builder().agentCode("agent")
                        .evalCases(List.of(new CapabilityEvalCase())).build())).build();
    }

    private PluginResource resource(ResourceType type) {
        var resource = new PluginResource();
        resource.setResourceTypeEnum(type);
        resource.setResourceCode(type.code());
        resource.setResourcePid("resource");
        return resource;
    }

    private void assertMissing(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(PluginException.class).hasMessageContaining("not installed");
    }
}
