package com.auraboot.framework.meta.schema;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SystemTabRegistryTest {

    @Test
    void documentModel_shouldReturn4SystemTabs() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("document");
        assertThat(tabs).hasSize(4);
        assertThat(tabs.get(0).get("key")).isEqualTo("__comments__");
        assertThat(tabs.get(1).get("key")).isEqualTo("__activity__");
        assertThat(tabs.get(2).get("key")).isEqualTo("__approval_comments__");
        assertThat(tabs.get(3).get("key")).isEqualTo("__field_history__");
        tabs.forEach(t -> assertThat(t.get("system")).isEqualTo(true));
    }

    @Test
    void masterModel_shouldReturn3SystemTabs() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("master");
        assertThat(tabs).hasSize(3);
        assertThat(tabs.get(0).get("key")).isEqualTo("__comments__");
        assertThat(tabs.get(1).get("key")).isEqualTo("__activity__");
        assertThat(tabs.get(2).get("key")).isEqualTo("__field_history__");
    }

    @Test
    void entityModel_shouldReturnOnlyFieldHistory() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("entity");
        assertThat(tabs).hasSize(1);
        assertThat(tabs.get(0).get("key")).isEqualTo("__field_history__");
    }

    @Test
    void transactionModel_shouldReturnOnlyFieldHistory() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("transaction");
        assertThat(tabs).hasSize(1);
        assertThat(tabs.get(0).get("key")).isEqualTo("__field_history__");
    }

    @Test
    void referenceModel_shouldReturnOnlyFieldHistory() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("reference");
        assertThat(tabs).hasSize(1);
        assertThat(tabs.get(0).get("key")).isEqualTo("__field_history__");
    }

    @Test
    void activityModel_shouldReturnOnlyFieldHistory() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("activity");
        assertThat(tabs).hasSize(1);
        assertThat(tabs.get(0).get("key")).isEqualTo("__field_history__");
    }

    @Test
    void nullCategory_shouldReturnOnlyFieldHistory() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs(null);
        assertThat(tabs).hasSize(1);
        assertThat(tabs.get(0).get("key")).isEqualTo("__field_history__");
    }

    @Test
    void emptyCategory_shouldReturnOnlyFieldHistory() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("");
        assertThat(tabs).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tabStructure_shouldHaveCorrectLabelAndBlocks() {
        List<Map<String, Object>> tabs = SystemTabRegistry.getSystemTabs("document");
        // tabs[0] = __comments__, tabs[1] = __activity__
        Map<String, Object> activityTab = tabs.get(1);

        Map<String, String> label = (Map<String, String>) activityTab.get("label");
        assertThat(label.get("zh-CN")).isEqualTo("活动记录");
        assertThat(label.get("en-US")).isEqualTo("Activity");

        List<Map<String, Object>> blocks = (List<Map<String, Object>>) activityTab.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("blockType")).isEqualTo("activity-timeline");
    }
}
