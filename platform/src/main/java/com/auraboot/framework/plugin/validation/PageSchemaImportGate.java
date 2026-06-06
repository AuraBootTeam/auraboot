package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DSL V4 Phase B import gate: hard-fails a plugin import when any declared page
 * violates the v4 structural import contract.
 * <p>
 * Three layers (DSL V4 spec §6):
 * <ol>
 *   <li>① version + ② v4 format — {@link PageSchemaValidator} blocking findings
 *       (schemaVersion==4, importable kind, layout.type, blockType, block grid col,
 *       block id, no legacy top-level fields).</li>
 *   <li>③ JSON Schema — declarative networknt validation of the page envelope
 *       ({@code schemas/page-import-v4.schema.json}).</li>
 * </ol>
 * Only structural/blocking findings abort the import; advisory page findings
 * (label / i18n / field-ref / table-dict / form-required ...) continue to flow to
 * the post-import quality score and never block. This keeps currently-importing
 * plugins with advisory issues importable while enforcing honest v4 structure.
 */
@Slf4j
@Component
public class PageSchemaImportGate {

    /** Page-schema rule codes that abort the import (vs. advisory quality-score-only codes). */
    private static final Set<String> BLOCKING_CODES = Set.of(
            "S-PAGE-VERSION",
            "S-PAGE-KIND",
            "S-PAGE-KIND-UNKNOWN",
            "S-PAGE-LAYOUT",
            "S-PAGE-LAYOUT-TYPE",
            "S-PAGE-BLOCKS",
            "S-PAGE-BLOCK-TYPE",
            "S-PAGE-BLOCK-ID",
            "S-PAGE-BLOCK-COL",
            "S-PAGE-LEGACY-FORMAT",
            "S-PAGE-JSON-SCHEMA");

    private static final String SCHEMA_RESOURCE = "/schemas/page-import-v4.schema.json";

    private final PageSchemaValidator pageSchemaValidator;
    private final ObjectMapper objectMapper;
    private volatile JsonSchema envelopeSchema;

    public PageSchemaImportGate(PageSchemaValidator pageSchemaValidator, ObjectMapper objectMapper) {
        this.pageSchemaValidator = pageSchemaValidator;
        this.objectMapper = objectMapper;
    }

    /**
     * Validate all declared pages against the v4 import contract. Throws
     * {@link PageSchemaImportException} (aborting the import before persistence) if
     * any blocking finding is present.
     */
    public void enforce(PluginManifestExtended manifest) {
        if (manifest == null || manifest.getPages() == null || manifest.getPages().isEmpty()) {
            return;
        }

        List<PluginValidationMessage> blocking = new ArrayList<>();

        // ① + ② — structural validator, filtered to blocking codes only.
        PluginValidationContext ctx = PluginValidationContext.builder().manifest(manifest).build();
        for (PluginValidationMessage m : pageSchemaValidator.validate(ctx)) {
            if (m.isError() && BLOCKING_CODES.contains(m.getCode())) {
                blocking.add(m);
            }
        }

        // ③ — declarative JSON Schema envelope validation.
        blocking.addAll(jsonSchemaFindings(manifest));

        if (!blocking.isEmpty()) {
            String detail = blocking.stream()
                    .map(m -> m.getCode() + " @ " + m.getPath() + ": " + m.getMessage())
                    .collect(Collectors.joining("; "));
            throw new PageSchemaImportException(
                    "Plugin page schema failed v4 import validation ("
                            + blocking.size() + " blocking issue(s)): " + detail,
                    blocking);
        }
    }

    private List<PluginValidationMessage> jsonSchemaFindings(PluginManifestExtended manifest) {
        List<PluginValidationMessage> out = new ArrayList<>();
        JsonSchema schema = envelopeSchema();
        List<PageSchemaDTO> pages = manifest.getPages();
        for (int i = 0; i < pages.size(); i++) {
            PageSchemaDTO page = pages.get(i);
            if (page == null) {
                continue;
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("kind", page.getKind());
            envelope.put("schemaVersion", page.getSchemaVersion());
            envelope.put("layout", page.getLayout());
            envelope.put("blocks", page.getBlocks());
            JsonNode node = objectMapper.valueToTree(envelope);
            Set<ValidationMessage> errors = schema.validate(node);
            String pageKey = page.getPageKey() != null && !page.getPageKey().isBlank()
                    ? page.getPageKey() : "<unknown>";
            for (ValidationMessage e : errors) {
                String loc = e.getInstanceLocation() != null ? e.getInstanceLocation().toString() : "";
                out.add(PluginValidationMessage.error("S-PAGE-JSON-SCHEMA", "semantic",
                        "pages[" + i + "]" + loc,
                        "Page '" + pageKey + "' violates v4 import schema: " + e.getMessage()));
            }
        }
        return out;
    }

    private JsonSchema envelopeSchema() {
        JsonSchema s = envelopeSchema;
        if (s == null) {
            synchronized (this) {
                if (envelopeSchema == null) {
                    envelopeSchema = JsonSchemaFactory
                            .getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(loadSchemaJson());
                }
                s = envelopeSchema;
            }
        }
        return s;
    }

    private String loadSchemaJson() {
        try (InputStream in = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + SCHEMA_RESOURCE + ": " + e.getMessage(), e);
        }
    }
}
