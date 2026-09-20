package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the shared plugin resource parse failure policy. The directory loader and the
 * ZIP import path must behave identically: commands fail closed, auxiliary resources skip.
 */
@DisplayName("PluginResourceParsePolicy Unit Tests")
class PluginResourceParsePolicyTest {

    @Test
    @DisplayName("Command files are fail-closed: a parse failure throws instead of skipping")
    void commandResourcesAreFailClosed() {
        Exception parseFailure = new RuntimeException("Cannot deserialize CommandDefinitionDTO");

        assertThatThrownBy(() -> PluginResourceParsePolicy
                .onResourceParseFailure("config/commands/crm_credit_hold.json",
                        CommandDefinitionDTO.class, parseFailure))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("crm_credit_hold.json")
                .hasMessageContaining("CommandDefinitionDTO")
                .hasMessageContaining("must not be silently dropped");
    }

    @Test
    @DisplayName("Auxiliary resources keep per-file resilience: log and skip")
    void auxiliaryResourcesSkipThemselves() {
        List<DictDefinitionDTO> result = PluginResourceParsePolicy.onResourceParseFailure(
                "config/dicts/broken.json", DictDefinitionDTO.class,
                new RuntimeException("not valid json"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Only command definitions are fail-closed today")
    void failClosedSetIsScopedToCommands() {
        assertThat(PluginResourceParsePolicy.requiresFailClosed(CommandDefinitionDTO.class)).isTrue();
        assertThat(PluginResourceParsePolicy.requiresFailClosed(DictDefinitionDTO.class)).isFalse();
    }
}
