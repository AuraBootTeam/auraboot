package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandlerContractInspectorTest {
    record Handler(int priority, boolean chained, Set<String> contracts) implements CommandHandlerExtension {
        public String getCommandType() { return "edu:run"; }
        public boolean supports(String type) { return getSupportedCommandTypes().contains(type); }
        public Set<String> getSupportedCommandTypes() { return Set.of("edu:run", "edu:alias"); }
        public int getPriority() { return priority; }
        public boolean chainsAfterPrimary() { return chained; }
        public Set<String> getSupportedContracts(String type) { return supports(type) ? contracts : Set.of(); }
        public Object execute(CommandContext context) { throw new AssertionError("Inspection must not execute business code"); }
    }
    private HandlerContractInspector inspector(CommandHandlerExtension... handlers) {
        var beans = new DefaultListableBeanFactory();
        for (int i = 0; i < handlers.length; i++) beans.registerSingleton("handler" + i, handlers[i]);
        var manager = mock(AuraPluginManager.class);
        when(manager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of());
        return new HandlerContractInspector(new ExtensionRegistry(manager,
                beans.getBeanProvider(CommandHandlerExtension.class), beans.getBeanProvider(ServiceTaskActionExtension.class)), manager);
    }
    @Test void requiresAnActualPrimary() {
        assertEquals(List.of("primary-handler-missing"), inspector().inspect("edu:run", "v1").findings());
        assertFalse(inspector(new Handler(1, true, Set.of("v1"))).inspect("edu:run", "v1").supported());
    }
    @Test void inspectsAliasesAndTheWinningHandlerInsteadOfAnyRegisteredHandler() {
        var probe = inspector(new Handler(1, false, Set.of("v1")), new Handler(2, false, Set.of("v2")));
        assertFalse(probe.inspect("edu:alias", "v1").supported());
        assertTrue(probe.inspect("edu:alias", "v2").supported());
    }
    @Test void everyChainedHandlerMustSupportTheContract() {
        var probe = inspector(new Handler(2, false, Set.of("v1")), new Handler(1, true, Set.of()));
        assertFalse(probe.inspect("edu:run", "v1").supported());
        assertEquals(2, probe.inspect("edu:run", "v1").runtimeClasses().size());
        assertTrue(inspector(new Handler(2, false, Set.of("v1")), new Handler(1, true, Set.of("v1")))
                .inspect("edu:run", "v1").supported());
    }
    @Test void rejectsEqualPriorityWinnersAndUnknownLegacyContracts() {
        var probe = inspector(new Handler(1, false, Set.of("v1")), new Handler(1, false, Set.of("v1")));
        assertTrue(probe.inspect("edu:run", "v1").findings().contains("primary-handler-ambiguous"));
        CommandHandlerExtension legacy = new CommandHandlerExtension() {
            public String getCommandType() { return "edu:run"; }
            public Object execute(CommandContext context) { return null; }
        };
        assertFalse(inspector(legacy).inspect("edu:run", "v1").supported());
    }
    @Test void rejectsMissingRequirement() {
        assertThrows(IllegalArgumentException.class, () -> inspector().inspect("edu:run", ""));
    }
    @Test void declaredContractWithoutArtifactIdentityCannotProduceCapability() {
        var probe = inspector(new Handler(1, false, Set.of("v1")));
        assertTrue(probe.inspect("edu:run", "v1").supported());
        var observed = probe.observe("edu:run", "v1");
        assertNull(observed.capability());
        assertFalse(observed.contractObservation().supported());
        assertTrue(observed.contractObservation().findings().getFirst().startsWith("handler-artifact-unavailable:"));
    }
    @Test void lifecycleChangeDuringContractReadInvalidatesObservation() {
        var reference = new java.util.concurrent.atomic.AtomicReference<ExtensionRegistry>();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("changingHandler", new CommandHandlerExtension() {
            public String getCommandType() { return "edu:run"; }
            public Object execute(CommandContext context) { return null; }
            public Set<String> getSupportedContracts(String commandType) {
                reference.get().refreshAllCaches();
                return Set.of("v1");
            }
        });
        var manager = mock(AuraPluginManager.class);
        when(manager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of());
        var registry = new ExtensionRegistry(manager, beans.getBeanProvider(CommandHandlerExtension.class),
                beans.getBeanProvider(ServiceTaskActionExtension.class));
        reference.set(registry);
        var result = new HandlerContractInspector(registry, manager).inspect("edu:run", "v1");
        assertFalse(result.supported());
        assertTrue(result.findings().contains("handler-registry-changed"));
    }
    @Test void definitionObservationRejectsGenerationChangeAndDuplicateRequirements() {
        var registry = mock(ExtensionRegistry.class);
        var manager = mock(AuraPluginManager.class);
        when(registry.commandGeneration()).thenReturn(10L, 11L);
        var manifest = new com.auraboot.framework.plugin.dto.imports.PluginManifestExtended();
        manifest.setCommands(List.of());
        var probe = new HandlerContractInspector(registry, manager);
        var result = probe.observeDefinition(manifest, List.of());
        assertEquals(List.of("definition-handler-generation-changed"), result.findings());
        assertTrue(result.capabilities().isEmpty());
        var requirement = new HandlerContractInspector.Requirement("edu:run", "v1");
        assertThrows(IllegalArgumentException.class, () -> probe.observeDefinition(manifest, List.of(requirement, requirement)));
    }

}
