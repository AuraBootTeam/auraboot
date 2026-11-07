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

    public record Consumer(String commandCode, String modelCode, String commandType, String publicationStatus,
                           List<String> permissions, boolean businessDeclared, String targetAction, String source) { }

    /** A declaration inventory, including legacy consumers; never an execution/access verdict. */
    public List<Consumer> consumers(java.util.Set<String> permissionCodes) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null || permissionCodes == null
                || permissionCodes.stream().anyMatch(code -> code == null || code.isBlank())) throw denied();
        boolean all = permissionCodes.contains("*");
        var codes = permissionCodes.stream().filter(code -> !"*".equals(code) && !"meta.command.execute".equals(code))
                .sorted().toList();
        if (!all && codes.isEmpty()) return List.of();
        Long tenant = MetaContext.getCurrentTenantId();
        var definitions = commands.findCurrentPermissionConsumers(codes, all);
        if (definitions.stream().map(command -> command.getCode()).distinct().count() != definitions.size()
                || definitions.stream().anyMatch(command -> !Objects.equals(tenant, command.getTenantId())
                    || command.getId() == null || command.getCode() == null || command.getCode().isBlank()
                    || !Boolean.TRUE.equals(command.getIsCurrent()) || !Boolean.FALSE.equals(command.getDeletedFlag()))) throw denied();
        if (definitions.isEmpty()) return List.of();
        var ids = definitions.stream().map(command -> command.getId()).toList();
        var bindings = rules.findByCommandIds(ids);
        if (bindings.stream().anyMatch(rule -> !Objects.equals(tenant, rule.getTenantId())
                || !ids.contains(rule.getCommandId()) || rule.getId() == null)
                || bindings.stream().map(rule -> rule.getId()).distinct().count() != bindings.size()) throw denied();
        return definitions.stream().sorted(java.util.Comparator.comparing(command -> command.getCode())).map(command -> {
            try {
                var config = json.readTree(command.getExecutionConfig());
                if (config == null || !config.isObject() || !config.path("type").isTextual()
                        || config.path("type").asText().isBlank()) throw denied();
                var declared = config.path("permissions");
                if (!declared.isMissingNode() && !declared.isArray()) throw denied();
                var permissions = new java.util.ArrayList<String>();
                for (var code : declared) {
                    if (!code.isTextual() || code.asText().isBlank() || !code.asText().equals(code.asText().strip())
                            || permissions.contains(code.asText())) throw denied();
                    permissions.add(code.asText());
                }
                if (!all && permissions.stream().noneMatch(codes::contains)) throw denied();
                var params = config.path("handlerParams");
                String source = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsString(Map.of("command", command, "rules", bindings.stream()
                            .filter(rule -> command.getId().equals(rule.getCommandId()))
                            .sorted(java.util.Comparator.comparing(rule -> rule.getId())).toList()));
                return new Consumer(command.getCode(), command.getModelCode(), config.path("type").asText(),
                        command.getStatus(), permissions.stream().sorted().toList(),
                        "business".equals(params.path("targetRecordAuthorization").asText()),
                        params.path("targetRecordAction").isTextual() ? params.path("targetRecordAction").asText() : null, source);
            } catch (JsonProcessingException invalid) {
                // CATCH: invalid consumer metadata must block review instead of disappearing.
                throw new AccessDeniedException("Command consumer metadata cannot be reviewed", invalid);
            }
        }).toList();
    }

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

    /**
     * Pin registered PF4J entry metadata to a reviewed role revision. Missing definitions stay
     * explicit in the source set; this fingerprint never asserts that an owner is executable.
     */
    public String ownerSources(List<String> commandCodes) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) throw denied();
        if (commandCodes == null || commandCodes.stream().anyMatch(code -> code == null || code.isBlank())) throw denied();
        var codes = commandCodes.stream().distinct().sorted().toList();
        if (codes.isEmpty()) return "";
        var tenant = MetaContext.getCurrentTenantId();
        var definitions = commands.findCurrentDefinitionsByCodes(codes);
        if (definitions.stream().map(command -> command.getCode()).distinct().count() != definitions.size()
                || definitions.stream().anyMatch(command -> !Objects.equals(tenant, command.getTenantId())
                    || !codes.contains(command.getCode()) || command.getId() == null
                    || !Boolean.TRUE.equals(command.getIsCurrent()) || !Boolean.FALSE.equals(command.getDeletedFlag()))) throw denied();
        var ids = definitions.stream().map(command -> command.getId()).toList();
        var bindings = ids.isEmpty() ? List.<com.auraboot.framework.meta.entity.BindingRule>of() : rules.findByCommandIds(ids);
        if (bindings.stream().anyMatch(rule -> !Objects.equals(tenant, rule.getTenantId())
                || !ids.contains(rule.getCommandId()) || rule.getId() == null)
                || bindings.stream().map(rule -> rule.getId()).distinct().count() != bindings.size()) throw denied();
        var state = new java.util.TreeMap<String, Object>();
        state.put("requestedOwners", codes);
        state.put("missingDefinitions", codes.stream().filter(code -> definitions.stream()
                .noneMatch(command -> code.equals(command.getCode()))).toList());
        state.put("commands", definitions.stream().sorted(java.util.Comparator.comparing(command -> command.getCode())).toList());
        state.put("rules", bindings.stream().sorted(java.util.Comparator.comparing(rule -> rule.getId())).toList());
        try {
            return json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(state);
        } catch (JsonProcessingException invalid) {
            // CATCH: a failed source fingerprint must reject review, never omit its inputs.
            throw new AccessDeniedException("Business owner entry metadata cannot be fingerprinted", invalid);
        }
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Native business boundary metadata is unavailable or belongs to another tenant");
    }
}
