package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.registry.CommandHandlerRegistry;
import com.auraboot.framework.meta.registry.RenderComponentRegistry;
import com.auraboot.framework.meta.registry.SideEffectHandlerRegistry;
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Deep-review finding DR-20260618-D4-dx-001.
 *
 * <p>Inline {@code bindingRules} in commands.json are silently dropped by the importer (binding
 * rules must live in a separate bindingRules.json) yet import still reports success — a documented
 * red-line §6 footgun. {@link ExtensionValidator} now surfaces it as an {@code S-EXT-INLINE-BINDING}
 * warning so import-directory-sync / page-golden-audit callers can see it.
 */
class ExtensionValidatorInlineBindingTest {

    private final ExtensionValidator validator = new ExtensionValidator(
            mock(CommandHandlerRegistry.class),
            mock(SideEffectHandlerRegistry.class),
            mock(RenderComponentRegistry.class));

    @Test
    void inlineBindingRules_emitsWarning() {
        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("demo:create");
        BindingRuleDTO rule = new BindingRuleDTO();
        rule.setCommandCode("demo:create");
        rule.setRuleType("auto_generate");
        cmd.setBindingRules(List.of(rule));

        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(cmd));

        List<PluginValidationMessage> messages =
                validator.validate(PluginValidationContext.builder().manifest(manifest).build());

        assertThat(messages)
                .anyMatch(m -> "S-EXT-INLINE-BINDING".equals(m.getCode())
                        && "warning".equals(m.getSeverity())
                        && m.getMessage().contains("demo:create"));
    }

    @Test
    void noInlineBindingRules_noWarning() {
        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("demo:create");

        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(cmd));

        List<PluginValidationMessage> messages =
                validator.validate(PluginValidationContext.builder().manifest(manifest).build());

        assertThat(messages).noneMatch(m -> "S-EXT-INLINE-BINDING".equals(m.getCode()));
    }
}
