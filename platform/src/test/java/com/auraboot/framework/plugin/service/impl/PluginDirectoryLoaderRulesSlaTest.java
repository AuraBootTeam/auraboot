package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.BpmRuleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.FieldMaskDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.SlaConfigDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private void writeManifest(Path pluginDir, String json) throws IOException {
        Files.writeString(pluginDir.resolve("plugin.json"), json);
    }
}
