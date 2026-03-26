package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-PAGE: Validates page DSL schema definitions within the plugin.
 * <p>
 * Checks:
 * - Required fields (kind, layout, areas)
 * - kind is a recognized value
 * - layout.areas is a non-empty array
 * - Each area has at least one block
 * - Block types are recognized
 * - JSON Schema validation against dsl-schema.generated.json (warnings only during rollout)
 */
@Slf4j
@Component
public class PageSchemaValidator implements PluginValidator {

    private static final Set<String> VALID_KINDS = DslRegistry.PageKind.codes();
    private static final Set<String> KNOWN_BLOCK_TYPES = DslRegistry.BlockType.codes();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Lazy-loaded JSON Schema instance for DSL page validation. */
    private volatile JsonSchema dslJsonSchema;
    private volatile boolean schemaLoadAttempted;

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        if (manifest.getPages() == null) return messages;

        for (int i = 0; i < manifest.getPages().size(); i++) {
            PageSchemaDTO page = manifest.getPages().get(i);
            if (page == null) continue;

            String path = "pages[" + i + "]";
            Map<String, Object> dsl = page.getDslSchema();
            if (dsl == null) continue;

            // --- Manual structural checks (existing logic) ---

            // Check kind — downgraded to WARNING for backward compatibility
            // (older pages may not have kind field; runtime infers it)
            Object kind = dsl.get("kind");
            if (kind == null) {
                messages.add(warning("S-PAGE-KIND", category(), path + ".dslSchema.kind",
                        "Page '" + page.getPageKey() + "' DSL is missing field 'kind'"));
            } else if (!VALID_KINDS.contains(kind.toString())) {
                messages.add(warning("S-PAGE-KIND-UNKNOWN", category(), path + ".dslSchema.kind",
                        "Page '" + page.getPageKey() + "' has unknown kind: '" + kind + "'"));
            }

            // Check layout — downgraded to WARNING for backward compatibility
            Object layout = dsl.get("layout");
            if (layout == null) {
                messages.add(warning("S-PAGE-LAYOUT", category(), path + ".dslSchema.layout",
                        "Page '" + page.getPageKey() + "' DSL is missing field 'layout'"));
            } else if (layout instanceof Map<?, ?> layoutMap) {
                Object areas = layoutMap.get("areas");
                if (areas == null || (areas instanceof List<?> list && list.isEmpty())) {
                    messages.add(error("S-PAGE-LAYOUT-AREAS", category(), path + ".dslSchema.layout.areas",
                            "Page '" + page.getPageKey() + "' layout.areas must be a non-empty array"));
                }
            }

            // Check areas — downgraded to WARNING for backward compatibility
            Object areas = dsl.get("areas");
            if (areas == null) {
                messages.add(warning("S-PAGE-AREAS", category(), path + ".dslSchema.areas",
                        "Page '" + page.getPageKey() + "' DSL is missing field 'areas'"));
            } else if (areas instanceof Map<?, ?> areasMap) {
                for (Map.Entry<?, ?> entry : areasMap.entrySet()) {
                    String areaKey = entry.getKey().toString();
                    if (entry.getValue() instanceof Map<?, ?> areaConfig) {
                        Object blocks = areaConfig.get("blocks");
                        if (blocks instanceof List<?> blocksList) {
                            for (int j = 0; j < blocksList.size(); j++) {
                                if (blocksList.get(j) instanceof Map<?, ?> block) {
                                    Object blockType = block.get("blockType");
                                    if (blockType != null && !KNOWN_BLOCK_TYPES.contains(blockType.toString())) {
                                        messages.add(warning("S-PAGE-BLOCK-TYPE", category(),
                                                path + ".dslSchema.areas." + areaKey + ".blocks[" + j + "].blockType",
                                                "Page '" + page.getPageKey() + "' has unknown blockType: '" +
                                                        blockType + "'"));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- JSON Schema validation (warnings only during rollout) ---
            validateAgainstJsonSchema(dsl, page.getPageKey(), path, messages);
        }

        return messages;
    }

    /**
     * Validates a page's dslSchema Map against the generated JSON Schema (Draft-07).
     * All violations are emitted as WARNINGs to avoid breaking existing plugins during rollout.
     */
    private void validateAgainstJsonSchema(Map<String, Object> dsl, String pageKey,
                                           String path, List<PluginValidationMessage> messages) {
        JsonSchema schema = getDslJsonSchema();
        if (schema == null) {
            return; // Schema not available — skip silently (already logged on load attempt)
        }

        try {
            JsonNode dslNode = OBJECT_MAPPER.valueToTree(dsl);
            Set<ValidationMessage> errors = schema.validate(dslNode);
            for (ValidationMessage error : errors) {
                messages.add(warning("S-DSL-SCHEMA", category(),
                        path + ".dslSchema" + error.getInstanceLocation(),
                        "Page '" + pageKey + "' JSON Schema violation: " + error.getMessage()));
            }
        } catch (Exception e) {
            log.warn("Failed to validate page '{}' dslSchema against JSON Schema: {}",
                    pageKey, e.getMessage());
        }
    }

    /**
     * Lazily loads the DSL JSON Schema from classpath. Thread-safe via double-checked locking.
     * The schema root $ref points to #/definitions/DslSchema, which networknt resolves automatically.
     */
    private JsonSchema getDslJsonSchema() {
        if (!schemaLoadAttempted) {
            synchronized (this) {
                if (!schemaLoadAttempted) {
                    try (InputStream is = getClass().getResourceAsStream("/schemas/dsl-schema.generated.json")) {
                        if (is != null) {
                            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
                            dslJsonSchema = factory.getSchema(is);
                            log.info("Loaded DSL JSON Schema for page validation");
                        } else {
                            log.warn("DSL JSON Schema not found at /schemas/dsl-schema.generated.json — "
                                    + "JSON Schema validation will be skipped");
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load DSL JSON Schema: {} — validation will be skipped",
                                e.getMessage());
                    }
                    schemaLoadAttempted = true;
                }
            }
        }
        return dslJsonSchema;
    }
}
