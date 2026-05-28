package com.auraboot.framework.semantic.parser;

import com.auraboot.framework.semantic.dto.SemanticModelDTO;
import com.auraboot.framework.semantic.exception.SemanticYamlInvalidException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses {@code *.semantic.yml} content into a {@link SemanticModelDTO}.
 *
 * <p>Two-step pipeline:
 * <ol>
 *   <li>YAML → JsonNode (via {@code jackson-dataformat-yaml})</li>
 *   <li>JsonNode validated against {@code semantic-v0.1.schema.json} (networknt validator,
 *       Draft 2020-12). Schema violations throw {@link SemanticYamlInvalidException} with
 *       all errors in one shot (no fail-fast — better UX for authors).</li>
 *   <li>JsonNode → SemanticModelDTO (Jackson deserialization, {@code @JsonProperty}
 *       handles snake_case ↔ camelCase mapping).</li>
 * </ol>
 *
 * <p>Business rules (cross-field, security, references) are enforced by
 * {@link SemanticYamlValidator} as a separate step after parsing.
 *
 * @see SemanticYamlValidator
 */
@Slf4j
@Component
public class SemanticYamlParser {

    private static final String SCHEMA_CLASSPATH = "semantic/semantic-v0.1.schema.json";

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private final JsonSchema schema;

    public SemanticYamlParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
        this.schema = loadSchema();
    }

    private JsonSchema loadSchema() {
        try (InputStream in = new ClassPathResource(SCHEMA_CLASSPATH).getInputStream()) {
            JsonNode schemaNode = jsonMapper.readTree(in);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load semantic schema from classpath: " + SCHEMA_CLASSPATH, e);
        }
    }

    /**
     * Parse a YAML string into a {@link SemanticModelDTO}.
     *
     * @throws SemanticYamlInvalidException if YAML is malformed or fails schema validation
     */
    public SemanticModelDTO parse(String yaml) {
        JsonNode tree;
        try {
            tree = yamlMapper.readTree(yaml);
        } catch (IOException e) {
            throw new SemanticYamlInvalidException("Malformed YAML: " + e.getMessage(),
                    List.of("yaml-parse: " + e.getMessage()), e);
        }
        return parseTree(tree);
    }

    /** Convenience overload accepting raw bytes (e.g. from plugin resource loading). */
    public SemanticModelDTO parse(byte[] yamlBytes) {
        return parse(new String(yamlBytes, StandardCharsets.UTF_8));
    }

    /** Parse a tree that has already been read (used when caller already holds JsonNode). */
    public SemanticModelDTO parseTree(JsonNode tree) {
        Set<ValidationMessage> errors = schema.validate(tree);
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream()
                    .map(this::formatError)
                    .sorted()
                    .collect(Collectors.toList());
            log.warn("Semantic YAML schema validation failed: {} errors", messages.size());
            throw new SemanticYamlInvalidException(
                    "JSON Schema validation failed: " + messages.size() + " error(s)",
                    messages);
        }
        try {
            return jsonMapper.treeToValue(tree, SemanticModelDTO.class);
        } catch (IOException e) {
            // Should be unreachable after schema passes — but keep defensive
            throw new SemanticYamlInvalidException(
                    "Schema passed but DTO deserialization failed: " + e.getMessage(),
                    List.of("dto-mapping: " + e.getMessage()), e);
        }
    }

    private String formatError(ValidationMessage m) {
        // Format: <path> :: <message>
        String path = m.getInstanceLocation() == null ? "/" : m.getInstanceLocation().toString();
        return path + " :: " + m.getMessage();
    }
}
