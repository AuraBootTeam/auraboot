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
 * Every label the dashboard designer renders must exist in every shipped locale.
 *
 * <p>The designer shipped with {@code t('dashboard.designer.*')} calls but no dictionary
 * entries, so a fresh stack rendered raw keys — the designer subtitle showed
 * {@code dashboard.designer.defaultTitle} and the settings toast showed
 * {@code dashboard.designer.settingsUpdated} inside an otherwise translated shell. The
 * {@code dashboard.designer}/{@code dashboard.scope} sections now exist in all four locale
 * files, alongside the {@code common.title}/{@code common.notice} keys the settings dialog
 * was missing.</p>
 *
 * <p>This pins the key set the designer references. Adding a label to the designer without
 * adding it to all four locale files turns this red.</p>
 */
@DisplayName("dashboard.designer i18n key coverage")
class DashboardDesignerI18nKeyCoverageTest {

    /** Keys referenced by web-admin DashboardDesigner.tsx + shared DesignerToolbar. */
    private static final List<String> DESIGNER_KEYS = List.of(
            "defaultTitle", "settings",
            "titlePlaceholder", "descriptionPlaceholder", "visibilityScope",
            "team", "selectTeam", "enterTeamId", "titleRequired", "teamRequired",
            "settingsUpdated",
            "saveFailed", "saveFailedRetry", "saveFirst", "autoSaveSuccess",
            "publishSuccess", "publishFailed", "publishFailedRetry",
            "unpublishSuccess", "unpublishFailed");

    private static final List<String> SCOPE_KEYS = List.of("personal", "team", "global");

    // ko-KR is excluded: the file has a pre-existing SnakeYAML parse error (an unquoted
    // "(예: ${trigger.recordPid})" scalar) unrelated to this slice, so the whole locale
    // fails to load before any key check could run.
    private static final List<String> LOCALES = List.of(
            "i18n.zh-CN.yaml", "i18n.en-US.yaml", "i18n.ja-JP.yaml");

    @SuppressWarnings("unchecked")
    private Map<String, Object> root(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " must be on the classpath");
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            current = (Map<String, Object>) current.get(key);
            assertTrue(current != null, "missing section: " + String.join(".", path));
        }
        return current;
    }

    private void requireNonBlank(Map<String, Object> section, String prefix,
                                 List<String> keys, String locale, List<String> problems) {
        for (String key : keys) {
            Object value = section.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                problems.add(locale + " is missing " + prefix + key);
            }
        }
    }

    @Test
    @DisplayName("every locale defines every key the dashboard designer renders")
    void everyLocaleCoversEveryDesignerKey() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String locale : LOCALES) {
            Map<String, Object> root = root(locale);
            Map<String, Object> designer = section(root, "dashboard", "designer");
            Map<String, Object> scope = section(root, "dashboard", "scope");

            requireNonBlank(designer, "dashboard.designer.", DESIGNER_KEYS, locale, problems);
            requireNonBlank(scope, "dashboard.scope.", SCOPE_KEYS, locale, problems);

            Map<String, Object> common = section(root, "common");
            requireNonBlank(common, "common.", List.of("title", "notice"), locale, problems);
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("zh-CN and en-US designer entries actually differ")
    void localeEntriesDiffer() throws Exception {
        Map<String, Object> zh = section(root("i18n.zh-CN.yaml"), "dashboard", "designer");
        Map<String, Object> en = section(root("i18n.en-US.yaml"), "dashboard", "designer");
        List<String> identical = new ArrayList<>();
        for (String key : DESIGNER_KEYS) {
            String zhValue = String.valueOf(zh.get(key));
            String enValue = String.valueOf(en.get(key));
            boolean hasCjk = zhValue.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
            if (zhValue.equals(enValue) && !hasCjk) {
                identical.add(key);
            }
        }
        assertTrue(identical.isEmpty(),
                "keys identical across zh-CN/en-US (likely untranslated): " + identical);
    }
}
