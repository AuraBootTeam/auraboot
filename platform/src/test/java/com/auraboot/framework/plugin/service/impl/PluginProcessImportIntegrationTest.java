package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ProcessDefinitionDTO;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PluginResourceImporterImpl#importProcess} —
 * specifically the BPMN auto-deploy path Phase 3b relies on.
 *
 * <p>Real PostgreSQL ({@code ab_bpm_process_definition}) + real {@link SmartEngine}
 * deployment via {@link com.auraboot.framework.bpm.converter.JsonToBpmnConverter}.
 * No mocks for the import infrastructure.
 *
 * <p>Phase 3b promise validated here:
 * <ol>
 *   <li>{@code processes.json} → {@code ProcessDefinitionDTO} → DB row in
 *       {@code ab_bpm_process_definition}.</li>
 *   <li>When {@code autoDeploy=true}, BPMN XML is generated from {@code designerJson}
 *       and registered in the SmartEngine repository so it's startable.</li>
 *   <li>Re-import is idempotent: a second pass with the same {@code processKey}
 *       does NOT throw, and SmartEngine does not double-deploy.</li>
 *   <li>{@code autoDeploy=false} inserts the DB row only — SmartEngine cache
 *       stays untouched.</li>
 * </ol>
 */
@DisplayName("Plugin process import integration tests (real DB + real SmartEngine)")
class PluginProcessImportIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginResourceImporterImpl importer;

    @Autowired
    private BpmProcessDefinitionMapper processDefinitionMapper;

    @Autowired
    private SmartEngine smartEngine;

    private static final String PLUGIN_PID = "plg_test_phase3b";
    private static final String IMPORT_ID = "imp_test_phase3b";

    /** Build a minimal 3-node designerJson: startEvent → userTask → endEvent. */
    private Map<String, Object> tinyDesignerJson() {
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("id", "start_1");
        start.put("type", "startEvent");
        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("label", "Start");
        start.put("data", startData);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", "task_1");
        task.put("type", "userTask");
        Map<String, Object> taskData = new LinkedHashMap<>();
        taskData.put("label", "Approve");
        taskData.put("config", new HashMap<>());
        task.put("data", taskData);

        Map<String, Object> end = new LinkedHashMap<>();
        end.put("id", "end_1");
        end.put("type", "endEvent");
        Map<String, Object> endData = new LinkedHashMap<>();
        endData.put("label", "End");
        end.put("data", endData);

        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("id", "edge_1");
        e1.put("source", "start_1");
        e1.put("target", "task_1");

        Map<String, Object> e2 = new LinkedHashMap<>();
        e2.put("id", "edge_2");
        e2.put("source", "task_1");
        e2.put("target", "end_1");

        Map<String, Object> dj = new LinkedHashMap<>();
        dj.put("nodes", List.of(start, task, end));
        dj.put("edges", List.of(e1, e2));
        return dj;
    }

    private ProcessDefinitionDTO dto(String key) {
        return ProcessDefinitionDTO.builder()
                .key(key)
                .name("Phase 3b Test Process " + key)
                .description("integration test")
                .category("test")
                .designerJson(tinyDesignerJson())
                .build();
    }

    @Test
    @DisplayName("autoDeploy=true: inserts DB row AND deploys BPMN to SmartEngine")
    void importWithAutoDeployInsertsRowAndDeploysBpmn() {
        String key = "it_proc_deploy_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        PluginResource r = importer.importProcess(dto(key), PLUGIN_PID, IMPORT_ID,
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE, true);

        assertThat(r).isNotNull();
        assertThat(r.getResourceCode()).isEqualTo(key);

        // DB row present and marked deployed
        BpmProcessDefinition row = processDefinitionMapper.findByProcessKey(tenantId, key);
        assertThat(row).isNotNull();
        assertThat(row.getProcessKey()).isEqualTo(key);
        assertThat(row.getStatus()).isEqualTo("deployed");
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getIsCurrent()).isTrue();
        assertThat(row.getBpmnContent()).isNotNull(); // empty string by builder default

        // SmartEngine repository now knows about the process
        boolean deployed = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .anyMatch(pd -> key.equals(pd.getId()));
        assertThat(deployed)
                .as("process key %s should be present in SmartEngine cache after autoDeploy", key)
                .isTrue();
    }

    @Test
    @DisplayName("Re-import of same processKey is idempotent (no SmartEngine duplicate, new version row)")
    void reimportIsIdempotent() {
        String key = "it_proc_idem_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        importer.importProcess(dto(key), PLUGIN_PID, IMPORT_ID, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, true);

        long cacheCountBefore = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(pd -> key.equals(pd.getId()))
                .count();
        assertThat(cacheCountBefore).isEqualTo(1L);

        // Re-import should NOT throw and should NOT add a duplicate to the SmartEngine cache.
        // It SHOULD insert a new DB version row (version=2, is_current=true).
        importer.importProcess(dto(key), PLUGIN_PID, IMPORT_ID, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, true);

        long cacheCountAfter = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(pd -> key.equals(pd.getId()))
                .count();
        assertThat(cacheCountAfter)
                .as("SmartEngine cache must NOT double-deploy the same processKey")
                .isEqualTo(1L);

        BpmProcessDefinition current = processDefinitionMapper.findByProcessKey(tenantId, key);
        assertThat(current).isNotNull();
        assertThat(current.getVersion())
                .as("re-import should bump version")
                .isGreaterThanOrEqualTo(2);
        assertThat(current.getIsCurrent()).isTrue();
    }

    @Test
    @DisplayName("autoDeploy=false: inserts DB row but does NOT touch SmartEngine cache")
    void importWithoutAutoDeployDoesNotDeploy() {
        String key = "it_proc_nodeploy_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        long cacheBefore = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(pd -> key.equals(pd.getId()))
                .count();
        assertThat(cacheBefore).isZero();

        importer.importProcess(dto(key), PLUGIN_PID, IMPORT_ID, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        BpmProcessDefinition row = processDefinitionMapper.findByProcessKey(tenantId, key);
        assertThat(row).isNotNull();
        assertThat(row.getProcessKey()).isEqualTo(key);
        assertThat(row.getStatus())
                .as("autoDeploy=false should leave status as 'draft'")
                .isEqualTo("draft");

        long cacheAfter = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(pd -> key.equals(pd.getId()))
                .count();
        assertThat(cacheAfter)
                .as("autoDeploy=false must NOT register the process with SmartEngine")
                .isZero();
    }

    @Test
    @DisplayName("designerJson userTask.data.formPageKey → BpmProcessDefinition.formBindings is derived at import time")
    void importDerivesFormBindingsFromDesignerFormPageKey() {
        String key = "it_proc_formbind_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        // Build a designerJson where the userTask carries data.formPageKey
        // (designer form) but the DTO does NOT declare top-level formBindings.
        Map<String, Object> designer = tinyDesignerJson();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) designer.get("nodes");
        Map<String, Object> taskNode = nodes.stream()
                .filter(n -> "task_1".equals(n.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> taskData = (Map<String, Object>) taskNode.get("data");
        taskData.put("formPageKey", "wd_leave_request_detail");

        ProcessDefinitionDTO dto = ProcessDefinitionDTO.builder()
                .key(key)
                .name("FormBindings derivation test " + key)
                .category("test")
                .designerJson(designer)
                .build();
        // Explicit assertion: caller did NOT provide formBindings
        assertThat(dto.getFormBindings()).isNull();

        importer.importProcess(dto, PLUGIN_PID, IMPORT_ID, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        BpmProcessDefinition row = processDefinitionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", key)
                        .eq("is_current", true)
                        .eq("deleted_flag", false));
        assertThat(row).isNotNull();
        assertThat(row.getFormBindings())
                .as("form_bindings must contain an entry for the userTask carrying formPageKey")
                .isNotNull()
                .containsKey("task_1");

        @SuppressWarnings("unchecked")
        Map<String, Object> binding = (Map<String, Object>) row.getFormBindings().get("task_1");
        assertThat(binding).isNotNull();
        assertThat(binding.get("formType")).isEqualTo("PAGE");
        assertThat(binding.get("formRef")).isEqualTo("wd_leave_request_detail");
    }

    @Test
    @DisplayName("Explicit dto.formBindings wins over derivation from designerJson")
    void explicitFormBindingsOverrideDerivation() {
        String key = "it_proc_formbind_override_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        Map<String, Object> designer = tinyDesignerJson();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) designer.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> taskData = (Map<String, Object>) nodes.stream()
                .filter(n -> "task_1".equals(n.get("id")))
                .findFirst()
                .orElseThrow()
                .get("data");
        taskData.put("formPageKey", "page_from_designer");

        Map<String, ProcessDefinitionDTO.FormBinding> explicit = new LinkedHashMap<>();
        explicit.put("task_1", ProcessDefinitionDTO.FormBinding.builder()
                .formType("PAGE")
                .pagePid("page_from_dto")
                .build());

        ProcessDefinitionDTO dto = ProcessDefinitionDTO.builder()
                .key(key)
                .name("Override test " + key)
                .category("test")
                .designerJson(designer)
                .formBindings(explicit)
                .build();

        importer.importProcess(dto, PLUGIN_PID, IMPORT_ID, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        BpmProcessDefinition row = processDefinitionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", key)
                        .eq("is_current", true)
                        .eq("deleted_flag", false));
        assertThat(row).isNotNull();
        assertThat(row.getFormBindings())
                .as("Explicit caller-supplied formBindings must not be overwritten by derivation")
                .isNotNull()
                .containsKey("task_1");

        @SuppressWarnings("unchecked")
        Map<String, Object> binding = (Map<String, Object>) row.getFormBindings().get("task_1");
        // Explicit DTO FormBinding preserves its shape (pagePid), not the
        // derived (formRef) shape.
        assertThat(binding).containsKey("pagePid");
        assertThat(binding.get("pagePid")).isEqualTo("page_from_dto");
        assertThat(binding).doesNotContainKey("formRef");
    }

    @Test
    @DisplayName("Importer respects MetaContext tenantId (SKIP strategy returns SKIP record on existing key)")
    void skipStrategyReturnsSkipWhenExists() {
        // Ensure tenant context is set (BaseIntegrationTest already does this in @BeforeEach)
        assertThat(MetaContext.getCurrentTenantId()).isNotNull();

        String key = "it_proc_skip_" + System.nanoTime();
        Long tenantId = getTestTenant().getId();

        importer.importProcess(dto(key), PLUGIN_PID, IMPORT_ID, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        PluginResource second = importer.importProcess(dto(key), PLUGIN_PID, IMPORT_ID, tenantId,
                ImportRequest.ConflictStrategy.SKIP, false);
        assertThat(second).isNotNull();
        // ResourceAction.SKIP code is 'skip' — verifying via toString to avoid coupling to enum import path
        assertThat(String.valueOf(second.getAction())).containsIgnoringCase("skip");
    }
}
