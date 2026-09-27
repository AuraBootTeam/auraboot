package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Links exact registered contract bytes to a catalog observation, never migration or admission authority. */
@Service
public class RegisteredSchemaContractInspector {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectReader contractReader;
    private final SchemaContractInspector schemas;

    public RegisteredSchemaContractInspector(JdbcTemplate jdbc, ObjectMapper mapper, SchemaContractInspector schemas) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        var text = this.mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for (var shape : java.util.List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) {
            text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
        this.contractReader = this.mapper.readerFor(ContractDocument.class);
        this.schemas = schemas;
    }

    public record ContractDocument(int schemaVersion, String key, String contract, Map<String, SchemaContractInspector.Contract> tables) {}
    public record Observation(String platformReleaseId, String platformReleaseDigest, String artifactId,
                              String artifactDigest, String key, String contract, SchemaContractInspector.BatchObservation catalog) {}

    public Observation inspect(String platformReleaseId, String platformReleaseDigest, String artifactId, byte[] contractBytes) {
        require(platformReleaseId != null && platformReleaseId.matches("[0-9A-HJKMNP-TV-Z]{26}"), "Exact platform release ID required");
        require(platformReleaseDigest != null && platformReleaseDigest.matches("sha256:[0-9a-f]{64}"), "Pinned platform release digest required");
        require(validText(artifactId) && contractBytes != null && contractBytes.length > 0 && contractBytes.length <= 1024 * 1024,
                "Explicit schema contract artifact required (maximum 1 MiB)");
        byte[] bytes = contractBytes.clone();
        var stored = jdbc.queryForObject("SELECT manifest_text,digest FROM ab_platform_release_registry WHERE release_id=?",
                (row, index) -> new String[]{row.getString(1), row.getString(2)}, platformReleaseId);
        if (stored == null || !platformReleaseDigest.equals(stored[1])
                || !platformReleaseDigest.equals(hash(stored[0].getBytes(StandardCharsets.UTF_8)))) {
            throw new IllegalStateException("Registered platform digest mismatch");
        }
        try {
            var manifest = mapper.readTree(stored[0]);
            require(platformReleaseId.equals(manifest.path("releaseId").asText()) && manifest.path("artifacts").isArray(),
                    "Invalid registered platform identity");
            var artifacts = java.util.stream.StreamSupport.stream(manifest.path("artifacts").spliterator(), false)
                    .filter(item -> "config".equals(item.path("type").asText()) && artifactId.equals(item.path("id").asText())).toList();
            require(artifacts.size() == 1, "Exactly one registered config artifact required");
            String artifactDigest = artifacts.getFirst().path("digest").asText();
            require(artifactDigest.equals(hash(bytes)), "Schema contract bytes differ from registered artifact digest");
            ContractDocument document = contractReader.readValue(bytes);
            require(document != null && document.schemaVersion() == 1 && validText(document.key()) && validText(document.contract())
                    && document.tables() != null && !document.tables().isEmpty(), "Explicit versioned schema contract required");
            var catalog = schemas.inspectAll(document.tables());
            return new Observation(platformReleaseId, platformReleaseDigest, artifactId, artifactDigest,
                    document.key(), document.contract(), catalog);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid schema contract JSON", invalid);
        }
    }

    private static boolean validText(String value) { return value != null && !value.isBlank() && value.length() <= 200; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
}
