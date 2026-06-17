package com.auraboot.framework.meta.validator;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.constant.DslRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural integrity guard for page-schema blocks on the online save path
 * ({@code POST/PUT /api/pages} → {@code PageSchemaServiceImpl.create/update}).
 *
 * <p>The online save path previously validated only i18n compliance and the
 * serialized size of {@code blocks}; it did not check that each block carries a
 * stable {@code id} and a {@code blockType}, nor that block ids are unique. A
 * malformed page (duplicate / missing ids) renders into a broken designer
 * canvas. This validator ports the equivalent structural checks from the
 * front-end
 * {@code web-admin/.../unified-designer/validation/validatePageSchemaV3.ts}
 * ({@code missing_block_id} / {@code missing_block_type} /
 * {@code duplicate_block_id}) into the backend so the server is the source of
 * truth.</p>
 *
 * <p><b>Hard reject (throws {@link ValidationException}):</b></p>
 * <ul>
 *   <li>any block with a null / blank {@code id}</li>
 *   <li>any block with a null / blank {@code blockType}</li>
 *   <li>any duplicate block {@code id} across the whole tree (global scope)</li>
 * </ul>
 *
 * <p><b>Soft warning (logs, never rejects):</b> a {@code blockType} that is not
 * in the union whitelist. This is deliberate forward-compatibility — custom
 * plugin blocks register their own {@code blockType} via the front-end
 * {@code BlockRegistry}, and future block types must not be hard-rejected by an
 * older backend. Hard-rejecting unknown types would break those custom blocks,
 * so unknown types pass with a warning only.</p>
 *
 * <p>Children are nested under the {@code blocks} key (mirrors the front-end
 * {@code DslBlockV3.blocks?: DslBlockV3[]}); validation recurses through it.</p>
 */
public final class PageSchemaBlockStructureValidator {

    private static final Logger log = LoggerFactory.getLogger(PageSchemaBlockStructureValidator.class);

    /**
     * Front-end v4 designer structural blockTypes that have no backend
     * {@link DslRegistry.BlockType} enum counterpart. Source of truth:
     * {@code web-admin/app/plugins/core-designer/components/unified-designer/registry/BlockRegistry.ts}
     * (v4 structural / layout block kinds). These describe the canvas tree
     * structure (rows, columns, fields, actions, tabs) rather than runtime
     * render widgets, so they live in the front-end registry, not the backend
     * {@code DslRegistry}. Kept here as an explicit set so the union whitelist
     * accepts a clean v4 page without a soft warning.
     */
    static final Set<String> FRONTEND_V4_STRUCTURAL_BLOCK_TYPES = Set.of(
            "field", "list", "filter-bar", "filter-field", "column",
            "action-bar", "action", "detail", "dashboard", "widget",
            "columns", "tab", "repeater", "subform"
    );

    /**
     * Union of the backend {@link DslRegistry.BlockType} codes (30) and the
     * front-end v4 structural block types (14). A {@code blockType} outside this
     * set triggers a soft warning only — never a hard reject.
     */
    private static final Set<String> KNOWN_BLOCK_TYPES = buildKnownBlockTypes();

    private static Set<String> buildKnownBlockTypes() {
        Set<String> union = new LinkedHashSet<>(DslRegistry.BlockType.codes());
        union.addAll(FRONTEND_V4_STRUCTURAL_BLOCK_TYPES);
        return Set.copyOf(union);
    }

    private PageSchemaBlockStructureValidator() {
        // utility class
    }

    /**
     * Returns the full union whitelist of known block types (backend enum ∪
     * front-end v4 structural blocks). Exposed for tests / introspection.
     */
    public static Set<String> knownBlockTypes() {
        return KNOWN_BLOCK_TYPES;
    }

    /**
     * Validate the structural integrity of a page's block tree.
     *
     * <p>Null or empty {@code blocks} is allowed (pages are built incrementally
     * in the designer; publishable-state checks happen elsewhere). Non-Map block
     * entries are tolerated and skipped (defensive — the i18n validator and size
     * validator already run before this point).</p>
     *
     * @param blocks  the raw blocks list (typically {@code List<Object>} where
     *                each element is a {@code Map<String,Object>})
     * @param pageKey page key, used only to build a readable error message
     * @throws ValidationException on the first hard-reject condition
     */
    public static void validate(List<?> blocks, String pageKey) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        Set<String> seenIds = new HashSet<>();
        List<String> errors = new ArrayList<>();
        String basePath = "pages[" + safeKey(pageKey) + "].blocks";
        walk(blocks, basePath, seenIds, errors);

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "Page block structure validation failed. ");
            sb.append("Each block must have a non-blank id and blockType, and block ids must be unique. Violations:\n");
            for (String e : errors) {
                sb.append("  ").append(e).append("\n");
            }
            throw new ValidationException(ResponseCode.CommonValidationFailed, sb.toString().trim());
        }
    }

    private static void walk(List<?> blocks, String path, Set<String> seenIds, List<String> errors) {
        for (int i = 0; i < blocks.size(); i++) {
            Object raw = blocks.get(i);
            String blockPath = path + "[" + i + "]";
            if (!(raw instanceof Map<?, ?> blockMap)) {
                // Not a structured block (defensive); skip rather than reject —
                // size / i18n validators run first and would have surfaced gross
                // malformations.
                continue;
            }

            String id = asText(blockMap.get("id"));
            String blockType = asText(blockMap.get("blockType"));

            // ── Hard reject: missing id ──
            if (isBlank(id)) {
                errors.add("path=" + blockPath + ".id, error=missing block id");
            } else if (!seenIds.add(id)) {
                // ── Hard reject: duplicate id (global scope across the tree) ──
                errors.add("path=" + blockPath + ".id, error=duplicate block id \"" + id + "\"");
            }

            // ── Hard reject: missing blockType ──
            if (isBlank(blockType)) {
                errors.add("path=" + blockPath + ".blockType, error=missing blockType");
            } else if (!KNOWN_BLOCK_TYPES.contains(blockType)) {
                // ── Soft warning only: forward-compatibility for custom / future block types ──
                log.warn("Unknown page blockType (allowed for forward-compat, custom blocks register their own type): "
                        + "path={}.blockType, blockType=\"{}\"", blockPath, blockType);
            }

            // Recurse into nested children (key = "blocks", mirrors DslBlockV3.blocks).
            Object children = blockMap.get("blocks");
            if (children instanceof List<?> childList) {
                walk(childList, blockPath + ".blocks", seenIds, errors);
            }
        }
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safeKey(String pageKey) {
        return pageKey == null ? "(unknown)" : pageKey;
    }
}
