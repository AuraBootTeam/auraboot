package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** Inspects active dispatch selection, not the separate import-time metadata registry. */
@Component
public class HandlerContractInspector {
    private final ExtensionRegistry registry;
    private final com.auraboot.framework.plugin.pf4j.AuraPluginManager pluginManager;

    public HandlerContractInspector(ExtensionRegistry registry,
                                    com.auraboot.framework.plugin.pf4j.AuraPluginManager pluginManager) {
        this.registry = registry;
        this.pluginManager = pluginManager;
    }

    public record Observation(String commandType, String requiredContract, boolean supported,
                              List<String> runtimeClasses, List<String> findings) {}

    public record Capability(String kind, String key, String contract, List<String> providerDigests) {}
    public record ArtifactObservation(Observation contractObservation, long handlerGeneration, Capability capability) {}
    public record Requirement(String key, String contract) {}
    public record DefinitionArtifactObservation(String definitionDigest, DefinitionObservation observation) {}

    public DefinitionArtifactObservation observeDefinitionArtifact(java.nio.file.Path directory, String expectedDigest,
                                                                    List<Requirement> requirements) throws java.io.IOException {
        var source = PinnedPluginSource.capture(directory, expectedDigest);
        var manifest = new com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader().loadFromSource(source);
        return new DefinitionArtifactObservation(source.digest(), observeDefinition(manifest, requirements));
    }

    public record DefinitionObservation(long handlerGeneration, DefinitionHandlerDependencies.Audit dependencies,
                                        List<ArtifactObservation> observations, List<Capability> capabilities,
                                        List<String> findings) {}

    /** Observes all declared plugin requirements together; this is not whole-release admission. */
    public DefinitionObservation observeDefinition(
            com.auraboot.framework.plugin.dto.imports.PluginManifestExtended manifest,
            List<Requirement> requirements) {
        if (requirements == null) throw new IllegalArgumentException("Explicit handler requirements required");
        var pinned = List.copyOf(requirements);
        var identities = new java.util.HashSet<Requirement>();
        var keys = new java.util.HashSet<String>();
        for (var requirement : pinned) {
            if (requirement.key() == null || requirement.key().isBlank() || requirement.contract() == null
                    || requirement.contract().isBlank() || !identities.add(requirement)) {
                throw new IllegalArgumentException("Invalid or duplicate handler requirement");
            }
            keys.add(requirement.key());
        }
        long generation = registry.commandGeneration();
        var dependencies = DefinitionHandlerDependencies.inspect(manifest, keys);
        var findings = new ArrayList<>(dependencies.findings());
        var observations = new ArrayList<ArtifactObservation>();
        var capabilities = new ArrayList<Capability>();
        for (var requirement : pinned) {
            var observation = observe(requirement.key(), requirement.contract());
            observations.add(observation);
            if (observation.handlerGeneration() != generation) findings.add("definition-handler-generation-changed");
            for (var finding : observation.contractObservation().findings()) {
                findings.add(requirement.key() + ":" + requirement.contract() + ":" + finding);
            }
            if (observation.capability() != null) capabilities.add(observation.capability());
        }
        if (registry.commandGeneration() != generation) findings.add("definition-handler-generation-changed");
        return new DefinitionObservation(generation, dependencies, List.copyOf(observations),
                findings.isEmpty() ? List.copyOf(capabilities) : List.of(), List.copyOf(findings));
    }

    public Observation inspect(String commandType, String requiredContract) {
        return evaluate(commandType, requiredContract, false).contractObservation();
    }

    public ArtifactObservation observe(String commandType, String requiredContract) {
        return evaluate(commandType, requiredContract, true);
    }

    private ArtifactObservation evaluate(String commandType, String requiredContract, boolean requireArtifacts) {
        if (commandType == null || commandType.isBlank() || requiredContract == null || requiredContract.isBlank()) {
            throw new IllegalArgumentException("Command type and exact contract are required");
        }
        long generation = registry.commandGeneration();
        var providers = new java.util.TreeSet<String>();
        var chain = new ArrayList<CommandHandlerExtension>();
        var findings = new ArrayList<String>();
        var runtimeClasses = new ArrayList<String>();
        var primary = registry.getCommandHandler(commandType);
        if (primary.isEmpty()) {
            findings.add("primary-handler-missing");
        } else {
            var selected = primary.get();
            long winners = registry.getAllCommandHandlers().stream()
                    .filter(handler -> handler.supports(commandType) && !handler.chainsAfterPrimary())
                    .filter(handler -> handler.getPriority() == selected.getPriority()).count();
            if (winners != 1) findings.add("primary-handler-ambiguous");
            chain.add(selected);
            chain.addAll(registry.getSecondaryCommandHandlers(commandType));
        }
        for (var handler : chain) {
            check(handler, commandType, requiredContract, runtimeClasses, findings);
            if (requireArtifacts) {
                var implementation = org.springframework.aop.support.AopUtils.getTargetClass(handler);
                var artifact = pluginManager.observeLoadedArtifact(implementation);
                if (artifact.isPresent()) providers.add(artifact.get().digest());
                else findings.add("handler-artifact-unavailable:" + implementation.getName());
            }
        }
        if (generation != registry.commandGeneration()) findings.add("handler-registry-changed");
        var observation = new Observation(commandType, requiredContract, findings.isEmpty(),
                List.copyOf(runtimeClasses), List.copyOf(findings));
        var capability = requireArtifacts && findings.isEmpty()
                ? new Capability("handler", commandType, requiredContract, List.copyOf(providers)) : null;
        return new ArtifactObservation(observation, generation, capability);
    }

    private static void check(CommandHandlerExtension handler, String commandType, String contract,
                              List<String> runtimeClasses, List<String> findings) {
        String name = org.springframework.aop.support.AopUtils.getTargetClass(handler).getName();
        runtimeClasses.add(name);
        var supported = handler.getSupportedContracts(commandType);
        if (supported == null || !supported.contains(contract)) findings.add("handler-contract-unavailable:" + name);
    }
}
