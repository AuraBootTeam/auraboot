package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.security.NativeBusinessCommandContract;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fresh database definitions for authoring; reading this catalog never authorizes execution. */
@Service
@RequiredArgsConstructor
public class NativeBusinessBoundaryCatalog {
    private final CommandDefinitionMapper commands;
    private final BindingRuleMapper rules;
    private final ExtensionRegistry extensions;
    private final ObjectMapper json;

    public record Definition(String commandCode, NativeBusinessCommandContract contract, String source) { }

    public List<Definition> snapshot() {
        if (!MetaContext.exists()) throw denied();
        Long tenant = MetaContext.getCurrentTenantId();
        if (tenant == null) throw denied();
        var definitions = commands.findCurrentNativeBusinessDefinitions();
        if (definitions.stream().map(command -> command.getCode()).distinct().count() != definitions.size()) throw denied();
        for (var command : definitions) {
            if (!Objects.equals(tenant, command.getTenantId()) || command.getId() == null
                    || !Boolean.TRUE.equals(command.getIsCurrent()) || !Boolean.FALSE.equals(command.getDeletedFlag())
                    || !Status.PUBLISHED.getCode().equals(command.getStatus()) || command.getExecutionConfig() == null) throw denied();
        }
        if (definitions.isEmpty()) return List.of();
        var commandIds = definitions.stream().map(command -> command.getId()).toList();
        var knownIds = new java.util.HashSet<>(commandIds);
        var allRules = rules.findByCommandIds(commandIds);
        if (allRules.stream().anyMatch(rule -> !Objects.equals(tenant, rule.getTenantId())
                || !knownIds.contains(rule.getCommandId()))) throw denied();
        var byCommand = allRules.stream().collect(java.util.stream.Collectors.groupingBy(rule -> rule.getCommandId()));
        return definitions.stream().map(command -> {
            var bindings = byCommand.getOrDefault(command.getId(), List.of());
            var enabled = bindings.stream().filter(rule -> Boolean.TRUE.equals(rule.getEnabled())).toList();
            try {
                Map<String, Object> config = json.readValue(command.getExecutionConfig(), new TypeReference<>() { });
                var contract = NativeBusinessCommandContract.describe(command, config,
                        enabled.isEmpty() ? Map.of() : Map.of("enabled", enabled), extensions);
                String source = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsString(Map.of("command", command, "rules", bindings));
                return new Definition(command.getCode(), contract, source);
            } catch (JsonProcessingException invalid) {
                // CATCH: malformed stored metadata is a validation error, never a reason to omit a boundary.
                throw new AccessDeniedException("Native business boundary metadata is invalid", invalid);
            }
        }).toList();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Native business boundary metadata is unavailable or belongs to another tenant");
    }
}
