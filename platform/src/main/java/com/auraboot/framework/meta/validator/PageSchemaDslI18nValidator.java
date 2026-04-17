package com.auraboot.framework.meta.validator;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates i18n compliance for DSL page schema user-facing text fields.
 *
 * <p>Rules for any user-facing text field value:</p>
 * <ul>
 *   <li>{@code null} or blank string → skipped (not required to exist)</li>
 *   <li>Map (LocalizedText object, e.g. {@code {"zh-CN": "...", "en-US": "..."}}) → PASS</li>
 *   <li>String starting with {@code $i18n:} → PASS</li>
 *   <li>Pure ASCII string (only chars in range 0x00–0x7F) → PASS (common English labels)</li>
 *   <li>Non-ASCII string (contains Chinese or any other non-Latin script) → FAIL</li>
 * </ul>
 *
 * <p>User-facing text fields scanned at page level:
 * {@code title}, {@code description}</p>
 *
 * <p>User-facing text fields scanned inside each block:
 * {@code title}, {@code label}, {@code placeholder}, {@code description},
 * {@code emptyText}, {@code tooltip}, {@code buttonText}, {@code helpText},
 * {@code headerTitle}, {@code confirmMessage}, {@code cancelText}, {@code okText}</p>
 *
 * <p>The validator also recurses into nested {@code columns}, {@code fields},
 * {@code actions}, {@code tabs}, and {@code filters} sub-lists within a block.</p>
 */
public final class PageSchemaDslI18nValidator {

    /** Top-level page fields that carry user-visible text. */
    private static final Set<String> PAGE_TEXT_FIELDS = Set.of(
            "title", "description"
    );

    /**
     * Block-level (and sub-item-level) fields that carry user-visible text.
     * Covers table columns, form fields, toolbar actions, filter widgets, etc.
     */
    public static final Set<String> BLOCK_TEXT_FIELDS = Set.of(
            "title", "label", "placeholder", "description",
            "emptyText", "tooltip", "buttonText", "helpText",
            "headerTitle", "confirmMessage", "cancelText", "okText"
    );

    /** Sub-collections inside a block that may contain further text fields. */
    private static final Set<String> BLOCK_SUB_LISTS = Set.of(
            "columns", "fields", "actions", "tabs", "filters",
            "items", "children", "buttons"
    );

    private PageSchemaDslI18nValidator() {
        // utility class
    }

    // ==================== Public API ====================

    /**
     * Validate that page-level text fields contain no hardcoded non-ASCII text.
     *
     * @param title       page title value (may be String or Map&lt;?,?&gt;)
     * @param description page description value (may be String)
     * @param pageKey     page key for error messages
     * @throws ValidationException if any violation is found
     */
    public static void validatePageFields(Object title, Object description, String pageKey) {
        List<Violation> violations = new ArrayList<>();
        checkField("pages[" + pageKey + "].title", title, violations);
        checkField("pages[" + pageKey + "].description", description, violations);
        if (!violations.isEmpty()) {
            throw buildException(violations);
        }
    }

    /**
     * Validate i18n compliance for all text fields in the given page schema map.
     * Scans page-level fields and recurses into every block.
     *
     * <p>Returns a list of {@link Violation} objects so callers can choose to
     * throw or collect. Throws {@link ValidationException} if there are errors.</p>
     *
     * @param pageMap  raw page schema as a Map (title, blocks, …)
     * @param pageKey  page key used to build error paths
     * @throws ValidationException if any non-ASCII hardcoded text is found
     */
    @SuppressWarnings("unchecked")
    public static void validatePageSchema(Map<String, Object> pageMap, String pageKey) {
        List<Violation> violations = new ArrayList<>();
        String basePath = "pages[" + pageKey + "]";

        // Scan page-level text fields
        for (String field : PAGE_TEXT_FIELDS) {
            Object value = pageMap.get(field);
            if (value != null) {
                checkField(basePath + "." + field, value, violations);
            }
        }

        // Scan each block
        Object blocksObj = pageMap.get("blocks");
        if (blocksObj instanceof List<?> blocks) {
            for (int i = 0; i < blocks.size(); i++) {
                Object block = blocks.get(i);
                if (block instanceof Map<?, ?> blockMap) {
                    String blockPath = basePath + ".blocks[" + i + "]";
                    scanBlock(blockPath, (Map<String, Object>) blockMap, violations);
                }
            }
        }

        if (!violations.isEmpty()) {
            throw buildException(violations);
        }
    }

    /**
     * Collect violations from a single text value without throwing.
     * Useful for unit-testing individual rules.
     *
     * @param path   JSON-path-like location string
     * @param value  the field value to check
     * @return list of violations (empty if compliant)
     */
    public static List<Violation> collectViolations(String path, Object value) {
        List<Violation> violations = new ArrayList<>();
        checkField(path, value, violations);
        return violations;
    }

    // ==================== Internal scanning ====================

    @SuppressWarnings("unchecked")
    private static void scanBlock(String blockPath, Map<String, Object> blockMap,
                                  List<Violation> violations) {
        // Check direct text fields on the block
        for (String field : BLOCK_TEXT_FIELDS) {
            Object value = blockMap.get(field);
            if (value != null) {
                checkField(blockPath + "." + field, value, violations);
            }
        }

        // Recurse into sub-lists
        for (String subList : BLOCK_SUB_LISTS) {
            Object sub = blockMap.get(subList);
            if (sub instanceof List<?> items) {
                for (int i = 0; i < items.size(); i++) {
                    Object item = items.get(i);
                    if (item instanceof Map<?, ?> itemMap) {
                        String itemPath = blockPath + "." + subList + "[" + i + "]";
                        scanBlock(itemPath, (Map<String, Object>) itemMap, violations);
                    }
                }
            }
        }
    }

    // ==================== Rule engine ====================

    /**
     * Check a single field value and append a violation if it contains non-ASCII text.
     *
     * @param path       JSON path for error reporting
     * @param value      the field value (String, Map, or other)
     * @param violations accumulator
     */
    static void checkField(String path, Object value, List<Violation> violations) {
        if (value == null) {
            return; // null is fine — field is optional
        }
        if (value instanceof Map<?, ?>) {
            // LocalizedText object — always compliant
            return;
        }
        if (value instanceof String str) {
            if (str.isBlank()) {
                return; // blank string — skip
            }
            if (str.startsWith("$i18n:")) {
                return; // i18n reference key — compliant
            }
            if (isPureAscii(str)) {
                return; // pure ASCII — allowed
            }
            // Non-ASCII (Chinese, Japanese, etc.) hardcoded in DSL — violation
            violations.add(new Violation(path, str));
        }
        // Other types (Boolean, Number, etc.) are not text — skip
    }

    /**
     * Returns {@code true} if every character in the string is in the ASCII range (0–127).
     */
    static boolean isPureAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static ValidationException buildException(List<Violation> violations) {
        StringBuilder sb = new StringBuilder(
                "DSL i18n compliance violation: hardcoded non-ASCII text found in page schema. ");
        sb.append("Use LocalizedText object or $i18n:key instead. Violations:\n");
        for (Violation v : violations) {
            sb.append("  path=").append(v.path())
              .append(", value=\"").append(v.value()).append("\"\n");
        }
        return new ValidationException(ResponseCode.CommonValidationFailed, sb.toString().trim());
    }

    // ==================== Value type ====================

    /**
     * Represents a single i18n compliance violation.
     *
     * @param path  JSON-path location of the violating field
     * @param value the non-ASCII string value that was found
     */
    public record Violation(String path, String value) {}
}
