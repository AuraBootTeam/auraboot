package com.auraboot.framework.meta.validator;

import com.auraboot.framework.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PageSchemaDslI18nValidator}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>LocalizedText (Map) → PASS</li>
 *   <li>$i18n: prefix → PASS</li>
 *   <li>Pure ASCII string → PASS</li>
 *   <li>Non-ASCII (Chinese) string → FAIL with path in message</li>
 *   <li>Nested blocks recursion → FAIL</li>
 *   <li>validatePageFields helper method</li>
 *   <li>validatePageSchema full page map scan</li>
 * </ul>
 */
@DisplayName("PageSchemaDslI18nValidator")
class PageSchemaDslI18nValidatorTest {

    // ==================== checkField / collectViolations ====================

    @Nested
    @DisplayName("collectViolations — single field rules")
    class CollectViolationsTest {

        @Test
        @DisplayName("null value → no violation")
        void nullValue_pass() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("title", null);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("blank string → no violation")
        void blankString_pass() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("title", "   ");
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("LocalizedText Map → PASS")
        void localizedTextMap_pass() {
            Map<String, Object> localizedText = Map.of(
                    "zh-CN", "提交",
                    "en-US", "Submit"
            );
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("title", localizedText);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("$i18n: reference key → PASS")
        void i18nKey_pass() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("label", "$i18n:page.contract.title");
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("pure ASCII string 'Submit' → PASS")
        void pureAscii_pass() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("buttonText", "Submit");
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("pure ASCII string 'OK' → PASS")
        void pureAscii_ok_pass() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("okText", "OK");
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Chinese string '提交' → FAIL with violation")
        void chineseString_fail() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("title", "提交");
            assertThat(violations).hasSize(1);
            assertThat(violations.get(0).path()).isEqualTo("title");
            assertThat(violations.get(0).value()).isEqualTo("提交");
        }

        @Test
        @DisplayName("Chinese string in label → FAIL with correct path")
        void chineseLabel_failWithPath() {
            String path = "pages[my_page].blocks[0].label";
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations(path, "用户名");
            assertThat(violations).hasSize(1);
            assertThat(violations.get(0).path()).isEqualTo(path);
        }

        @Test
        @DisplayName("Non-ASCII Japanese string → FAIL")
        void japaneseString_fail() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("tooltip", "送信");
            assertThat(violations).hasSize(1);
        }

        @Test
        @DisplayName("Non-string value (Integer) → no violation")
        void integerValue_pass() {
            List<PageSchemaDslI18nValidator.Violation> violations =
                    PageSchemaDslI18nValidator.collectViolations("sortWeight", 42);
            assertThat(violations).isEmpty();
        }
    }

    // ==================== isPureAscii ====================

    @Nested
    @DisplayName("isPureAscii helper")
    class IsPureAsciiTest {

        @Test
        void emptyString_ascii() {
            assertThat(PageSchemaDslI18nValidator.isPureAscii("")).isTrue();
        }

        @Test
        void asciiOnlyString_ascii() {
            assertThat(PageSchemaDslI18nValidator.isPureAscii("Hello World 123!")).isTrue();
        }

        @Test
        void chineseChar_notAscii() {
            assertThat(PageSchemaDslI18nValidator.isPureAscii("中文")).isFalse();
        }

        @Test
        void mixedAsciiChinese_notAscii() {
            assertThat(PageSchemaDslI18nValidator.isPureAscii("Submit 提交")).isFalse();
        }
    }

    // ==================== validatePageFields ====================

    @Nested
    @DisplayName("validatePageFields")
    class ValidatePageFieldsTest {

        @Test
        @DisplayName("both null → no exception")
        void bothNull_pass() {
            assertThatNoException().isThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageFields(null, null, "my_page"));
        }

        @Test
        @DisplayName("ASCII title → no exception")
        void asciiTitle_pass() {
            assertThatNoException().isThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageFields("Contract List", null, "my_page"));
        }

        @Test
        @DisplayName("$i18n: title → no exception")
        void i18nTitle_pass() {
            assertThatNoException().isThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageFields("$i18n:page.contract.title", null, "my_page"));
        }

        @Test
        @DisplayName("LocalizedText map title → no exception")
        void localizedTextTitle_pass() {
            Map<String, Object> title = Map.of("zh-CN", "合同列表", "en-US", "Contract List");
            assertThatNoException().isThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageFields(title, null, "my_page"));
        }

        @Test
        @DisplayName("Chinese title '合同列表' → ValidationException with path in message")
        void chineseTitle_throwsWithPath() {
            assertThatThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageFields("合同列表", null, "my_page"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("pages[my_page].title")
                    .hasMessageContaining("合同列表");
        }

        @Test
        @DisplayName("Chinese description → ValidationException")
        void chineseDescription_throws() {
            assertThatThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageFields(null, "这是描述", "my_page"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("pages[my_page].description");
        }
    }

    // ==================== validatePageSchema (full map scan + recursion) ====================

    @Nested
    @DisplayName("validatePageSchema — full page map")
    class ValidatePageSchemaTest {

        @Test
        @DisplayName("compliant page with LocalizedText title and ASCII blocks → no exception")
        void compliantPage_pass() {
            Map<String, Object> page = Map.of(
                    "title", Map.of("zh-CN", "合同列表", "en-US", "Contract List"),
                    "description", "A list of contracts",
                    "blocks", List.of(
                            Map.of(
                                    "blockType", "table",
                                    "title", "Contract Table",
                                    "columns", List.of(
                                            Map.of("label", "$i18n:field.contract.name.label"),
                                            Map.of("label", "Status")
                                    )
                            )
                    )
            );
            assertThatNoException().isThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageSchema(page, "contract_list"));
        }

        @Test
        @DisplayName("page with hardcoded Chinese block title → FAIL")
        void blockWithChineseTitle_fails() {
            Map<String, Object> page = Map.of(
                    "title", Map.of("zh-CN", "合同", "en-US", "Contract"),
                    "blocks", List.of(
                            Map.of(
                                    "blockType", "toolbar",
                                    "title", "工具栏"   // hardcoded Chinese — violation
                            )
                    )
            );
            assertThatThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageSchema(page, "contract_list"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("blocks[0].title")
                    .hasMessageContaining("工具栏");
        }

        @Test
        @DisplayName("nested column label with Chinese → FAIL (recursion)")
        void nestedColumnChineseLabel_failsRecursion() {
            Map<String, Object> page = Map.of(
                    "title", "$i18n:page.contract.title",
                    "blocks", List.of(
                            Map.of(
                                    "blockType", "table",
                                    "columns", List.of(
                                            Map.of("label", "Contract Name"),       // ASCII — ok
                                            Map.of("label", "合同金额")             // Chinese — violation
                                    )
                            )
                    )
            );
            assertThatThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageSchema(page, "contract_list"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("blocks[0].columns[1].label")
                    .hasMessageContaining("合同金额");
        }

        @Test
        @DisplayName("nested action buttonText with Chinese → FAIL (action recursion)")
        void nestedActionChineseButtonText_fails() {
            Map<String, Object> page = Map.of(
                    "title", "Contract List",
                    "blocks", List.of(
                            Map.of(
                                    "blockType", "toolbar",
                                    "actions", List.of(
                                            Map.of("buttonText", "新建合同")   // Chinese — violation
                                    )
                            )
                    )
            );
            assertThatThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageSchema(page, "contract_list"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("blocks[0].actions[0].buttonText")
                    .hasMessageContaining("新建合同");
        }

        @Test
        @DisplayName("page with hardcoded Chinese page-level title → FAIL")
        void pageLevelChineseTitle_fails() {
            Map<String, Object> page = Map.of(
                    "title", "合同管理",    // hardcoded Chinese at page level
                    "blocks", List.of()
            );
            assertThatThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageSchema(page, "contract_list"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("pages[contract_list].title")
                    .hasMessageContaining("合同管理");
        }

        @Test
        @DisplayName("page with no blocks → no exception (blocks optional at schema level)")
        void noBlocks_pass() {
            Map<String, Object> page = Map.of(
                    "title", "Contract List",
                    "kind", "list"
            );
            assertThatNoException().isThrownBy(
                    () -> PageSchemaDslI18nValidator.validatePageSchema(page, "contract_list"));
        }
    }
}
