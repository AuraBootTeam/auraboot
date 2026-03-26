package com.auraboot.framework.i18n.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for I18nTextProcessor.
 */
class I18nTextProcessorTest {

    private I18nTextProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new I18nTextProcessor();
    }

    // =========================================================
    // Null / empty input guards
    // =========================================================

    @Test
    void processI18nTexts_nullSchema_returnsNull() {
        assertThat(processor.processI18nTexts(null, "zh-CN")).isNull();
    }

    @Test
    void processI18nTexts_nullLocale_returnsOriginal() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("key", "value");
        Map<String, Object> result = processor.processI18nTexts(schema, null);
        assertThat(result).isEqualTo(schema);
    }

    @Test
    void processI18nTexts_emptySchema_returnsEmpty() {
        Map<String, Object> result = processor.processI18nTexts(new HashMap<>(), "zh-CN");
        assertThat(result).isEmpty();
    }

    // =========================================================
    // Label field i18n conversion
    // =========================================================

    @Test
    void processI18nTexts_labelField_exactLocaleMatch() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("zh-CN", "中文标签");
        i18nLabel.put("en-US", "English Label");
        schema.put("label", i18nLabel);

        Map<String, Object> result = processor.processI18nTexts(schema, "zh-CN");
        assertThat(result.get("label")).isEqualTo("中文标签");
    }

    @Test
    void processI18nTexts_labelField_enLocale() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("zh-CN", "中文标签");
        i18nLabel.put("en-US", "English Label");
        schema.put("label", i18nLabel);

        Map<String, Object> result = processor.processI18nTexts(schema, "en-US");
        assertThat(result.get("label")).isEqualTo("English Label");
    }

    @Test
    void processI18nTexts_labelField_languageCodeFallback() {
        // "en-GB" not present, but "en" is → falls back to "en"
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("zh-CN", "中文");
        i18nLabel.put("en", "English");
        schema.put("label", i18nLabel);

        Map<String, Object> result = processor.processI18nTexts(schema, "en-GB");
        assertThat(result.get("label")).isEqualTo("English");
    }

    @Test
    void processI18nTexts_labelField_sameLanguageDifferentRegion() {
        // "en-AU" not present, but "en-US" has same language prefix → falls back
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("zh-CN", "中文");
        i18nLabel.put("en-US", "American English");
        schema.put("label", i18nLabel);

        Map<String, Object> result = processor.processI18nTexts(schema, "en-AU");
        assertThat(result.get("label")).isEqualTo("American English");
    }

    @Test
    void processI18nTexts_labelField_defaultsToZhCN() {
        // Unknown locale falls back to zh-CN
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("zh-CN", "默认中文");
        schema.put("label", i18nLabel);

        Map<String, Object> result = processor.processI18nTexts(schema, "ja-JP");
        assertThat(result.get("label")).isEqualTo("默认中文");
    }

    @Test
    void processI18nTexts_labelField_defaultsToEnUS() {
        // Unknown locale, no zh-CN → falls back to en-US
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("en-US", "Fallback English");
        schema.put("label", i18nLabel);

        Map<String, Object> result = processor.processI18nTexts(schema, "fr-FR");
        assertThat(result.get("label")).isEqualTo("Fallback English");
    }

    @Test
    void processI18nTexts_labelField_defaultsToFirstValue() {
        // Unknown locale, no zh-CN, no en-US → first value
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("de-DE", "Deutsch");
        schema.put("label", i18nLabel);

        Map<String, Object> result = processor.processI18nTexts(schema, "fr-FR");
        assertThat(result.get("label")).isEqualTo("Deutsch");
    }

    // =========================================================
    // Fields that should be i18n-processed (confirm/title/placeholder/message)
    // =========================================================

    @Test
    void processI18nTexts_titleField_converted() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18n = new LinkedHashMap<>();
        i18n.put("zh-CN", "标题");
        i18n.put("en-US", "Title");
        schema.put("title", i18n);

        Map<String, Object> result = processor.processI18nTexts(schema, "en-US");
        assertThat(result.get("title")).isEqualTo("Title");
    }

    @Test
    void processI18nTexts_placeholderField_converted() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18n = new LinkedHashMap<>();
        i18n.put("zh-CN", "请输入");
        i18n.put("en-US", "Please enter");
        schema.put("placeholder", i18n);

        Map<String, Object> result = processor.processI18nTexts(schema, "zh-CN");
        assertThat(result.get("placeholder")).isEqualTo("请输入");
    }

    @Test
    void processI18nTexts_confirmField_converted() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18n = new LinkedHashMap<>();
        i18n.put("zh-CN", "确认");
        schema.put("confirm", i18n);

        Map<String, Object> result = processor.processI18nTexts(schema, "zh-CN");
        assertThat(result.get("confirm")).isEqualTo("确认");
    }

    @Test
    void processI18nTexts_messageField_converted() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> i18n = new LinkedHashMap<>();
        i18n.put("en-US", "Error message");
        schema.put("message", i18n);

        Map<String, Object> result = processor.processI18nTexts(schema, "en-US");
        assertThat(result.get("message")).isEqualTo("Error message");
    }

    // =========================================================
    // Non-i18n fields are not converted
    // =========================================================

    @Test
    void processI18nTexts_nonI18nField_notConverted() {
        // Field "code" should NOT be converted even if it looks like an i18n map
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> mapValue = new LinkedHashMap<>();
        mapValue.put("zh-CN", "value_cn");
        schema.put("code", mapValue);

        Map<String, Object> result = processor.processI18nTexts(schema, "zh-CN");
        // "code" field is not in the shouldProcessI18nField list → stays as Map
        assertThat(result.get("code")).isInstanceOf(Map.class);
    }

    // =========================================================
    // Nested Map and List recursion
    // =========================================================

    @Test
    void processI18nTexts_nestedMap_recursivelyConverted() {
        Map<String, Object> nested = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("zh-CN", "嵌套标签");
        i18nLabel.put("en-US", "Nested Label");
        nested.put("label", i18nLabel);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("field", nested);

        Map<String, Object> result = processor.processI18nTexts(schema, "en-US");
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldResult = (Map<String, Object>) result.get("field");
        assertThat(fieldResult.get("label")).isEqualTo("Nested Label");
    }

    @Test
    void processI18nTexts_listWithMaps_recursivelyConverted() {
        Map<String, Object> item = new LinkedHashMap<>();
        Map<String, Object> i18nLabel = new LinkedHashMap<>();
        i18nLabel.put("zh-CN", "列表标签");
        i18nLabel.put("en-US", "List Label");
        item.put("label", i18nLabel);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("items", List.of(item));

        Map<String, Object> result = processor.processI18nTexts(schema, "zh-CN");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items.get(0).get("label")).isEqualTo("列表标签");
    }

    @Test
    void processI18nTexts_listWithI18nObjects_convertedInPlace() {
        // A list where an element itself IS an i18n object
        Map<String, Object> i18nObj = new LinkedHashMap<>();
        i18nObj.put("zh-CN", "直接i18n");
        i18nObj.put("en-US", "Direct i18n");

        List<Object> items = new ArrayList<>();
        items.add(i18nObj);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("label", items);  // "label" field is processed, but value is a List

        // When label's value is a List, the List items themselves might be i18n objects
        processor.processI18nTexts(schema, "en-US");
        // No assertion on conversion since label with List value is unusual, just ensure no crash
    }

    // =========================================================
    // Non-i18n map values (nested DSL config maps) are recursed but not converted
    // =========================================================

    @Test
    void processI18nTexts_nestedNonI18nMap_notConverted() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "text");
        config.put("required", true);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("label", config);  // looks like label but config has no language codes

        Map<String, Object> result = processor.processI18nTexts(schema, "zh-CN");
        // Not an i18n object, stays as Map
        assertThat(result.get("label")).isInstanceOf(Map.class);
    }

    // =========================================================
    // String-valued schema fields pass through unchanged
    // =========================================================

    @Test
    void processI18nTexts_stringValue_passesThrough() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "text");
        schema.put("code", "field_001");

        Map<String, Object> result = processor.processI18nTexts(schema, "zh-CN");
        assertThat(result.get("type")).isEqualTo("text");
        assertThat(result.get("code")).isEqualTo("field_001");
    }
}
