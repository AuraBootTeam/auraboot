package com.auraboot.framework.tenant;

import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backend i18n (deep-review R1 F4): TenantApplicationServiceImpl no longer hard-codes Chinese
 * user-facing messages; it emits {@code $i18n:tenant.application.*} keys that
 * TenantSelectionController resolves to the request locale via I18nService. This pins that the
 * keys are registered in the i18n catalog and resolve correctly per locale (the controller's
 * resolution composes I18nLocaleResolver + I18nService.getValue over these keys).
 */
class TenantApplicationMessageI18nIT extends BaseIntegrationTest {

    @Autowired
    private I18nService i18nService;

    @Test
    @DisplayName("tenant.application.* keys resolve to Chinese under zh-CN")
    void resolvesZhCn() {
        assertThat(i18nService.getValue("zh-CN", "tenant.application.create_success"))
                .isEqualTo("租户创建成功，您已成为该租户的管理员");
        assertThat(i18nService.getValue("zh-CN", "tenant.application.invite_invalid"))
                .isEqualTo("无效的邀请码");
        assertThat(i18nService.getValue("zh-CN", "tenant.application.invite_revoked"))
                .isEqualTo("邀请码已失效");
        assertThat(i18nService.getValue("zh-CN", "tenant.application.invite_expired"))
                .isEqualTo("邀请码已过期");
        assertThat(i18nService.getValue("zh-CN", "tenant.application.join_pending"))
                .isEqualTo("加入申请已提交，等待租户管理员审批");
        assertThat(i18nService.getValue("zh-CN", "tenant.application.unknown_tenant"))
                .isEqualTo("未知租户");
    }

    @Test
    @DisplayName("tenant.application.* keys resolve to English under en-US")
    void resolvesEnUs() {
        assertThat(i18nService.getValue("en-US", "tenant.application.create_success"))
                .isEqualTo("Tenant created — you are now its administrator");
        assertThat(i18nService.getValue("en-US", "tenant.application.invite_invalid"))
                .isEqualTo("Invalid invite code");
        assertThat(i18nService.getValue("en-US", "tenant.application.join_pending"))
                .isEqualTo("Your join request has been submitted and is awaiting tenant admin approval");
    }

    @Test
    @DisplayName("missing locale (ja-JP gap) returns null so the controller falls back to the base locale")
    void missingLocaleFallsThrough() {
        // ja-JP/ko-KR are partial catalogs; these keys are absent there. getValue returns null,
        // which the controller's localize() resolves via the zh-CN base fallback.
        assertThat(i18nService.getValue("ja-JP", "tenant.application.create_success")).isNull();
    }
}
