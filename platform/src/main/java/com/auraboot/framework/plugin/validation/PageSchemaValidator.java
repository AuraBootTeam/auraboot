package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.constant.DslRegistry;
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
        }

        return messages;
    }
}
