package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Read-only inspection of a registered definition component; never release admission or binding. */
@Service
public class RegisteredDefinitionHandlerInspector {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HandlerContractInspector handlers;
    public RegisteredDefinitionHandlerInspector(JdbcTemplate jdbc, ObjectMapper mapper, HandlerContractInspector handlers) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.handlers = handlers;
    }
    public record Requirement(String kind, String key, String contract) {}
    public record Result(String releaseId, String releaseDigest, String componentKey,
                         HandlerContractInspector.DefinitionArtifactObservation handlerObservation,
                         List<Requirement> unobservedRequirements) {}

    public record UnobservedComponent(String key, String type, String digest) {}
    public record Finding(String componentKey, String code) {}
    public record ReleaseObservation(String releaseId, String releaseDigest, long handlerGeneration,
                                     List<Result> definitions, List<UnobservedComponent> unobservedComponents,
                                     List<Finding> findings, List<HandlerContractInspector.Capability> capabilities) {}

    public ReleaseObservation inspectRelease(String releaseId, String expectedReleaseDigest,
                                              java.util.Map<String, Path> definitionArtifacts) throws IOException {
        if (definitionArtifacts == null) throw new IllegalArgumentException("Explicit definition artifact map required");
        var artifacts = java.util.Map.copyOf(definitionArtifacts);
        long generation = handlers.currentGeneration();
        var release = loadRelease(releaseId, expectedReleaseDigest);
        var keys = new HashSet<String>();
        var definitionKeys = new HashSet<String>();
        var unobserved = new ArrayList<UnobservedComponent>();
        var findings = new ArrayList<Finding>();
        for (var component : release.path("components")) {
            String key = component.path("key").asText();
            if (key.isBlank() || !keys.add(key)) throw new IllegalStateException("Invalid or duplicate registered component key");
            if ("definition".equals(component.path("type").asText())) definitionKeys.add(key);
            else {
                unobserved.add(new UnobservedComponent(key, component.path("type").asText(), component.path("digest").asText()));
                findings.add(new Finding(key, "component-type-unobserved"));
            }
        }
        if (!definitionKeys.equals(artifacts.keySet())) throw new IllegalArgumentException("Definition artifacts must exactly match registered definition components");
        var definitions = new ArrayList<Result>();
        for (var key : definitionKeys.stream().sorted().toList()) {
            var result = inspectComponent(releaseId, expectedReleaseDigest, release, key, artifacts.get(key));
            definitions.add(result);
            var observation = result.handlerObservation().observation();
            if (observation.handlerGeneration() != generation) findings.add(new Finding(key, "release-handler-generation-changed"));
            observation.findings().forEach(finding -> findings.add(new Finding(key, finding)));
            result.unobservedRequirements().forEach(requirement -> findings.add(new Finding(key,
                    "capability-unobserved:" + requirement.kind() + ":" + requirement.key() + ":" + requirement.contract())));
        }
        var capabilities = new java.util.TreeMap<String, HandlerContractInspector.Capability>();
        for (var definition : definitions) {
            for (var capability : definition.handlerObservation().observation().capabilities()) {
                String identity = mapper.writeValueAsString(List.of(capability.kind(), capability.key(), capability.contract()));
                var previous = capabilities.putIfAbsent(identity, capability);
                if (previous != null && !previous.equals(capability)) {
                    findings.add(new Finding(definition.componentKey(), "release-handler-provider-conflict"));
                }
            }
        }
        if (handlers.currentGeneration() != generation) findings.add(new Finding(null, "release-handler-generation-changed"));
        return new ReleaseObservation(releaseId, expectedReleaseDigest, generation,
                List.copyOf(definitions), List.copyOf(unobserved), List.copyOf(findings),
                findings.isEmpty() ? List.copyOf(capabilities.values()) : List.of());
    }

    public Result inspect(String releaseId, String expectedReleaseDigest, String componentKey, Path artifactDirectory) throws IOException {
        if (componentKey == null || componentKey.isBlank()) throw new IllegalArgumentException("Registered component required");
        return inspectComponent(releaseId, expectedReleaseDigest, loadRelease(releaseId, expectedReleaseDigest), componentKey, artifactDirectory);
    }

    private JsonNode loadRelease(String releaseId, String expectedReleaseDigest) throws IOException {
        if (releaseId == null || !releaseId.matches("[0-9A-HJKMNP-TV-Z]{26}")
                || expectedReleaseDigest == null || !expectedReleaseDigest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Exact registered release required");
        var row = jdbc.queryForObject("SELECT manifest_text,digest FROM ab_application_release WHERE release_id=?",
                (result, index) -> new String[]{result.getString(1), result.getString(2)}, releaseId);
        if (row == null || !expectedReleaseDigest.equals(row[1]) || !expectedReleaseDigest.equals(hash(row[0]))) {
            throw new IllegalStateException("Registered release digest mismatch");
        }
        var release = mapper.readTree(row[0]);
        if (!releaseId.equals(release.path("releaseId").asText()) || !release.path("components").isArray()) {
            throw new IllegalStateException("Registered release identity or components invalid");
        }
        return release;
    }

    private Result inspectComponent(String releaseId, String expectedReleaseDigest, JsonNode release,
                                    String componentKey, Path artifactDirectory) throws IOException {
        JsonNode selected = null;
        for (var component : release.path("components")) {
            if (componentKey.equals(component.path("key").asText())) {
                if (selected != null) throw new IllegalStateException("Duplicate registered component key");
                selected = component;
            }
        }
        if (selected == null || !"definition".equals(selected.path("type").asText())) {
            throw new IllegalArgumentException("Registered definition component not found");
        }
        var contract = selected.path("compatibilityContract");
        var fields = new HashSet<String>();
        contract.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("schemaVersion", "requiredCapabilities")) || !contract.path("schemaVersion").isIntegralNumber()
                || contract.path("schemaVersion").asInt() != 1 || !contract.path("requiredCapabilities").isArray()) {
            throw new IllegalArgumentException("Explicit versioned component requirements required");
        }
        var requiredHandlers = new ArrayList<HandlerContractInspector.Requirement>();
        var unobserved = new ArrayList<Requirement>();
        var identities = new HashSet<Requirement>();
        for (var item : contract.path("requiredCapabilities")) {
            fields.clear(); item.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("kind", "key", "contract")) || !Set.of("handler", "web", "schema").contains(item.path("kind").asText())
                    || !validText(item.path("key")) || !validText(item.path("contract"))) throw new IllegalArgumentException("Invalid registered capability requirement");
            var requirement = new Requirement(item.path("kind").asText(), item.path("key").asText(), item.path("contract").asText());
            if (!identities.add(requirement)) throw new IllegalArgumentException("Duplicate registered capability requirement");
            if (requirement.kind().equals("handler")) requiredHandlers.add(new HandlerContractInspector.Requirement(requirement.key(), requirement.contract()));
            else unobserved.add(requirement);
        }
        return new Result(releaseId, expectedReleaseDigest, componentKey,
                handlers.observeDefinitionArtifact(artifactDirectory, selected.path("digest").asText(), requiredHandlers), List.copyOf(unobserved));
    }
    private static boolean validText(JsonNode value) { return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 200; }
    private static String hash(String text) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
}
