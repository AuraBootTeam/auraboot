package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.validator.PageSchemaDslI18nValidator;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;

/**
 * S-PAGE: Validates page schema definitions within the plugin (V2 flat format).
 * <p>
 * Checks:
 * - Required field: kind (on DTO directly)
 * - kind is a recognized value
 * - blocks list is present and non-empty
 * - Block types are recognized
 * - I18n compliance: no hardcoded non-ASCII text in user-facing fields (S-PAGE-I18N)
 */
@Slf4j
@Component
public class PageSchemaValidator implements PluginValidator {

    private static final Set<String> VALID_KINDS = DslRegistry.PageKind.codes();
    private static final Set<String> KNOWN_BLOCK_TYPES = DslRegistry.BlockType.codes();
    private static final Set<String> FORBIDDEN_LEGACY_TOP_LEVEL_FIELDS = Set.of("dslSchema", "pageType");

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
            String pageKey = page.getPageKey() != null && !page.getPageKey().isBlank()
                    ? page.getPageKey()
                    : "<unknown>";

            // Validate kind field (required, on DTO directly)
            String kind = page.getKind();
            if (kind == null || kind.isBlank()) {
                messages.add(error("S-PAGE-KIND", category(), path + ".kind",
                        "Page '" + pageKey + "' is missing required field 'kind'. " +
                                "Page JSON must use the V2 flat format with top-level kind/layout/blocks."));
            } else if (!VALID_KINDS.contains(kind)) {
                messages.add(error("S-PAGE-KIND-UNKNOWN", category(), path + ".kind",
                        "Page '" + pageKey + "' has unsupported kind '" + kind + "'. " +
                                "Supported kinds: " + VALID_KINDS + "."));
            }

            validateLegacyTopLevelFields(page, path, pageKey, messages);

            Map<String, Object> layout = page.getLayout();
            if (layout == null || layout.isEmpty()) {
                messages.add(error("S-PAGE-LAYOUT", category(), path + ".layout",
                        "Page '" + pageKey + "' is missing required top-level field 'layout'. " +
                                "Platform only accepts the latest V2 flat page format: kind/layout/blocks."));
            }

            // Validate blocks (on DTO directly)
            List<Object> blocks = page.getBlocks();
            if (blocks == null || blocks.isEmpty()) {
                messages.add(error("S-PAGE-BLOCKS", category(), path + ".blocks",
                        "Page '" + pageKey + "' is missing required top-level field 'blocks' " +
                                "or the array is empty. Platform only accepts the latest V2 flat page format."));
            } else {
                for (int j = 0; j < blocks.size(); j++) {
                    if (blocks.get(j) instanceof Map<?, ?> block) {
                        Object blockType = block.get("blockType");
                        if (blockType != null && !KNOWN_BLOCK_TYPES.contains(blockType.toString())) {
                            messages.add(error("S-PAGE-BLOCK-TYPE", category(),
                                    path + ".blocks[" + j + "].blockType",
                                    "Page '" + pageKey + "' has unknown blockType: '" +
                                            blockType + "'"));
                        }
                    }
                }
            }

            // I18n compliance: scan page title and all block text fields
            validateI18nCompliance(page, path, messages);
        }

        return messages;
    }

    private void validateLegacyTopLevelFields(PageSchemaDTO page, String path, String pageKey,
                                              List<PluginValidationMessage> messages) {
        Map<String, Object> unknownFields = page.getUnknownFields();
        if (unknownFields == null || unknownFields.isEmpty()) {
            return;
        }

        for (String legacyField : FORBIDDEN_LEGACY_TOP_LEVEL_FIELDS) {
            if (unknownFields.containsKey(legacyField)) {
                messages.add(error("S-PAGE-LEGACY-FORMAT", category(), path + "." + legacyField,
                        "Page '" + pageKey + "' uses deprecated top-level field '" + legacyField + "'. " +
                                "Platform only accepts the latest V2 flat page format with top-level " +
                                "kind/layout/blocks. Update the page JSON instead of relying on legacy DSL fields."));
            }
        }

        Set<String> unknownTopLevelFields = new LinkedHashSet<>(unknownFields.keySet());
        unknownTopLevelFields.removeAll(FORBIDDEN_LEGACY_TOP_LEVEL_FIELDS);
        if (!unknownTopLevelFields.isEmpty()) {
            messages.add(error("S-PAGE-UNKNOWN-FIELDS", category(), path,
                    "Page '" + pageKey + "' contains unsupported top-level fields: " + unknownTopLevelFields +
                            ". Platform only accepts the latest V2 flat page format with top-level " +
                            "kind/layout/blocks."));
        }
    }

    /**
     * Validate i18n compliance for user-facing text fields in the page and its blocks.
     * Any hardcoded non-ASCII string (e.g. Chinese) in title/label/placeholder etc. is
     * reported as an error with rule code S-PAGE-I18N.
     *
     * @param page     the page DTO to validate
     * @param basePath JSON path prefix for error messages
     * @param messages accumulator for validation messages
     */
    @SuppressWarnings("unchecked")
    private void validateI18nCompliance(PageSchemaDTO page, String basePath,
                                        List<PluginValidationMessage> messages) {
        String pageKey = page.getPageKey();

        // Check page-level title
        collectI18nViolations(basePath + ".title", page.getTitle(), pageKey, messages);

        // Check each block's text fields
        List<Object> blocks = page.getBlocks();
        if (blocks == null) return;

        for (int j = 0; j < blocks.size(); j++) {
            if (blocks.get(j) instanceof Map<?, ?> blockMap) {
                String blockPath = basePath + ".blocks[" + j + "]";
                scanBlockForI18n(blockPath, (Map<String, Object>) blockMap, pageKey, messages);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void scanBlockForI18n(String blockPath, Map<String, Object> blockMap,
                                  String pageKey, List<PluginValidationMessage> messages) {
        // Check all direct text fields on the block
        for (String field : PageSchemaDslI18nValidator.BLOCK_TEXT_FIELDS) {
            Object value = blockMap.get(field);
            if (value != null) {
                collectI18nViolations(blockPath + "." + field, value, pageKey, messages);
            }
        }

        // Recurse into sub-lists (columns, fields, actions, tabs, filters, …)
        for (String subList : PageSchemaDslI18nValidator.BLOCK_SUB_LISTS) {
            Object sub = blockMap.get(subList);
            if (sub instanceof List<?> items) {
                for (int k = 0; k < items.size(); k++) {
                    if (items.get(k) instanceof Map<?, ?> itemMap) {
                        scanBlockForI18n(blockPath + "." + subList + "[" + k + "]",
                                (Map<String, Object>) itemMap, pageKey, messages);
                    }
                }
            }
        }
    }

    private void collectI18nViolations(String path, Object value, String pageKey,
                                       List<PluginValidationMessage> messages) {
        List<PageSchemaDslI18nValidator.Violation> violations =
                PageSchemaDslI18nValidator.collectViolations(path, value);
        for (PageSchemaDslI18nValidator.Violation v : violations) {
            messages.add(error("S-PAGE-I18N", category(), v.path(),
                    "Page '" + pageKey + "': hardcoded non-ASCII text in DSL field '" +
                            v.path() + "'. Value: \"" + v.value() + "\". " +
                            "Use LocalizedText map or $i18n:key instead."));
        }
    }
}
