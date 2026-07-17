package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.decision.adapter.DecisionTableAdapter;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.plugin.dto.imports.BpmRuleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ConditionFragmentSeedDTO;
import com.auraboot.framework.plugin.dto.imports.DecisionDefinitionSeedDTO;
import com.auraboot.framework.plugin.dto.imports.EventPolicySeedDTO;
import com.auraboot.framework.plugin.dto.imports.FieldMaskDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ProcessDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RolePermissionPolicyDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.SlaConfigDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
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
                    "deadlineValue": "PT30S",
                    "actionPolicy": {
                      "trigger": "SLA_TIMEOUT",
                      "actions": [
                        {
                          "type": "SEND_IM",
                          "target": "ROLE:wd_manager",
                          "order": 10,
                          "payload": { "title": "SLA 超时提醒" }
                        }
                      ],
                      "executionEffect": { "lastStatus": "SUCCESS", "traceId": "seed-trace" }
                    } }
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
        assertThat(sla.getActionPolicy())
                .containsEntry("trigger", "SLA_TIMEOUT")
                .extractingByKey("actions")
                .asList()
                .hasSize(1);
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
    @DisplayName("Loader reads conditionFragments.json into reusable condition-fragment seeds")
    void loadsConditionFragments(@TempDir Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve("conditionFragments.json"), """
                [
                  {
                    "fragmentCode": "leave_sla_node_match",
                    "fragmentName": "请假 SLA 节点匹配",
                    "scopeType": "SLA",
                    "scopeRef": "wd_leave_approval",
                    "ownerModule": "workflow-demo",
                    "publish": true,
                    "conditionSpec": {
                      "root": {
                        "type": "group",
                        "op": "AND",
                        "children": [
                          {
                            "type": "compare",
                            "left": { "type": "path", "scope": "process", "path": "taskKey", "dataType": "string" },
                            "operator": "EQ",
                            "right": { "type": "literal", "value": "task_manager_approve", "dataType": "string" }
                          }
                        ]
                      },
                      "decisionBindings": [
                        { "decisionCode": "complaint_sla_deadline", "versionPolicy": "LATEST_PUBLISHED" }
                      ]
                    }
                  }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "conditionFragments": "conditionFragments.json" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getConditionFragments()).hasSize(1);
        ConditionFragmentSeedDTO fragment = manifest.getConditionFragments().get(0);
        assertThat(fragment.getFragmentCode()).isEqualTo("leave_sla_node_match");
        assertThat(fragment.getConditionSpec().at("/decisionBindings/0/decisionCode").asText())
                .isEqualTo("complaint_sla_deadline");
        assertThat(fragment.isPublish()).isTrue();
        assertThat(fragment.isValid()).isTrue();
    }

    @Test
    @DisplayName("Loader reads eventPolicies.json into EventPolicy seed definitions")
    void loadsEventPolicies(@TempDir Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve("eventPolicies.json"), """
                [
                  {
                    "policyCode": "leave_request_event_policy",
                    "policyName": "请假事件动作策略",
                    "eventType": "LEAVE_REQUEST_CREATED",
                    "targetType": "MODEL",
                    "targetKey": "wd_leave_request",
                    "phase": "AFTER_COMMIT",
                    "matchMode": "COLLECT_ALL",
                    "executionMode": "ORDERED",
                    "failureStrategy": "CONTINUE_ON_ERROR",
                    "conflictStrategy": "REJECT_ON_CONFLICT",
                    "dedupStrategy": "BY_IDEMPOTENCY_KEY",
                    "publish": true,
                    "rulesJson": [
                      {
                        "ruleCode": "notify_long_leave",
                        "ruleName": "长假通知",
                        "priority": 10,
                        "enabled": true,
                        "decisionBinding": {
                          "decisionCode": "leave_request_automation",
                          "versionPolicy": "LATEST_PUBLISHED"
                        },
                        "actions": [
                          {
                            "type": "NOTIFY",
                            "target": "ROLE:wd_manager",
                            "order": 10,
                            "payload": { "title": "长假申请提醒" },
                            "idempotencyKeyTemplate": "${record.pid}:notify_long_leave:NOTIFY"
                          }
                        ]
                      }
                    ]
                  }
                ]
                """);
        writeManifest(pluginDir, """
                { "pluginId": "com.demo",
                  "namespace": "demo",
                  "version": "1.0.0",
                  "resourceDirs": { "eventPolicies": "eventPolicies.json" } }
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(pluginDir);

        assertThat(manifest.getEventPolicies()).hasSize(1);
        EventPolicySeedDTO policy = manifest.getEventPolicies().get(0);
        assertThat(policy.getPolicyCode()).isEqualTo("leave_request_event_policy");
        assertThat(policy.getRulesJson().get(0).at("/decisionBinding/decisionCode").asText())
                .isEqualTo("leave_request_automation");
        assertThat(policy.isPublish()).isTrue();
        assertThat(policy.isValid()).isTrue();
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
        assertThat(manifest.getConditionFragments())
                .extracting(ConditionFragmentSeedDTO::getFragmentCode)
                .contains("shared_leave_approval_guard", "leave_sla_node_match");
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
                .filter(s -> "wd_manager_approve_sla".equals(s.getSlaKey()))
                .findFirst()
                .orElseThrow();
        assertThat(managerSla.getRuleBinding()).isNotNull();
        assertThat(managerSla.getRuleBinding().conditionFragmentRefs())
                .containsExactly("shared_leave_approval_guard");
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

    @Test
    @DisplayName("workflow-demo wires rule-center seed into Automation trigger")
    void workflowDemoDeclaresRuleCenterAutomationSeed() throws Exception {
        PluginManifestExtended manifest = loader.loadFromDirectory(workflowDemoDir());

        assertThat(manifest.getResourceCounts()).containsEntry("automations", 1);

        List<?> automations = (List<?>) invokeGetter(manifest, "getAutomations");
        assertThat(automations).hasSize(1);
        Object automation = automations.get(0);
        assertThat(invokeGetter(automation, "getAutomationKey"))
                .isEqualTo("wd_leave_high_value_notify");
        assertThat(invokeGetter(automation, "getModelCode")).isEqualTo("wd_leave_request");
        assertThat(invokeGetter(automation, "getTriggerType")).isEqualTo("on_record_create");
        assertThat(invokeGetter(automation, "getTriggerCondition"))
                .isEqualTo("#decision['outputs']['actionType'] == 'send_notification'");
        Object triggerConfig = invokeGetter(automation, "getTriggerConfig");
        Object ruleBinding = invokeGetter(triggerConfig, "getRuleBinding");
        assertThat(invokeGetter(ruleBinding, "consumerType")).isEqualTo("AUTOMATION");
        assertThat(invokeGetter(ruleBinding, "consumerCode")).isEqualTo("wd_leave_high_value_notify");
        List<?> automationFragmentRefs = (List<?>) invokeGetter(ruleBinding, "conditionFragmentRefs");
        assertThat(automationFragmentRefs).hasSize(1);
        assertThat(automationFragmentRefs.get(0)).isEqualTo("shared_leave_approval_guard");
        Object decisionBinding = invokeGetter(ruleBinding, "decisionBinding");
        assertThat(invokeGetter(decisionBinding, "decisionCode")).isEqualTo("leave_request_automation");
        List<?> actions = (List<?>) invokeGetter(automation, "getActions");
        assertThat(actions).hasSize(1);
        Object action = actions.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) invokeGetter(action, "getConfig");
        assertThat(config.get("recipients")).isEqualTo(List.of("ROLE:wd_manager"));
    }

    @Test
    @DisplayName("workflow-demo wires shared condition fragment into permission role policies")
    void workflowDemoDeclaresPermissionRolePolicySeed() throws Exception {
        PluginManifestExtended manifest = loader.loadFromDirectory(workflowDemoDir());

        PermissionDefinitionDTO approvePermission = manifest.getPermissions().stream()
                .filter(permission -> "wd.leave_request.approve".equals(permission.getCode()))
                .findFirst()
                .orElseThrow();
        assertThat(approvePermission.getPolicySchema())
                .containsKey("dynamicAbac");
        @SuppressWarnings("unchecked")
        Map<String, Object> dynamicAbacSchema =
                (Map<String, Object>) approvePermission.getPolicySchema().get("dynamicAbac");
        assertThat(dynamicAbacSchema)
                .containsEntry("fieldCatalogModelCode", "wd_leave_request");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schemaDecisions =
                (List<Map<String, Object>>) dynamicAbacSchema.get("decisions");
        assertThat(schemaDecisions)
                .anySatisfy(decision -> {
                    assertThat(decision).containsEntry("code", "leave_request_automation");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> outputs =
                            (List<Map<String, Object>>) decision.get("outputs");
                    assertThat(outputs)
                            .extracting(output -> output.get("id"))
                            .contains("severity", "message", "actionType");
                });

        RoleDefinitionDTO manager = manifest.getRoles().stream()
                .filter(role -> "wd_manager".equals(role.getCode()))
                .findFirst()
                .orElseThrow();
        RolePermissionPolicyDefinitionDTO policy = manager.getPermissionPolicies().stream()
                .filter(candidate -> "wd.leave_request.approve".equals(candidate.getPermissionCode()))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> dynamicAbac =
                (Map<String, Object>) policy.getConditions().get("dynamicAbac");
        @SuppressWarnings("unchecked")
        Map<String, Object> ruleBinding =
                (Map<String, Object>) dynamicAbac.get("ruleBinding");
        assertThat(ruleBinding)
                .containsEntry("consumerType", "PERMISSION")
                .containsEntry("bindingKind", "DECISION_REF");
        assertThat(ruleBinding.get("conditionFragmentRefs"))
                .isEqualTo(List.of("shared_leave_approval_guard"));
        @SuppressWarnings("unchecked")
        Map<String, Object> decisionBinding =
                (Map<String, Object>) ruleBinding.get("decisionBinding");
        assertThat(decisionBinding).containsEntry("decisionCode", "leave_request_automation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputMappings =
                (List<Map<String, Object>>) decisionBinding.get("outputMappings");
        assertThat(outputMappings)
                .anySatisfy(mapping -> {
                    assertThat(mapping).containsEntry("output", "severity");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> target = (Map<String, Object>) mapping.get("target");
                    assertThat(target)
                            .containsEntry("kind", "PERMISSION_CONTEXT")
                            .containsEntry("path", "severity");
                })
                .anySatisfy(mapping -> {
                    assertThat(mapping).containsEntry("output", "message");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> target = (Map<String, Object>) mapping.get("target");
                    assertThat(target)
                            .containsEntry("kind", "PERMISSION_CONTEXT")
                            .containsEntry("path", "message");
                });
    }

    private void writeManifest(Path pluginDir, String json) throws IOException {
        Files.writeString(pluginDir.resolve("plugin.json"), json);
    }

    private Object invokeGetter(Object target, String getterName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(getterName);
        return method.invoke(target);
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
        List<?> conditionFragmentRefs = (List<?>) ruleBinding.get("conditionFragmentRefs");
        assertThat(conditionFragmentRefs).hasSize(1);
        assertThat(conditionFragmentRefs.get(0)).isEqualTo("shared_leave_approval_guard");
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
