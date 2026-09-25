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

    public Result inspect(String releaseId, String expectedReleaseDigest, String componentKey, Path artifactDirectory) throws IOException {
        if (releaseId == null || !releaseId.matches("[0-9A-HJKMNP-TV-Z]{26}")
                || expectedReleaseDigest == null || !expectedReleaseDigest.matches("sha256:[0-9a-f]{64}")
                || componentKey == null || componentKey.isBlank()) throw new IllegalArgumentException("Exact registered release and component required");
        var row = jdbc.queryForObject("SELECT manifest_text,digest FROM ab_application_release WHERE release_id=?",
                (result, index) -> new String[]{result.getString(1), result.getString(2)}, releaseId);
        if (row == null || !expectedReleaseDigest.equals(row[1]) || !expectedReleaseDigest.equals(hash(row[0]))) {
            throw new IllegalStateException("Registered release digest mismatch");
        }
        var release = mapper.readTree(row[0]);
        if (!releaseId.equals(release.path("releaseId").asText()) || !release.path("components").isArray()) {
            throw new IllegalStateException("Registered release identity or components invalid");
        }
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
