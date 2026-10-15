package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.plugin.extension.PublicApiHandlerExtension;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Pins bounded command effects or a registered read-only handler against its actual resource source. */
@Component
@RequiredArgsConstructor
public class OpenApiActionValidator {
    private final OpenApiPublicationService resources;
    private final CommandService commands;
    private final PublicApiHandlerRegistry handlers;
    private final ObjectMapper json;
    private final OpenApiProtocolHash protocolHash;
    private final List<OpenApiCapabilityContributor> contributors;

    public record SourceView(String sourceKind, String sourceCode, String name, String fingerprint, String permission,
            Map<String, OpenApiQueryContract.Parameter> parameters, Map<String, String> inputFields,
            Set<String> effectFields, Map<String, OpenApiResourceContract.Type> outputFields,
            int minTimeoutMs, boolean available, String reason) { }

    public List<SourceView> sources(Long tenant, String resourceCode) {
        var resource = resource(tenant, resourceCode);
        List<SourceView> result = new ArrayList<>();
        if (resource.operations().contains(OpenApiResourceContract.Operation.UPDATE)) {
            for (var source : commands.listByModelCode(resource.modelCode())) {
                if (!"published".equals(source.getStatus()) || !"state_transition".equals(source.getType())) continue;
                try { result.add(describe(tenant, resourceCode, "command", source.getCode())); }
                catch (IllegalArgumentException unsupported) {
                    // CATCH: a visible disabled candidate preserves the source failure; publication still rejects it.
                    result.add(new SourceView("command", source.getCode(), source.getDisplayName(), null, null,
                            Map.of(), Map.of(), Set.of(), Map.of(), 1, false, unsupported.getMessage()));
                }
            }
        }
        if (resource.operations().contains(OpenApiResourceContract.Operation.LIST)) {
            for (var handler : handlers.list()) result.add(describe(tenant, resourceCode, "handler", handler.definition().code()));
        }
        return List.copyOf(result);
    }

    public SourceView describe(Long tenant, String resourceCode, String kind, String code) {
        var resource = resource(tenant, resourceCode);
        if ("handler".equals(kind)) {
            require(resource.operations().contains(OpenApiResourceContract.Operation.LIST), "Handler source requires a LIST resource");
            var handler = handlers.require(code); var definition = handler.definition();
            Map<String, OpenApiQueryContract.Parameter> parameters = new TreeMap<>();
            definition.parameters().forEach((alias, type) -> parameters.put(alias,
                    new OpenApiQueryContract.Parameter(type(type), definition.required().contains(alias), false, null)));
            Map<String, OpenApiResourceContract.Type> output = new TreeMap<>();
            definition.output().forEach((alias, type) -> output.put(alias, type(type)));
            return new SourceView(kind, code, definition.name(), hash(Map.of("code", definition.code(), "version", definition.version(), "parameters", definition.parameters(), "required", List.copyOf(new TreeSet<>(definition.required())),
                    "output", definition.output())), null, Map.copyOf(parameters), Map.of(), Set.of(), Map.copyOf(output),
                    resource.timeoutMs(), true, null);
        }
        require("command".equals(kind), "Unknown business API source kind");
        require(resource.operations().contains(OpenApiResourceContract.Operation.UPDATE), "Command requires an UPDATE resource");
        var source = commands.findByCode(code);
        require("published".equals(source.getStatus()) && "state_transition".equals(source.getType())
                && resource.modelCode().equals(source.getModelCode()) && "L1".equals(source.getCmdRiskLevel()),
                "Published local state-transition command on the bound model required");
        require(source.getBindingRules() == null || source.getBindingRules().isEmpty(), "Public command binding rules require an explicit supported source contract");
        var config = parse(source.getExecutionConfig());
        require(!config.has("sideEffects") || config.path("sideEffects").isArray() && config.path("sideEffects").isEmpty(),
                "Public state transitions cannot declare additional side effects");
        require(config.path("permissions").isArray() && config.path("permissions").size() == 1
                && config.path("permissions").get(0).isTextual(), "Exactly one source command permission required");
        require((!config.has("inputFields") || config.path("inputFields").isArray()) && config.path("inputFields").size() <= 32
                && config.path("stateField").isTextual(), "Explicit command input and state effects required");
        if (source.getTargetModels() != null && !source.getTargetModels().isBlank()) {
            var targets = parse(source.getTargetModels());
            require(targets.isArray() && java.util.stream.StreamSupport.stream(targets.spliterator(), false)
                    .allMatch(target -> target.isTextual() && resource.modelCode().equals(target.asText())), "Cross-model commands are not public state transitions");
        }
        Map<String, String> aliases = new HashMap<>(); resource.fields().forEach((alias, field) -> aliases.put(field.source(), alias));
        Map<String, OpenApiQueryContract.Parameter> parameters = new TreeMap<>(); Map<String, String> inputs = new TreeMap<>();
        Set<String> effects = new TreeSet<>();
        for (var input : config.path("inputFields")) {
            String alias = aliases.get(input.asText());
            require(input.isTextual() && alias != null && resource.fields().get(alias).writable(), "Every command input requires a writable public resource field");
            var field = resource.fields().get(alias);
            require(parameters.putIfAbsent(alias, new OpenApiQueryContract.Parameter(field.type(), true, false, field.items())) == null,
                    "Duplicate public command input");
            inputs.put(alias, alias); effects.add(alias);
        }
        Set<String> generated = new HashSet<>(); generated.add(config.path("stateField").asText());
        require(!config.has("autoSetFields") || config.get("autoSetFields").isObject(), "Invalid source command effects");
        if (config.has("autoSetFields")) config.path("autoSetFields").fieldNames().forEachRemaining(generated::add);
        for (String internal : generated) {
            String alias = aliases.get(internal);
            require(alias != null && resource.fields().get(alias).readable(), "Every controlled command effect requires an explicit readable public field");
            effects.add(alias);
        }
        String fingerprint = hash(Map.of("model", source.getModelCode(), "type", source.getType(), "config", config,
                "inputSchema", Objects.toString(source.getInputSchema(), ""), "targets", Objects.toString(source.getTargetModels(), "")));
        return new SourceView(kind, code, source.getDisplayName(), fingerprint, config.path("permissions").get(0).asText(),
                Map.copyOf(parameters), Map.copyOf(inputs), Set.copyOf(effects), Map.of(), 1, true, null);
    }

    public void validate(Long tenant, OpenApiActionContract contract) {
        require(new OpenApiCapabilityRegistry(contributors).resolve("POST", "/api/open/v1/actions/" + contract.code() + ":execute").isEmpty(),
                "Business API route is already reserved");
        var current = describe(tenant, contract.resourceCode(), contract.sourceKind(), contract.sourceCode());
        if ("handler".equals(contract.sourceKind())) require(contract.timeoutMs() >= resource(tenant, contract.resourceCode()).timeoutMs(),
                "Handler execution budget must include its bound resource query budget");
        require(current.fingerprint().equals(contract.sourceFingerprint()) && Objects.equals(current.permission(), contract.permission())
                && current.parameters().equals(contract.parameters()) && current.inputFields().equals(contract.inputFields())
                && current.effectFields().equals(contract.effectFields()) && current.outputFields().equals(contract.outputFields()),
                "Business API source fingerprint, typed input or effect declaration changed");
    }
    private OpenApiResourceContract resource(Long tenant, String code) {
        return resources.active(tenant, code).orElseThrow(() -> new IllegalArgumentException("Published bound resource required"));
    }
    private OpenApiResourceContract.Type type(PublicApiHandlerExtension.ValueType value) { return OpenApiResourceContract.Type.valueOf(value.name()); }
    private String hash(Object value) {
        return protocolHash.fingerprint(value, false);
    }
    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Business API source JSON is invalid", invalid); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
