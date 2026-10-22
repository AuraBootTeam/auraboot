package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationBinding;
import com.auraboot.framework.plugin.pf4j.CommandHandlerCapabilityGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** A declared host requirement cannot grant a restricted installation access to AI bridges. */
class InstalledHandlerCapabilityBoundaryTest {
    @Test
    void installedIntegrationCannotAcquireAiEvenWhenTheModuleIsPresent() {
        CommandHandlerExtension handler = new CommandHandlerExtension() {
            @Override public String getCommandType() { return "integration:process"; }
            @Override public Object execute(CommandContext context) { throw new AssertionError("Must not execute"); }
            @Override public boolean requiresAiProvider(String code) { return true; }
        };
        var binding = new InstalledIntegrationBinding(42L, "app", "installation", "source", "event",
                "durable-event", 1L, Map.of());
        InstalledIntegrationExecution.run("integration:process", "config", binding, false, handler, () -> {
            assertThatThrownBy(() -> CommandHandlerCapabilityGuard.requireAvailable(
                    handler, "integration:process", true, true)).isInstanceOf(AccessDeniedException.class);
            return null;
        });
        assertThat(InstalledIntegrationExecution.active()).isFalse();
    }
}
