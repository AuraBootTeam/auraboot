package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Inventories plugin dispatch targets from the same consolidated config used by import. */
public final class DefinitionHandlerDependencies {
    private DefinitionHandlerDependencies() {}
    public record Reference(String commandCode, String handlerCode, boolean explicit) {}
    public record Audit(List<Reference> references, List<String> findings) {}

    public static Audit inspect(PluginManifestExtended manifest, Set<String> declaredHandlerKeys) {
        if (manifest == null || declaredHandlerKeys == null
                || declaredHandlerKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("Resolved manifest and explicit handler declaration set required");
        }
        var references = new ArrayList<Reference>();
        var findings = new ArrayList<String>();
        var codes = new HashSet<String>();
        if (manifest.getCommands() == null) {
            throw new IllegalArgumentException("Resolved command list required; use an explicit empty list when absent");
        }
        for (var command : manifest.getCommands()) {
            if (command == null || command.getCode() == null || command.getCode().isBlank() || !codes.add(command.getCode())) {
                throw new IllegalArgumentException("Invalid or duplicate command definition");
            }
            Object handler = command.getConsolidatedExecutionConfig().get("handler");
            boolean explicit = handler instanceof String text && !text.isBlank();
            String target = explicit ? ((String) handler).trim() : command.getCode();
            references.add(new Reference(command.getCode(), target, explicit));
            if (explicit && !declaredHandlerKeys.contains(target)) findings.add("handler-requirement-undeclared:" + target);
            // The pipeline also probes commandCode when no explicit target exists. Absence needs runtime evidence.
            if (!explicit) findings.add("implicit-handler-dispatch-unresolved:" + command.getCode());
        }
        return new Audit(List.copyOf(references), List.copyOf(findings));
    }
}
