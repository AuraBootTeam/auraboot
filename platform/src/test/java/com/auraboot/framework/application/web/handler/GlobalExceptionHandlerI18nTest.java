package com.auraboot.framework.application.web.handler;

import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
}
