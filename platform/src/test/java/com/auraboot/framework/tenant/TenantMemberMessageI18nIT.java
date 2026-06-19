package com.auraboot.framework.tenant;

import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the static tenant-member BusinessException messages migrated to {@code $i18n:} keys
 * (TenantMemberServiceImpl / TenantInviteServiceImpl) are registered in the i18n catalog and
 * resolve per locale. GlobalExceptionHandler resolves these at error-response time.
 */
class TenantMemberMessageI18nIT extends BaseIntegrationTest {

    @Autowired
    private I18nService i18nService;

    @Test
    @DisplayName("tenant.member.* keys resolve per locale")
    void resolvesPerLocale() {
        assertThat(i18nService.getValue("zh-CN", "tenant.member.already_member"))
                .isEqualTo("用户已经是该租户的成员");
        assertThat(i18nService.getValue("en-US", "tenant.member.already_member"))
                .isEqualTo("User is already a member of this tenant");
        assertThat(i18nService.getValue("zh-CN", "tenant.member.not_in_tenant"))
                .isEqualTo("用户未加入任何租户");
        assertThat(i18nService.getValue("en-US", "tenant.member.not_in_tenant"))
                .isEqualTo("User does not belong to any tenant");
    }

    @Test
    @DisplayName("parameterized tenant keys substitute {0} per locale (getMessage)")
    void parameterizedKeysSubstitute() {
        assertThat(i18nService.getMessage("zh-CN", "tenant.member.not_found", 42L))
                .isEqualTo("成员不存在: 42");
        assertThat(i18nService.getMessage("en-US", "tenant.member.not_found", 42L))
                .isEqualTo("Member not found: 42");
        assertThat(i18nService.getMessage("zh-CN", "tenant.not_found", 7L))
                .isEqualTo("租户不存在: 7");
        assertThat(i18nService.getMessage("en-US", "tenant.name_exists", "acme"))
                .isEqualTo("Tenant name already exists: acme");
        // missing locale -> null so the boundary falls back to the base locale
        assertThat(i18nService.getMessage("ja-JP", "tenant.member.not_found", 42L)).isNull();
    }
}
