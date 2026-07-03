package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.decision.adapter.DecisionTableAdapter;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.plugin.dto.imports.BpmRuleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DecisionDefinitionSeedDTO;
import com.auraboot.framework.plugin.dto.imports.FieldMaskDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ProcessDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.SlaConfigDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PluginDirectoryLoader}'s new rules/sla branches,
 * including DRL-on-disk inlining.
 */
class PluginDirectoryLoaderRulesSlaTest {

    private final PluginDirectoryLoader loader = new PluginDirectoryLoader();

    @Test
    @DisplayName("Loader reads standard config/agent-definitions.json without resourceDirs entry")
    void loadsAgentDefinitionsByConvention(@TempDir Path pluginDir) throws IOException {
        Files.createDirectories(pluginDir.resolve("config"));
        Files.writeString(pluginDir.resolve("config/agent-definitions.json"), """
                [
                  {
                    "agentCode": "demo_procurement_agent",
                    "name": "Demo Procurement Agent",
                    "agentType": "reactive",
                    "model": "MiniMax-M2.5",
                    "systemPrompt": "Compare supplier quotes with evidence.",
                    "tools": ["named_query:demo_quote_summary"],
                    "allowedModels": ["demo_quote"],
                    "soulProfile": { "persona": "Procurement analyst" }
                  }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "commands": "commands" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getAgentDefinitions()).hasSize(1);
        AgentDefinitionDTO agent = manifest.getAgentDefinitions().get(0);
        assertThat(agent.getAgentCode()).isEqualTo("demo_procurement_agent");
        assertThat(agent.getTools()).containsExactly("named_query:demo_quote_summary");
        assertThat(agent.getAllowedModels()).containsExactly("demo_quote");
        assertThat(agent.getSoulProfile()).containsEntry("persona", "Procurement analyst");
    }

    @Test
    @DisplayName("Loader reads rules.json and inlines ruleContentFile DRL")
    void loadsRulesWithInlinedDrl(@TempDir Path pluginDir) throws IOException {
        String drl = """
                package com.demo
                rule "r1" when then end
                """;
        Files.createDirectories(pluginDir.resolve("rules"));
        Files.writeString(pluginDir.resolve("rules/validation.drl"), drl);
        Files.writeString(pluginDir.resolve("rules.json"), """
                [
                  { "ruleCode": "demo_validation",
                    "ruleName": "Demo",
                    "ruleType": "VALIDATION",
                    "ruleContentFile": "rules/validation.drl" }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "rules": "rules.json" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getRules()).hasSize(1);
        BpmRuleDefinitionDTO rule = manifest.getRules().get(0);
        assertThat(rule.getRuleCode()).isEqualTo("demo_validation");
        assertThat(rule.getRuleContent()).contains("rule \"r1\"");
    }

    @Test
    @DisplayName("Loader reads sla.json into SlaConfigDefinitionDTO list")
    void loadsSlaConfigs(@TempDir Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve("sla.json"), """
                [
                  { "name": "wd_manager_approval_sla",
                    "targetType": "NODE",
                    "targetKey": "user_manager_approval",
                    "deadlineMode": "FIXED",
                    "deadlineValue": "PT30S" }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "sla": "sla.json" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getSlaConfigs()).hasSize(1);
        SlaConfigDefinitionDTO sla = manifest.getSlaConfigs().get(0);
        assertThat(sla.getName()).isEqualTo("wd_manager_approval_sla");
        assertThat(sla.getDeadlineValue()).isEqualTo("PT30S");
    }

    @Test
    @DisplayName("Loader reads decisionDefinitions.json into DRT seed definitions")
    void loadsDecisionDefinitions(@TempDir Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve("decisionDefinitions.json"), """
                [
                  {
                    "decisionCode": "complaint_sla_deadline",
                    "decisionName": "投诉 SLA 截止时间",
                    "scopeType": "SLA",
                    "ownerModule": "bpm",
                    "kind": "SIMPLE_CONDITION",
                    "runtimeAdapter": "AST_EVALUATOR",
                    "versionTag": "seed-v1",
                    "publish": true,
                    "contentJson": {
                      "condition": {
                        "type": "group",
                        "op": "AND",
                        "children": [
                          {
                            "type": "compare",
                            "left": { "type": "path", "scope": "process", "path": "taskKey", "dataType": "string" },
                            "operator": "IS_NOT_NULL"
                          }
                        ]
                      },
                      "outputs": { "deadlineMinutes": 30 }
                    }
                  }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "decisionDefinitions": "decisionDefinitions.json" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getDecisionDefinitions()).hasSize(1);
        DecisionDefinitionSeedDTO decision = manifest.getDecisionDefinitions().get(0);
        assertThat(decision.getDecisionCode()).isEqualTo("complaint_sla_deadline");
        assertThat(decision.getKind()).isEqualTo("SIMPLE_CONDITION");
        assertThat(decision.getRuntimeAdapter()).isEqualTo("AST_EVALUATOR");
        assertThat(decision.getContentJson().at("/outputs/deadlineMinutes").asInt()).isEqualTo(30);
        assertThat(decision.isPublish()).isTrue();
        assertThat(decision.isValid()).isTrue();
    }

    @Test
    @DisplayName("Loader reads fieldMasks.json into FieldMaskDefinitionDTO list")
    void loadsFieldMasks(@TempDir Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve("fieldMasks.json"), """
                [
                  { "modelCode": "crm_account_common",
                    "fieldCode": "phone",
                    "maskType": "PHONE",
                    "applyToList": true,
                    "applyToDetail": true,
                    "applyToExport": true,
                    "exemptPermissionCodes": "crm.account.contact_unmask" }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "fieldMasks": "fieldMasks.json" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getFieldMasks()).hasSize(1);
        FieldMaskDefinitionDTO fm = manifest.getFieldMasks().get(0);
        assertThat(fm.getModelCode()).isEqualTo("crm_account_common");
        assertThat(fm.getFieldCode()).isEqualTo("phone");
        assertThat(fm.getMaskType()).isEqualTo("PHONE");
        assertThat(fm.getExemptPermissionCodes()).isEqualTo("crm.account.contact_unmask");
        assertThat(fm.isValid()).isTrue();
    }

    @Test
    @DisplayName("Loader reads capabilities.json into CapabilityDefinitionDTO list")
    void loadsCapabilities(@TempDir Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve("capabilities.json"), """
                [
                  { "code": "crm.cap.account",
                    "group": "客户管理",
                    "name:zh-CN": "维护客户资料",
                    "includes": ["crm.account.read", "crm.account.manage"],
                    "tier": "editor",
                    "order": 10 },
                  { "code": "crm.cap.account_contact_full",
                    "group": "客户管理",
                    "name:zh-CN": "查看完整联系方式",
                    "sensitive": true,
                    "unmasksFields": ["crm_account_common.crm_acc_phone"],
                    "includes": ["crm.account.contact_unmask"] }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "capabilities": "capabilities.json" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getCapabilities()).hasSize(2);
        CapabilityDefinitionDTO account = manifest.getCapabilities().get(0);
        assertThat(account.getCode()).isEqualTo("crm.cap.account");
        assertThat(account.getGroup()).isEqualTo("客户管理");
        assertThat(account.getNameZhCN()).isEqualTo("维护客户资料");
        assertThat(account.getIncludes()).containsExactly("crm.account.read", "crm.account.manage");
        assertThat(account.getTier()).isEqualTo("editor");
        assertThat(account.isValid()).isTrue();

        CapabilityDefinitionDTO sensitive = manifest.getCapabilities().get(1);
        assertThat(sensitive.getSensitive()).isTrue();
        assertThat(sensitive.getUnmasksFields()).containsExactly("crm_account_common.crm_acc_phone");
    }

    @Test
    @DisplayName("Both ruleContent and ruleContentFile set → PluginException")
    void ambiguousRuleSourceThrows(@TempDir Path pluginDir) throws IOException {
        Files.createDirectories(pluginDir.resolve("rules"));
        Files.writeString(pluginDir.resolve("rules/foo.drl"), "rule r when then end");
        Files.writeString(pluginDir.resolve("rules.json"), """
                [
                  { "ruleCode": "demo",
                    "ruleContent": "rule inline when then end",
                    "ruleContentFile": "rules/foo.drl" }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "rules": "rules.json" } }
                """);

        assertThatThrownBy(() -> loader.loadFromDirectory(pluginDir))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("pick one");
    }

    @Test
    @DisplayName("Missing ruleContentFile → PluginException")
    void missingDrlFileThrows(@TempDir Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve("rules.json"), """
                [ { "ruleCode": "demo", "ruleContentFile": "rules/missing.drl" } ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "rules": "rules.json" } }
                """);

        assertThatThrownBy(() -> loader.loadFromDirectory(pluginDir))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("workflow-demo wires rule-center seeds into BPM assignment and SLA deadline consumers")
    void workflowDemoDeclaresDecisionSeedsAndRuleBindings() {
        PluginManifestExtended manifest = loader.loadFromDirectory(workflowDemoDir());

        assertThat(manifest.getDecisionDefinitions())
                .extracting(DecisionDefinitionSeedDTO::getDecisionCode)
                .contains("complaint_sla_deadline", "approval_routing");
        DecisionDefinitionSeedDTO slaDecision = manifest.getDecisionDefinitions().stream()
                .filter(d -> "complaint_sla_deadline".equals(d.getDecisionCode()))
                .findFirst()
                .orElseThrow();
        assertThat(slaDecision.getKind()).isEqualTo("DECISION_TABLE");
        assertThat(slaDecision.getRuntimeAdapter()).isEqualTo("PLATFORM_DECISION_TABLE");
        assertThat(slaDecision.getContentJson().path("outputs").findValuesAsText("id"))
                .contains("deadlineMinutes");
        DecisionTableAdapter decisionTableAdapter = new DecisionTableAdapter();
        for (DecisionDefinitionSeedDTO decision : manifest.getDecisionDefinitions()) {
            assertThat(decisionTableAdapter.validate(new ResolvedDecision(
                    decision.getDecisionCode(),
                    1,
                    decision.getVersionTag(),
                    VersionStatus.PUBLISHED,
                    DecisionKind.valueOf(decision.getKind()),
                    RuntimeAdapter.valueOf(decision.getRuntimeAdapter()),
                    decision.getContentJson())).valid())
                    .as("decision seed must validate: %s", decision.getDecisionCode())
                    .isTrue();
        }

        SlaConfigDefinitionDTO managerSla = manifest.getSlaConfigs().stream()
                .filter(s -> "wd_manager_approve_sla".equals(s.getUnknownFields().get("slaKey")))
                .findFirst()
                .orElseThrow();
        assertThat(managerSla.getRuleBinding()).isNotNull();
        assertThat(managerSla.getRuleBinding().decisionBinding().decisionCode())
                .isEqualTo("complaint_sla_deadline");
        assertThat(managerSla.getRuleBinding().decisionBinding().outputMappings())
                .anySatisfy(mapping -> {
                    assertThat(mapping.output()).isEqualTo("deadlineMinutes");
                    assertThat(mapping.target().kind().name()).isEqualTo("SLA_FIELD");
                    assertThat(mapping.target().path()).isEqualTo("deadlineMinutes");
                });

        ProcessDefinitionDTO process = manifest.getProcesses().stream()
                .filter(p -> "wd_leave_approval".equals(p.getKey()))
                .findFirst()
                .orElseThrow();
        assertAssignmentBinding(process, "task_manager_approve");
        assertAssignmentBinding(process, "task_hr_approve");
    }

    private void writeManifest(Path pluginDir, String json) throws IOException {
        Files.writeString(pluginDir.resolve("plugin.json"), json);
    }

    private Path workflowDemoDir() {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                userDir.resolve("plugins/workflow-demo"),
                userDir.resolve("../plugins/workflow-demo").normalize(),
                userDir.resolve("../../plugins/workflow-demo").normalize());
        return candidates.stream()
                .filter(path -> Files.exists(path.resolve("plugin.json")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void assertAssignmentBinding(ProcessDefinitionDTO process, String nodeId) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) process.getDesignerJson().get("nodes");
        Map<String, Object> node = nodes.stream()
                .filter(candidate -> nodeId.equals(candidate.get("id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> data = (Map<String, Object>) node.get("data");
        Map<String, Object> config = (Map<String, Object>) data.get("config");
        Map<String, Object> ruleBinding = (Map<String, Object>) config.get("assignmentRuleBinding");
        assertThat(ruleBinding).isNotNull();
        assertThat(ruleBinding.get("consumerType")).isEqualTo("BPM");
        assertThat(ruleBinding.get("consumerNodeId")).isEqualTo(nodeId);
        Map<String, Object> decisionBinding = (Map<String, Object>) ruleBinding.get("decisionBinding");
        assertThat(decisionBinding.get("decisionCode")).isEqualTo("approval_routing");
        List<Map<String, Object>> outputMappings =
                (List<Map<String, Object>>) decisionBinding.get("outputMappings");
        assertThat(outputMappings)
                .anySatisfy(mapping -> {
                    assertThat(mapping.get("output")).isEqualTo("reviewGroups");
                    Map<String, Object> target = (Map<String, Object>) mapping.get("target");
                    assertThat(target.get("kind")).isEqualTo("ACTION_PARAM");
                    assertThat(target.get("path")).isEqualTo("candidateGroups");
                })
                .anySatisfy(mapping -> {
                    assertThat(mapping.get("output")).isEqualTo("primaryAssignee");
                    Map<String, Object> target = (Map<String, Object>) mapping.get("target");
                    assertThat(target.get("kind")).isEqualTo("PROCESS_VARIABLE");
                    assertThat(target.get("path")).isEqualTo("assigneeUserId");
                });
    }
}
