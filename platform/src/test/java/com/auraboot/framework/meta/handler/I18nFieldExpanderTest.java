package com.auraboot.framework.meta.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.auraboot.framework.meta.handler.I18nFieldExpander.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for I18nFieldExpander static helper methods.
 *
 * Integration coverage (companion field DB creation) is tested via
 * model-publish lifecycle in ModelServiceIntegrationTest.
 */
@DisplayName("I18nFieldExpander")
class I18nFieldExpanderTest {

    // ─── getCompanionFieldCode ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getCompanionFieldCode()")
    class GetCompanionFieldCode {

        @Test
        @DisplayName("zh-CN returns primary field code unchanged")
        void zhCN_returnsPrimary() {
            assertThat(getCompanionFieldCode("product_name", "zh-CN"))
                    .isEqualTo("product_name");
        }

        @Test
        @DisplayName("zh-TW also returns primary field code (zh prefix)")
        void zhTW_returnsPrimary() {
            assertThat(getCompanionFieldCode("product_name", "zh-TW"))
                    .isEqualTo("product_name");
        }

        @Test
        @DisplayName("null locale returns primary field code")
        void null_returnsPrimary() {
            assertThat(getCompanionFieldCode("product_name", null))
                    .isEqualTo("product_name");
        }

        @Test
        @DisplayName("en-US returns _en_us companion code")
        void enUS_returnsEnUsCompanion() {
            assertThat(getCompanionFieldCode("product_name", "en-US"))
                    .isEqualTo("product_name_en_us");
        }

        @Test
        @DisplayName("ja-JP returns _ja_jp companion code")
        void jaJP_returnsJaJpCompanion() {
            assertThat(getCompanionFieldCode("product_name", "ja-JP"))
                    .isEqualTo("product_name_ja_jp");
        }

        @Test
        @DisplayName("ko-KR returns _ko_kr companion code")
        void koKR_returnsKoKrCompanion() {
            assertThat(getCompanionFieldCode("product_name", "ko-KR"))
                    .isEqualTo("product_name_ko_kr");
        }

        @Test
        @DisplayName("unsupported locale falls back to primary field code")
        void unsupportedLocale_returnsPrimary() {
            assertThat(getCompanionFieldCode("product_name", "fr-FR"))
                    .isEqualTo("product_name");
        }
    }

    // ─── resolveLocalizedValue ─────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveLocalizedValue()")
    class ResolveLocalizedValue {

        @Test
        @DisplayName("zh-CN returns primary field value directly")
        void zhCN_returnsPrimaryValue() {
            Map<String, Object> record = new HashMap<>();
            record.put("product_name", "产品名称");
            record.put("product_name_en_us", "Product Name");

            assertThat(resolveLocalizedValue(record, "product_name", "zh-CN"))
                    .isEqualTo("产品名称");
        }

        @Test
        @DisplayName("en-US returns companion field value when present")
        void enUS_returnsCompanionValue() {
            Map<String, Object> record = new HashMap<>();
            record.put("product_name", "产品名称");
            record.put("product_name_en_us", "Product Name");

            assertThat(resolveLocalizedValue(record, "product_name", "en-US"))
                    .isEqualTo("Product Name");
        }

        @Test
        @DisplayName("en-US falls back to primary when companion is null")
        void enUS_fallbackToPrimary_whenCompanionNull() {
            Map<String, Object> record = new HashMap<>();
            record.put("product_name", "产品名称");
            // no product_name_en_us

            assertThat(resolveLocalizedValue(record, "product_name", "en-US"))
                    .isEqualTo("产品名称");
        }

        @Test
        @DisplayName("en-US falls back to primary when companion is blank string")
        void enUS_fallbackToPrimary_whenCompanionBlank() {
            Map<String, Object> record = new HashMap<>();
            record.put("product_name", "产品名称");
            record.put("product_name_en_us", "   ");

            assertThat(resolveLocalizedValue(record, "product_name", "en-US"))
                    .isEqualTo("产品名称");
        }

        @Test
        @DisplayName("null record returns null")
        void nullRecord_returnsNull() {
            assertThat(resolveLocalizedValue(null, "product_name", "en-US"))
                    .isNull();
        }

        @Test
        @DisplayName("ja-JP returns ja companion when present")
        void jaJP_returnsJaCompanion() {
            Map<String, Object> record = new HashMap<>();
            record.put("product_name", "产品名称");
            record.put("product_name_ja_jp", "製品名");

            assertThat(resolveLocalizedValue(record, "product_name", "ja-JP"))
                    .isEqualTo("製品名");
        }
    }

    // ─── LOCALE_SUFFIXES sanity checks ────────────────────────────────────────

    @Nested
    @DisplayName("LOCALE_SUFFIXES map")
    class LocaleSuffixes {

        @Test
        @DisplayName("contains all three supported locales")
        void containsAllSupportedLocales() {
            assertThat(LOCALE_SUFFIXES).containsOnlyKeys("en-US", "ja-JP", "ko-KR");
        }

        @Test
        @DisplayName("suffix format is lowercase with underscores")
        void suffixFormat_isLowercaseUnderscore() {
            LOCALE_SUFFIXES.values().forEach(suffix -> {
                assertThat(suffix).startsWith("_");
                assertThat(suffix).doesNotContainPattern("[A-Z]");
                assertThat(suffix).matches("_[a-z]{2}_[a-z]{2}");
            });
        }
    }
}
