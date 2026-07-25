package com.auraboot.framework.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every label the inbox page asks for must exist in every shipped locale.
 *
 * <p>The page used to hardcode English while the shell around it rendered Chinese, so a
 * zh-CN user read "Inbox / Refresh / Mark all read / Pending" inside an otherwise Chinese
 * app. It now resolves through {@code workbench.inbox.*}. The failure mode that replaces
 * the old one is subtler: add a tab or a column, forget the key, and the page silently
 * falls back to English for that one label — the same mixed-language result, just smaller
 * and harder to notice.</p>
 *
 * <p>This pins the key set the component references. Adding a label to the page without
 * adding it to both locale files turns this red.</p>
 */
@DisplayName("workbench.inbox i18n key coverage")
class InboxI18nKeyCoverageTest {

    /** Keys referenced by web-admin/app/routes/inbox/index.tsx. */
    private static final List<String> REQUIRED_KEYS = List.of(
            // header + toolbar
            "title", "itemsNeedAttention", "refresh", "markAllRead",
            // type tabs
            "all", "approval", "task", "mention", "alert", "assignment",
            // status filters
            "statusPending", "statusAll", "statusActed", "statusClosed",
            // queue copy (title + description per tab)
            "copyAllTitle", "copyAllDesc",
            "copyApprovalTitle", "copyApprovalDesc",
            "copyAlertTitle", "copyAlertDesc",
            "copyAssignmentTitle", "copyAssignmentDesc",
            "copyTaskTitle", "copyTaskDesc",
            "copyMentionTitle", "copyMentionDesc",
            // metrics
            "unread", "urgent", "approvals",
            // table columns
            "colTitle", "colType", "colStatus", "colTime", "colSource", "colAction",
            // row actions + relative time + error state
            "open", "view", "review",
            "justNow", "minutesAgo", "hoursAgo", "daysAgo",
            // value labels rendered in table cells
            "priorityUrgent", "priorityHigh", "priorityMedium", "priorityNormal", "priorityLow",
            "stateActed", "stateDismissed", "stateClosed", "stateExpired",
            "loadFailed");

    private static final List<String> LOCALES = List.of("i18n.zh-CN.yaml", "i18n.en-US.yaml");

    @SuppressWarnings("unchecked")
    private Map<String, Object> inboxSection(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " must be on the classpath");
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> workbench = (Map<String, Object>) root.get("workbench");
            assertTrue(workbench != null, resource + " must define a workbench section");
            Map<String, Object> inbox = (Map<String, Object>) workbench.get("inbox");
            assertTrue(inbox != null, resource + " must define workbench.inbox");
            return inbox;
        }
    }

    @Test
    @DisplayName("every locale defines every key the inbox page renders")
    void everyLocaleCoversEveryInboxKey() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String locale : LOCALES) {
            Map<String, Object> inbox = inboxSection(locale);
            for (String key : REQUIRED_KEYS) {
                Object value = inbox.get(key);
                if (value == null || String.valueOf(value).isBlank()) {
                    problems.add(locale + " is missing workbench.inbox." + key);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("zh-CN actually translates — a Chinese locale full of English is the bug this guards")
    void zhLocaleIsNotJustEnglishEchoed() throws Exception {
        Map<String, Object> zh = inboxSection("i18n.zh-CN.yaml");
        Map<String, Object> en = inboxSection("i18n.en-US.yaml");

        List<String> untranslated = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            String zhValue = String.valueOf(zh.get(key));
            String enValue = String.valueOf(en.get(key));
            // Placeholder-only strings ("{count}") legitimately match across locales;
            // everything else being identical means zh-CN never got a translation.
            boolean hasCjk = zhValue.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
            if (zhValue.equals(enValue) && !hasCjk) {
                untranslated.add("workbench.inbox." + key + " is identical in zh-CN and en-US: " + zhValue);
            }
        }
        assertTrue(untranslated.isEmpty(), String.join("\n", untranslated));
    }
}
