package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** The publication route and installation boundary jointly authorize synchronous business calls. */
@Service
@RequiredArgsConstructor
public class OpenApiSynchronousIntegrationService {
    public static final String CANDIDATE = "installed-integration.execute";
    private final AuraPluginManager plugins;
    private final InstalledIntegrationRegistry registry;
    private final InstalledIntegrationService installed;
    private final OpenApiProtocolHash hashes;
    private final ObjectMapper json;
    record Bound(SynchronousIntegrationEndpointExtension extension,
                 SynchronousIntegrationEndpointExtension.Definition definition,
                 ExternalEventIngressExtension.Registration registration) { }

    private Bound resolve(String code) {
        var matches = new ArrayList<Bound>();
        for (var extension : plugins.getExtensionsOfType(SynchronousIntegrationEndpointExtension.class)) {
            var artifact = plugins.observeLoadedArtifact(extension.getClass()).orElseThrow(OpenApiSynchronousIntegrationService::denied);
            var definitions = extension.definitions();
            if (definitions == null || definitions.size() > 32) throw denied();
            for (var definition : definitions) {
                if (!definition.code().equals(code)) continue;
                var source = registry.registration(definition.sourceCode()).orElseThrow(OpenApiSynchronousIntegrationService::denied);
                if (!source.getKey().providerPluginId().equals(artifact.pluginId()) || !source.getValue().equals(artifact.digest())
                        || !source.getKey().eventTypes().contains(definition.eventType())) throw denied();
                matches.add(new Bound(extension, definition, source.getKey()));
            }
        }
        if (matches.size() != 1) throw new IllegalArgumentException("Exactly one registered synchronous business contract required");
        return matches.getFirst();
    }
    public Map<String,Object> description(String code) {
        var bound = resolve(code);
        return Map.of("definition", bound.definition(), "registration", bound.registration());
    }
    public int timeout(String code) { return resolve(code).definition().timeoutMs(); }
    public String scope(String code) { return resolve(code).registration().requiredScope(); }

    @Transactional
    public OpenApiResourceService.Result execute(MachinePrincipal principal, String code, String namespace,
            String key, Map<String,Object> input) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))
            throw new IllegalArgumentException("Idempotency-Key must contain 8 to 128 supported characters");
        var bound = resolve(code); var definition = bound.definition();
        validate(definition.inputSchema(), input, "Invalid published integration input");
        var request = bound.extension().prepare(code, input);
        String external = "sync-" + hashes.fingerprint(Map.of("endpoint", namespace, "binding", code, "key", key), false);
        String durable = "sync:" + principal.installationPid() + ":" + definition.sourceCode() + ":" + external;
        String subject = request.subjectType() + "/" + request.subjectPid();
        var envelope = new IntegrationEventEnvelope("1.0", durable, definition.eventType(),
                "open-platform/" + principal.installationPid() + "/" + definition.sourceCode(), subject,
                request.occurredAt(), principal.tenantId(), external, null, subject, request.sequence(), request.payload(),
                Map.of("applicationPid", principal.applicationPid(), "installationPid", principal.installationPid(),
                        "sourceCode", definition.sourceCode(), "externalEventId", external,
                        "externalSubjectType", request.subjectType(), "externalSubjectPid", request.subjectPid()));
        var execution = installed.executeSynchronouslyWithReceipt(principal, definition.sourceCode(), envelope);
        var result = bound.extension().project(code, key, execution.data());
        if (!key.equals(result.get("requestId"))) throw new IllegalStateException("Synchronous result request identity mismatch");
        validate(definition.resultSchema(), result, "Installed integration returned an invalid business result");
        return new OpenApiResourceService.Result(200, result, Map.of(), execution.replay());
    }
    private void validate(Map<String,Object> schema, Map<String,Object> value, String message) {
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(json.valueToTree(schema));
        if (!validator.validate(json.valueToTree(value)).isEmpty()) throw new IllegalArgumentException(message);
    }
    public Map<String,Object> document(String code, String path) {
        var d = resolve(code).definition();
        return Map.of("openapi", "3.1.0", "paths", Map.of(path, Map.of("post", Map.of(
                "parameters", List.of(Map.of("in", "header", "name", "Idempotency-Key", "required", true,
                        "schema", Map.of("type", "string", "pattern", "^[A-Za-z0-9._:-]{8,128}$"))),
                "requestBody", Map.of("required", true, "content", Map.of("application/json", Map.of("schema",
                        Map.of("type", "object", "required", List.of("input"), "additionalProperties", false,
                                "properties", Map.of("input", d.inputSchema()))))),
                "responses", Map.of("200", Map.of("description", "Committed business result", "content",
                        Map.of("application/json", Map.of("schema", d.resultSchema()))))))),
                "components", Map.of("securitySchemes", Map.of("integrationToken", Map.of("type", "http", "scheme", "bearer"))));
    }
    private static SecurityException denied() { return new SecurityException("Synchronous integration provider denied"); }
}
