package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.BpmRuleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
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
