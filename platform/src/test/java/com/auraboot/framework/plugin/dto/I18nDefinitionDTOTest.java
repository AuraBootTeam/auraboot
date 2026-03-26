package com.auraboot.framework.plugin.dto;

import com.auraboot.framework.plugin.dto.imports.I18nDefinitionDTO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for I18nDefinitionDTO.
 * Tests JSON deserialization, locale extraction, validation logic.
 */
@DisplayName("I18nDefinitionDTO Unit Tests")
class I18nDefinitionDTOTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ==================== JSON Deserialization ====================

    @Test
    @DisplayName("Should deserialize standard JSON with zh-CN and en-US")
    void shouldDeserializeStandardJson() throws Exception {
        String json = """
                {
                  "key": "model.brand._meta.label",
                  "zh-CN": "品牌",
                  "en-US": "Brand",
                  "source": "import",
                  "refType": "model"
                }
                """;

        I18nDefinitionDTO dto = objectMapper.readValue(json, I18nDefinitionDTO.class);

        assertThat(dto.getKey()).isEqualTo("model.brand._meta.label");
        assertThat(dto.getZhCN()).isEqualTo("品牌");
        assertThat(dto.getEnUS()).isEqualTo("Brand");
        assertThat(dto.getSource()).isEqualTo("import");
        assertThat(dto.getRefType()).isEqualTo("model");
    }

    @Test
    @DisplayName("Should deserialize with optional ja-JP field")
    void shouldDeserializeWithJaJP() throws Exception {
        String json = """
                {
                  "key": "model.device._meta.label",
                  "zh-CN": "设备",
                  "en-US": "Device",
                  "ja-JP": "デバイス"
                }
                """;

        I18nDefinitionDTO dto = objectMapper.readValue(json, I18nDefinitionDTO.class);

        assertThat(dto.getJaJP()).isEqualTo("デバイス");
    }

    // ==================== getAllTranslations ====================

    @Test
    @DisplayName("getAllTranslations should return all named locale translations")
    void shouldReturnAllNamedTranslations() throws Exception {
        String json = """
                {
                  "key": "test.key",
                  "zh-CN": "中文",
                  "en-US": "English",
                  "ja-JP": "日本語"
                }
                """;

        I18nDefinitionDTO dto = objectMapper.readValue(json, I18nDefinitionDTO.class);
        Map<String, String> translations = dto.getAllTranslations();

        assertThat(translations)
                .hasSize(3)
                .containsEntry("zh-CN", "中文")
                .containsEntry("en-US", "English")
                .containsEntry("ja-JP", "日本語");
    }

    @Test
    @DisplayName("getAllTranslations should include extra locale translations")
    void shouldIncludeExtraLocaleTranslations() throws Exception {
        String json = """
                {
                  "key": "test.key",
                  "zh-CN": "中文",
                  "en-US": "English",
                  "ko-KR": "한국어",
                  "fr-FR": "Français"
                }
                """;

        I18nDefinitionDTO dto = objectMapper.readValue(json, I18nDefinitionDTO.class);
        Map<String, String> translations = dto.getAllTranslations();

        assertThat(translations)
                .hasSize(4)
                .containsEntry("zh-CN", "中文")
                .containsEntry("en-US", "English")
                .containsEntry("ko-KR", "한국어")
                .containsEntry("fr-FR", "Français");
    }

    // ==================== @JsonAnySetter Extra Locales ====================

    @Test
    @DisplayName("Extra locale via @JsonAnySetter should be captured")
    void shouldCaptureExtraLocaleViaJsonAnySetter() throws Exception {
        String json = """
                {
                  "key": "test.key",
                  "zh-CN": "中文",
                  "fr-FR": "Marque"
                }
                """;

        I18nDefinitionDTO dto = objectMapper.readValue(json, I18nDefinitionDTO.class);

        assertThat(dto.getExtraTranslations())
                .isNotNull()
                .hasSize(1)
                .containsEntry("fr-FR", "Marque");
    }

    @Test
    @DisplayName("Non-locale format extra fields should be ignored")
    void shouldIgnoreNonLocaleExtraFields() throws Exception {
        String json = """
                {
                  "key": "test.key",
                  "zh-CN": "中文",
                  "foo": "bar",
                  "notALocale": "value",
                  "abc": "xyz"
                }
                """;

        I18nDefinitionDTO dto = objectMapper.readValue(json, I18nDefinitionDTO.class);

        // "foo", "notALocale", "abc" don't match xx-XX pattern
        assertThat(dto.getExtraTranslations()).isNullOrEmpty();
    }

    // ==================== isValid ====================

    @Test
    @DisplayName("isValid should return true when key and translations are present")
    void shouldReturnTrueForValidDto() {
        I18nDefinitionDTO dto = new I18nDefinitionDTO();
        dto.setKey("test.key");
        dto.setZhCN("中文");

        assertThat(dto.isValid()).isTrue();
    }

    @Test
    @DisplayName("isValid should return false when key is null")
    void shouldReturnFalseWhenKeyIsNull() {
        I18nDefinitionDTO dto = new I18nDefinitionDTO();
        dto.setKey(null);
        dto.setZhCN("中文");

        assertThat(dto.isValid()).isFalse();
    }

    @Test
    @DisplayName("isValid should return false when key is blank")
    void shouldReturnFalseWhenKeyIsBlank() {
        I18nDefinitionDTO dto = new I18nDefinitionDTO();
        dto.setKey("   ");
        dto.setZhCN("中文");

        assertThat(dto.isValid()).isFalse();
    }

    @Test
    @DisplayName("isValid should return false when no translations exist")
    void shouldReturnFalseWhenNoTranslations() {
        I18nDefinitionDTO dto = new I18nDefinitionDTO();
        dto.setKey("test.key");
        // No translations set

        assertThat(dto.isValid()).isFalse();
    }

    @Test
    @DisplayName("isValid should return true with only extra translations")
    void shouldReturnTrueWithOnlyExtraTranslations() throws Exception {
        String json = """
                {
                  "key": "test.key",
                  "ko-KR": "한국어"
                }
                """;

        I18nDefinitionDTO dto = objectMapper.readValue(json, I18nDefinitionDTO.class);

        assertThat(dto.isValid()).isTrue();
    }
}
