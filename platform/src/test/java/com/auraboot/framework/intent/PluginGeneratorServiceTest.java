package com.auraboot.framework.intent;

import com.auraboot.framework.intent.dto.IntentAnalysisResult;
import com.auraboot.framework.intent.dto.IntentAnalysisResult.*;
import com.auraboot.framework.intent.dto.PluginGenerateResult;
import com.auraboot.framework.intent.service.PluginGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PluginGeneratorService.
 */
class PluginGeneratorServiceTest {

    private PluginGeneratorService service;

    @BeforeEach
    void setUp() {
        service = new PluginGeneratorService();
    }

    @Test
    void generate_shouldProduceAllConfigFiles() {
        IntentAnalysisResult analysis = buildSampleAnalysis();

        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        assertThat(result.getPluginCode()).isEqualTo("task-mgmt");
        assertThat(result.getPluginName()).isEqualTo("Task Management");
        assertThat(result.getConfigs()).containsKeys(
                "models.json", "fields.json", "bindings.json", "commands.json",
                "pages.json", "menus.json", "i18n.json", "permissions.json"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreateModelsWithCorrectPrefix() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        List<Map<String, Object>> models = (List<Map<String, Object>>) result.getConfigs().get("models.json");
        assertThat(models).hasSize(1);
        assertThat(models.get(0).get("code")).isEqualTo("task_mgmt_task");
        assertThat(models.get(0).get("tableName")).isEqualTo("mt_task_mgmt_task");
        assertThat(models.get(0).get("type")).isEqualTo("dynamic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreateFieldsForEachEntity() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.getConfigs().get("fields.json");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).get("modelCode")).isEqualTo("task_mgmt_task");
        assertThat(fields.get(0).get("code")).isEqualTo("task_title");
        assertThat(fields.get(0).get("fieldType")).isEqualTo("string");
        assertThat(fields.get(0).get("required")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreateCrudCommands() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.getConfigs().get("commands.json");
        // 3 CRUD commands + 1 state transition
        assertThat(commands).hasSizeGreaterThanOrEqualTo(3);
        assertThat(commands.stream().map(c -> c.get("type").toString()).toList())
                .contains("create", "update", "delete");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreateStateTransitionCommands() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.getConfigs().get("commands.json");
        // Find the state transition command
        var startCmd = commands.stream()
                .filter(c -> c.get("code").toString().contains("start"))
                .findFirst()
                .orElse(null);

        assertThat(startCmd).isNotNull();
        assertThat(startCmd.get("guard")).isNotNull();
        assertThat(startCmd.get("effects")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreatePagesForEachEntity() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        List<Map<String, Object>> pages = (List<Map<String, Object>>) result.getConfigs().get("pages.json");
        assertThat(pages).hasSize(2); // LIST + DETAIL
        assertThat(pages.get(0).get("pageType")).isEqualTo("list");
        assertThat(pages.get(0).get("routePath")).isEqualTo("/task-mgmt/task");
        assertThat(pages.get(1).get("pageType")).isEqualTo("detail");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreateMenuHierarchy() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        List<Map<String, Object>> menus = (List<Map<String, Object>>) result.getConfigs().get("menus.json");
        assertThat(menus).hasSize(2); // 1 root group + 1 child
        assertThat(menus.get(0).get("type")).isEqualTo("group");
        assertThat(menus.get(1).get("parentCode")).isEqualTo("task-mgmt_menu");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreateI18nForBothLocales() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        Map<String, Map<String, String>> i18n = (Map<String, Map<String, String>>) result.getConfigs().get("i18n.json");
        assertThat(i18n).containsKeys("en", "zh-CN");
        assertThat(i18n.get("en")).containsKey("model.task_mgmt_task.label");
        assertThat(i18n.get("en")).containsKey("field.task_mgmt_task.task_title.label");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_shouldCreatePermissionsForCrud() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        List<Map<String, Object>> permissions = (List<Map<String, Object>>) result.getConfigs().get("permissions.json");
        assertThat(permissions).hasSize(4); // LIST, CREATE, UPDATE, DELETE
        assertThat(permissions.stream().map(p -> p.get("code").toString()).toList())
                .containsExactly("task_mgmt_task:LIST", "task_mgmt_task:CREATE",
                        "task_mgmt_task:UPDATE", "task_mgmt_task:DELETE");
    }

    @Test
    void generate_shouldSetCorrectCounts() {
        IntentAnalysisResult analysis = buildSampleAnalysis();
        PluginGenerateResult result = service.generate(analysis, "task-mgmt", "Task Management");

        assertThat(result.getModelCount()).isEqualTo(1);
        assertThat(result.getFieldCount()).isEqualTo(2);
        assertThat(result.getPageCount()).isEqualTo(2);
        assertThat(result.getSummary()).contains("Task Management");
    }

    @Test
    void generate_shouldRejectEmptyEntities() {
        IntentAnalysisResult analysis = new IntentAnalysisResult();
        analysis.setEntities(List.of());

        assertThatThrownBy(() -> service.generate(analysis, "test", "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one entity");
    }

    @Test
    void generate_shouldRejectBlankPluginCode() {
        IntentAnalysisResult analysis = buildSampleAnalysis();

        assertThatThrownBy(() -> service.generate(analysis, "", "Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plugin code");
    }

    // ---- Test data ----

    private IntentAnalysisResult buildSampleAnalysis() {
        FieldDef titleField = FieldDef.builder()
                .code("task_title").name("Title").type("string").required(true)
                .description("Task title").build();
        FieldDef statusField = FieldDef.builder()
                .code("task_status").name("Status").type("enum").required(true)
                .description("Task status").enumValues("TODO,IN_PROGRESS,DONE").build();

        EntityDef task = EntityDef.builder()
                .code("task").name("Task").description("A work task")
                .fields(List.of(titleField, statusField))
                .build();

        TransitionDef start = TransitionDef.builder()
                .from("todo").to("in_progress").action("start").description("Start the task").build();

        StateMachineDef sm = StateMachineDef.builder()
                .entityCode("task").fieldCode("task_status")
                .states(List.of("todo", "in_progress", "done"))
                .transitions(List.of(start))
                .build();

        return IntentAnalysisResult.builder()
                .summary("Task management system")
                .entities(List.of(task))
                .relationships(List.of())
                .stateMachines(List.of(sm))
                .rules(List.of())
                .build();
    }
}
