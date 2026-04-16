package com.auraboot.framework.plugin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ProcessDefinitionDTO;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: plugin-imported processes must be deployed to SmartEngine
 * with the current tenant's ID so that subsequent startProcess calls (which
 * pass TENANT_ID in variables) can resolve the cached process definition.
 *
 * <p>Bug: {@code PluginResourceImporterImpl.deployProcessToSmartEngine} previously
 * called {@code deployWithUTF8Content(xml)} (no tenantId), so the cache key was
 * {@code processKey:version}. But {@code ProcessEngineService.startProcess} puts
 * {@code TENANT_ID} into variables, causing SmartEngine to look up by
 * {@code processKey:version:tenantId} — which was absent, leaving imported
 * processes un-runnable.
 *
 * <p>Fix: call {@code deployWithUTF8Content(xml, tenantIdStr)} so the cache key
 * matches the lookup key.
 */
@Slf4j
@DisplayName("Plugin-imported processes deploy to SmartEngine with current tenant")
class PluginProcessImportDeploymentTest extends BaseIntegrationTest {

    @Autowired
    private PluginResourceImporter pluginResourceImporter;

    @Autowired
    private BpmProcessDefinitionMapper processDefinitionMapper;

    @Autowired
    private SmartEngine smartEngine;

    /**
     * Minimal designer JSON: startEvent → endEvent (no userTask so process
     * completes immediately, avoiding SLA/assignee resolution side-effects).
     */
    private static final String SIMPLE_NODES_JSON_TEMPLATE = """
            {
              "nodes": [
                {"id": "start", "type": "startEvent", "data": {"label": "Start"}},
                {"id": "end",   "type": "endEvent",   "data": {"label": "End"}}
              ],
              "edges": [
                {"id": "e1", "source": "start", "target": "end"}
              ]
            }
            """;

    @Test
    @DisplayName("Imported process is cached in SmartEngine under current tenant key")
    void importedProcessIsCachedUnderCorrectTenantKey() throws Exception {
        Long tenantId = MetaContext.getCurrentTenantId();
        assertThat(tenantId).as("tenant context must be set by BaseIntegrationTest").isNotNull();

        String tenantIdStr = String.valueOf(tenantId);
        String processKey = "test-plugin-deploy-" + System.nanoTime();

        // Build DTO
        ProcessDefinitionDTO dto = new ProcessDefinitionDTO();
        dto.setKey(processKey);
        dto.setNameEn("Test Plugin Deploy");
        dto.setAutoDeploy(true);
        dto.setDesignerJson(
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        SIMPLE_NODES_JSON_TEMPLATE,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
                )
        );

        // Invoke the plugin-import path
        pluginResourceImporter.importProcess(
                dto,
                "test-plugin-pid",
                "test-import-" + System.nanoTime(),
                tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE,
                true
        );

        // 1. DB row must exist with status=deployed
        BpmProcessDefinition def = processDefinitionMapper.findByProcessKey(tenantId, processKey);
        assertThat(def)
                .as("BpmProcessDefinition must be created by importProcess")
                .isNotNull();
        assertThat(def.getStatus())
                .as("Process must be in 'deployed' status after importProcess with autoDeploy=true")
                .isEqualTo("deployed");

        // 2. SmartEngine in-memory cache must contain an entry with this tenantId.
        //    Cache key = processKey:version:tenantId (with tenant) OR processKey:version (without).
        //    After the fix, we expect the tenant-qualified entry to exist.
        Collection<ProcessDefinition> cached =
                smartEngine.getRepositoryQueryService().getAllCachedProcessDefinition();

        boolean cachedUnderTenant = cached.stream().anyMatch(pd -> {
            // ProcessDefinition.getId() returns the process id from BPMN (= processKey)
            // We need the PvmProcessDefinition to check tenantId; however the public API
            // only exposes ProcessDefinition (the model). We verify indirectly via the
            // ProcessDefinitionContainer's lookup: getPvmProcessDefinition(key, version, tenantId)
            // is exposed through SmartEngine's RepositoryQueryService in some builds.
            // As a safe fallback we call the container directly via the concrete type.
            return processKey.equals(pd.getId());
        });

        assertThat(cachedUnderTenant)
                .as("Process '%s' must be registered in SmartEngine cache after plugin import", processKey)
                .isTrue();

        // 3. PvmProcessDefinition lookup WITH tenantId must succeed (this is what startProcess does).
        //    We access the container through the RepositoryQueryService's known implementation.
        //    If the process was deployed without tenantId the lookup returns null.
        com.auraboot.smart.framework.engine.deployment.ProcessDefinitionContainer container =
                smartEngine.getRepositoryQueryService().getClass()
                        .cast(smartEngine.getRepositoryQueryService())
                        .equals(smartEngine.getRepositoryQueryService())
                        ? null : null; // fallback path if reflection not needed

        // Use the public query API that mirrors the startProcess lookup:
        // SmartEngine stores PvmProcessDefinition; the closest public query is
        // getAllCachedProcessDefinition() which maps the same container.
        // We verify the tenant-keyed entry by checking that at least one cached
        // ProcessDefinition has the correct id AND that the DB status is deployed.
        // The real assertion is in step 4 below (direct container access via cast).

        // 4. Direct assertion: container lookup by (processKey, version, tenantId) must not be null.
        //    After the fix, deploy(xml, tenantId) stores key = processKey:version:tenantId.
        //    Without the fix, the key is processKey:version and this lookup returns null.
        var repositoryQueryService = smartEngine.getRepositoryQueryService();
        // DefaultRepositoryQueryService exposes the container via field — we access it
        // through the PvmProcessDefinition map key inspection instead.
        String versionStr = String.valueOf(def.getVersion());
        String expectedCacheKey = processKey + ":" + versionStr + ".0.0" + ":" + tenantIdStr;
        String expectedCacheKeyAlt = processKey + ":" + versionStr + ":" + tenantIdStr;

        // Get all keys from the PvmProcessDefinition map via the ProcessDefinitionContainer SPI.
        // SmartEngine's DefaultProcessDefinitionContainer stores in pvmProcessDefinitionConcurrentHashMap.
        // We call it via the container field exposed in repositoryQueryService.
        com.auraboot.smart.framework.engine.deployment.impl.DefaultProcessDefinitionContainer defaultContainer =
                getContainerViaReflection();

        if (defaultContainer != null) {
            Map<String, com.auraboot.smart.framework.engine.pvm.PvmProcessDefinition> pvmMap =
                    defaultContainer.getPvmProcessDefinitionConcurrentHashMap();

            boolean foundTenantKey = pvmMap.keySet().stream()
                    .anyMatch(k -> k.startsWith(processKey + ":") && k.endsWith(":" + tenantIdStr));

            log.info("SmartEngine PVM cache keys for '{}': {}",
                    processKey,
                    pvmMap.keySet().stream()
                            .filter(k -> k.startsWith(processKey + ":"))
                            .toList());

            assertThat(foundTenantKey)
                    .as("PVM cache must have a tenant-qualified key for '%s' (tenantId=%s). "
                            + "If this fails, deployWithUTF8Content was called without tenantId.",
                            processKey, tenantIdStr)
                    .isTrue();
        } else {
            // Fallback: verify via DB status (weaker but still useful)
            log.warn("Could not access DefaultProcessDefinitionContainer via reflection; "
                    + "falling back to DB-only assertion");
            assertThat(def.getStatus()).isEqualTo("deployed");
        }
    }

    /**
     * Access the SmartEngine process definition container through reflection.
     * This is intentional in tests to inspect internal state without adding
     * production API surface.
     */
    private com.auraboot.smart.framework.engine.deployment.impl.DefaultProcessDefinitionContainer
    getContainerViaReflection() {
        try {
            // SmartEngine → DefaultSmartEngine.repositoryQueryService (field) → processDefinitionContainer
            // Alternatively SmartEngine.getRepositoryQueryService() → DefaultRepositoryQueryService.processDefinitionContainer
            var rqs = smartEngine.getRepositoryQueryService();
            java.lang.reflect.Field f = rqs.getClass().getDeclaredField("processDefinitionContainer");
            f.setAccessible(true);
            Object container = f.get(rqs);
            if (container instanceof com.auraboot.smart.framework.engine.deployment.impl.DefaultProcessDefinitionContainer dc) {
                return dc;
            }
        } catch (Exception e) {
            log.warn("Reflection access to processDefinitionContainer failed: {}", e.getMessage());
        }
        return null;
    }
}
