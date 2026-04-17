package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.validator.PageSchemaDslI18nValidator;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

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

            // Validate kind field (required, on DTO directly)
            String kind = page.getKind();
            if (kind == null || kind.isBlank()) {
                messages.add(error("S-PAGE-KIND", category(), path + ".kind",
                        "Page '" + page.getPageKey() + "' is missing required field 'kind'"));
            } else if (!VALID_KINDS.contains(kind)) {
                messages.add(warning("S-PAGE-KIND-UNKNOWN", category(), path + ".kind",
                        "Page '" + page.getPageKey() + "' has unknown kind: '" + kind + "'"));
            }

            // Validate blocks (on DTO directly)
            List<Object> blocks = page.getBlocks();
            if (blocks == null || blocks.isEmpty()) {
                messages.add(warning("S-PAGE-BLOCKS", category(), path + ".blocks",
                        "Page '" + page.getPageKey() + "' has no blocks defined"));
            } else {
                for (int j = 0; j < blocks.size(); j++) {
                    if (blocks.get(j) instanceof Map<?, ?> block) {
                        Object blockType = block.get("blockType");
                        if (blockType != null && !KNOWN_BLOCK_TYPES.contains(blockType.toString())) {
                            messages.add(warning("S-PAGE-BLOCK-TYPE", category(),
                                    path + ".blocks[" + j + "].blockType",
                                    "Page '" + page.getPageKey() + "' has unknown blockType: '" +
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
