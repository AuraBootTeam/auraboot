package com.auraboot.framework.meta.schema;

import java.util.*;

/**
 * Registry of system-injected tabs for detail pages.
 * Maps modelCategory -> list of system tab definitions to inject at query time.
 */
public class SystemTabRegistry {

    private static final Map<String, Object> ACTIVITY_TAB = Map.of(
            "key", "__activity__",
            "system", true,
            "label", Map.of("zh-CN", "活动记录", "en-US", "Activity"),
            "blocks", List.of(Map.of("blockType", "activity-timeline", "id", "__activity_timeline_block__"))
    );

    private static final Map<String, Object> COMMENT_TAB = Map.of(
            "key", "__comments__",
            "system", true,
            "label", Map.of("zh-CN", "评论", "en-US", "Comments"),
            "blocks", List.of(Map.of("blockType", "record-comments", "id", "__record_comments_block__"))
    );

    private static final Map<String, Object> FIELD_HISTORY_TAB = Map.of(
            "key", "__field_history__",
            "system", true,
            "label", Map.of("zh-CN", "变更历史", "en-US", "Field History"),
            "blocks", List.of(Map.of("blockType", "field-history", "id", "__field_history_block__"))
    );

    private static final Map<String, List<Map<String, Object>>> RULES = Map.of(
            "document", List.of(COMMENT_TAB, ACTIVITY_TAB, FIELD_HISTORY_TAB),
            "master", List.of(COMMENT_TAB, ACTIVITY_TAB, FIELD_HISTORY_TAB),
            "transaction", List.of(FIELD_HISTORY_TAB),
            "entity", List.of(FIELD_HISTORY_TAB),
            "reference", List.of(FIELD_HISTORY_TAB),
            "activity", List.of(FIELD_HISTORY_TAB)
    );

    private static final List<Map<String, Object>> DEFAULT_TABS = List.of(FIELD_HISTORY_TAB);

    /**
     * Get system tabs for a given model category.
     * Returns immutable list of tab definitions (each is a Map matching DSL tab JSON structure).
     */
    public static List<Map<String, Object>> getSystemTabs(String modelCategory) {
        if (modelCategory == null || modelCategory.isBlank()) {
            return DEFAULT_TABS;
        }
        return RULES.getOrDefault(modelCategory, DEFAULT_TABS);
    }

    private SystemTabRegistry() {}
}
