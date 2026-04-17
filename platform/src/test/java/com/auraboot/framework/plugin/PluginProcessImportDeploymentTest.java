package com.auraboot.framework.plugin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ProcessDefinitionDTO;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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

    @Autowired
    private ProcessEngineService processEngineService;

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
    @DisplayName("Imported process is cached in SmartEngine under correct tenant key and can be started")
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

        // 2. PvmProcessDefinition cache inspection via reflection.
        //    Cache key = processKey:version:tenantId (with tenant) OR processKey:version (without).
        //    After the fix, we expect the tenant-qualified entry to exist.
        //    If reflection fails (e.g., SmartEngine upgrades and renames the field), we must fail
        //    loudly rather than silently downgrade to a weaker assertion.
        com.auraboot.smart.framework.engine.deployment.impl.DefaultProcessDefinitionContainer defaultContainer =
                getContainerViaReflection();

        if (defaultContainer == null) {
            fail("Unable to inspect SmartEngine PVM cache via reflection on "
                    + "'processDefinitionContainer.pvmProcessDefinitionConcurrentHashMap'. "
                    + "Test must be updated for new SmartEngine internals.");
        }

        Map<String, com.auraboot.smart.framework.engine.pvm.PvmProcessDefinition> pvmMap =
                defaultContainer.getPvmProcessDefinitionConcurrentHashMap();

        List<String> matchingKeys = pvmMap.keySet().stream()
                .filter(k -> k.startsWith(processKey + ":"))
                .toList();

        log.info("SmartEngine PVM cache keys for '{}': {}", processKey, matchingKeys);

        boolean foundTenantKey = matchingKeys.stream()
                .anyMatch(k -> k.endsWith(":" + tenantIdStr));

        assertThat(foundTenantKey)
                .as("PVM cache must have a tenant-qualified key for '%s' (tenantId=%s). "
                        + "Keys found: %s. "
                        + "If this fails, deployWithUTF8Content was called without tenantId.",
                        processKey, tenantIdStr, matchingKeys)
                .isTrue();

        // 3. Actually start the process — this exercises the exact code path broken by the bug.
        //    ProcessEngineService.startProcess injects TENANT_ID into variables, so SmartEngine
        //    looks up by processKey:version:tenantId. Without the fix, it throws because the
        //    tenant-qualified cache entry is absent.
        String bizKey = "biz-plugin-deploy-test-" + System.nanoTime();
        ProcessInstance instance = processEngineService.startProcess(processKey, bizKey, null);

        assertThat(instance).as("startProcess must return a non-null ProcessInstance").isNotNull();
        assertThat(instance.getInstanceId())
                .as("ProcessInstance must have a non-blank instance id")
                .isNotBlank();

        log.info("Process started successfully: processInstanceId={}", instance.getInstanceId());

        // 4. Query back via processQueryService with current tenant to confirm the instance
        //    was persisted under this tenant (not a different one or no-tenant).
        ProcessInstanceQueryParam queryParam = new ProcessInstanceQueryParam();
        queryParam.setTenantId(tenantIdStr);
        queryParam.setBizUniqueId(bizKey);
        List<ProcessInstance> found = smartEngine.getProcessQueryService().findList(queryParam);

        assertThat(found)
                .as("findList(tenantId=%s, bizKey=%s) must return the newly-started instance",
                        tenantIdStr, bizKey)
                .isNotEmpty();

        assertThat(found.get(0).getInstanceId())
                .as("Queried instance id must match the started instance id")
                .isEqualTo(instance.getInstanceId());

        log.info("PASSED: plugin-imported process '{}' deployed with tenant key, started, and queried back. "
                + "instanceId={}", processKey, instance.getInstanceId());
    }

    /**
     * Access the SmartEngine process definition container through reflection.
     * This is intentional in tests to inspect internal state without adding
     * production API surface. Returns null only if the field is genuinely absent
     * (SmartEngine internals changed); callers must treat null as a hard failure,
     * not a fallback.
     */
    private com.auraboot.smart.framework.engine.deployment.impl.DefaultProcessDefinitionContainer
    getContainerViaReflection() {
        try {
            // SmartEngine.getRepositoryQueryService() → DefaultRepositoryQueryService.processDefinitionContainer
            var rqs = smartEngine.getRepositoryQueryService();
            java.lang.reflect.Field f = rqs.getClass().getDeclaredField("processDefinitionContainer");
            f.setAccessible(true);
            Object container = f.get(rqs);
            if (container instanceof com.auraboot.smart.framework.engine.deployment.impl.DefaultProcessDefinitionContainer dc) {
                return dc;
            }
            // Container exists but is not the expected type — caller will fail loudly
            log.error("processDefinitionContainer is of unexpected type: {}",
                    container == null ? "null" : container.getClass().getName());
            return null;
        } catch (NoSuchFieldException e) {
            log.error("Field 'processDefinitionContainer' not found on {}: {}",
                    smartEngine.getRepositoryQueryService().getClass().getName(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Reflection access to processDefinitionContainer failed: {}", e.getMessage(), e);
            return null;
        }
    }
}
