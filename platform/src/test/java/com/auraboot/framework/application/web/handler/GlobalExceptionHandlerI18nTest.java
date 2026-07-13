package com.auraboot.framework.application.web.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler localizes {@code $i18n:<key>} BusinessException messages to the request
 * locale via the existing i18n catalog (deep-review R1, exception-message i18n). Messages without
 * the prefix pass through untouched, so converting one message at a time is safe.
 */
class GlobalExceptionHandlerI18nTest {

    private GlobalExceptionHandler handler;
    private I18nService i18nService;
    private I18nLocaleResolver localeResolver;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        i18nService = mock(I18nService.class);
        localeResolver = mock(I18nLocaleResolver.class);
        request = mock(HttpServletRequest.class);
        ReflectionTestUtils.setField(handler, "i18nService", i18nService);
        ReflectionTestUtils.setField(handler, "i18nLocaleResolver", localeResolver);
    }

    @Test
    void resolvesI18nPrefixedMessageToRequestLocale() {
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getValue("en-US", "tenant.member.already_member"))
                .thenReturn("User is already a member of this tenant");

        String out = handler.localizeI18nMessage("$i18n:tenant.member.already_member", request);

        assertThat(out).isEqualTo("User is already a member of this tenant");
    }

    @Test
    void passesThroughPlainMessageUnchangedAndDoesNotTouchI18n() {
        // Not-yet-migrated message (e.g. interpolated) must be a no-op — zero behavior change.
        String out = handler.localizeI18nMessage("成员不存在: 42", request);

        assertThat(out).isEqualTo("成员不存在: 42");
        verifyNoInteractions(i18nService, localeResolver);
    }

    @Test
    void fallsBackToBaseLocaleWhenRequestLocaleMissingKey() {
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getValue("ja-JP", "tenant.member.not_in_tenant")).thenReturn(null);
        when(i18nService.getValue("zh-CN", "tenant.member.not_in_tenant")).thenReturn("用户未加入任何租户");

        String out = handler.localizeI18nMessage("$i18n:tenant.member.not_in_tenant", request);

        assertThat(out).isEqualTo("用户未加入任何租户");
    }

    @Test
    void returnsBareKeyWhenWhollyUnresolved() {
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getValue(anyString(), eq("tenant.member.unknown"))).thenReturn(null);

        String out = handler.localizeI18nMessage("$i18n:tenant.member.unknown", request);

        assertThat(out).isEqualTo("tenant.member.unknown");
    }

    @Test
    void nullMessagePassesThrough() {
        assertThat(handler.localizeI18nMessage(null, request)).isNull();
    }

    @Test
    void resolvesParameterizedBusinessException() {
        // BusinessException.i18n(key, args) carries the {0} args to the boundary.
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getMessage("en-US", "tenant.member.not_found", 42L))
                .thenReturn("Member not found: 42");

        BusinessException ex = BusinessException.i18n("tenant.member.not_found", 42L);
        String out = handler.localizeBusinessMessage(ex, request);

        assertThat(out).isEqualTo("Member not found: 42");
    }

    @Test
    void parameterizedFallsBackToBaseLocale() {
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getMessage("ja-JP", "tenant.not_found", 7L)).thenReturn(null);
        when(i18nService.getMessage("zh-CN", "tenant.not_found", 7L)).thenReturn("租户不存在: 7");

        BusinessException ex = BusinessException.i18n("tenant.not_found", 7L);

        assertThat(handler.localizeBusinessMessage(ex, request)).isEqualTo("租户不存在: 7");
    }

    @Test
    void staticBusinessExceptionStillResolvesWithoutArgs() {
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getValue("en-US", "tenant.member.already_member"))
                .thenReturn("User is already a member of this tenant");

        BusinessException ex = new BusinessException("$i18n:tenant.member.already_member");

        assertThat(handler.localizeBusinessMessage(ex, request))
                .isEqualTo("User is already a member of this tenant");
    }

    @Test
    @SuppressWarnings("unchecked")
    void devEnvironmentDetailCarriesLocalizedTextNotTheRawKey() {
        // The frontend toasts context.detail. On a dev stack that field used to carry the raw
        // exception message, so rule reason keys leaked into the UI (workflow-demo submit showed
        // "annual_leave_insufficient"). Dev must show the same localized text as production.
        ReflectionTestUtils.setField(handler, "activeProfile", "dev");
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "error.wd_leave_validation.annual_balance_not_found"))
                .thenReturn("未找到该员工的年假余额记录");

        BusinessException ex =
                new BusinessException("$i18n:error.wd_leave_validation.annual_balance_not_found");

        var response = handler.handleBusinessException(ex, request);

        Map<String, String> context =
                (Map<String, String>) response.getBody().getContext();
        assertThat(context).containsEntry("detail", "未找到该员工的年假余额记录");
        assertThat(context)
                .containsEntry("messageKey", "$i18n:error.wd_leave_validation.annual_balance_not_found");
        assertThat(context).containsEntry("exception", "BusinessException");
    }

    @Test
    void productionDetailIsTheLocalizedStringOnly() {
        ReflectionTestUtils.setField(handler, "activeProfile", "prod");
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "error.wd_leave_validation.annual_leave_insufficient"))
                .thenReturn("剩余年假不足，无法提交该申请");

        BusinessException ex =
                new BusinessException("$i18n:error.wd_leave_validation.annual_leave_insufficient");

        var response = handler.handleBusinessException(ex, request);

        assertThat(response.getBody().getContext()).isEqualTo("剩余年假不足，无法提交该申请");
    }
}
