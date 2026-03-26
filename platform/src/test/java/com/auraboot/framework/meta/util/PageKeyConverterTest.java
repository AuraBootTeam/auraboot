package com.auraboot.framework.meta.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PageKeyConverter.
 */
class PageKeyConverterTest {

    @Test
    void toModelCode_hyphenated_replacesWithUnderscore() {
        assertThat(PageKeyConverter.toModelCode("crm-lead")).isEqualTo("crm_lead");
    }

    @Test
    void toModelCode_alreadyUnderscore_unchanged() {
        assertThat(PageKeyConverter.toModelCode("crm_lead")).isEqualTo("crm_lead");
    }

    @Test
    void toModelCode_upperCase_lowercased() {
        assertThat(PageKeyConverter.toModelCode("CRM-Lead")).isEqualTo("crm_lead");
    }

    @Test
    void toModelCode_multipleHyphens_allReplaced() {
        assertThat(PageKeyConverter.toModelCode("my-long-page-key")).isEqualTo("my_long_page_key");
    }

    @Test
    void toModelCode_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> PageKeyConverter.toModelCode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toModelCode_blank_throwsIllegalArgument() {
        assertThatThrownBy(() -> PageKeyConverter.toModelCode("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toPageKey_underscore_replacesWithHyphen() {
        assertThat(PageKeyConverter.toPageKey("crm_lead")).isEqualTo("crm-lead");
    }

    @Test
    void toPageKey_alreadyHyphen_unchanged() {
        assertThat(PageKeyConverter.toPageKey("crm-lead")).isEqualTo("crm-lead");
    }

    @Test
    void toPageKey_upperCase_lowercased() {
        assertThat(PageKeyConverter.toPageKey("CRM_Lead")).isEqualTo("crm-lead");
    }

    @Test
    void toPageKey_multipleUnderscores_allReplaced() {
        assertThat(PageKeyConverter.toPageKey("my_long_model_code")).isEqualTo("my-long-model-code");
    }

    @Test
    void toPageKey_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> PageKeyConverter.toPageKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toPageKey_blank_throwsIllegalArgument() {
        assertThatThrownBy(() -> PageKeyConverter.toPageKey("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toModelCode_listSuffix_stripped() {
        assertThat(PageKeyConverter.toModelCode("e2et_order_list")).isEqualTo("e2et_order");
    }

    @Test
    void toModelCode_formSuffix_stripped() {
        assertThat(PageKeyConverter.toModelCode("crm_lead_form")).isEqualTo("crm_lead");
    }

    @Test
    void toModelCode_detailSuffix_stripped() {
        assertThat(PageKeyConverter.toModelCode("crm-lead-detail")).isEqualTo("crm_lead");
    }

    @Test
    void toModelCode_dashboardSuffix_stripped() {
        assertThat(PageKeyConverter.toModelCode("sales_dashboard")).isEqualTo("sales");
    }

    @Test
    void toModelCode_noSuffix_unchanged() {
        assertThat(PageKeyConverter.toModelCode("crm_lead")).isEqualTo("crm_lead");
    }

    @Test
    void roundTrip_modelCodeNoSuffix() {
        // pageKeys without page-type suffix round-trip correctly
        String pageKey = "crm-lead";
        String modelCode = PageKeyConverter.toModelCode(pageKey);
        String backToPageKey = PageKeyConverter.toPageKey(modelCode);
        assertThat(backToPageKey).isEqualTo(pageKey);
    }
}
