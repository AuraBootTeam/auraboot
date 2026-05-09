package com.auraboot.framework.i18n.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class I18nLocaleResolverTest {

    @Mock
    private HttpServletRequest request;

    private I18nLocaleResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new I18nLocaleResolver();
    }

    @Test
    void resolveLocale_queryParamWins() {
        when(request.getParameter("locale")).thenReturn("en-US");
        assertThat(resolver.resolveLocale(request)).isEqualTo("en-US");
    }

    @Test
    void resolveLocale_invalidQueryParam_fallsThrough() {
        when(request.getParameter("locale")).thenReturn("");
        when(request.getHeader("X-Locale")).thenReturn("ja-JP");
        assertThat(resolver.resolveLocale(request)).isEqualTo("ja-JP");
    }

    @Test
    void resolveLocale_xLocaleHeader_used() {
        when(request.getParameter("locale")).thenReturn(null);
        when(request.getHeader("X-Locale")).thenReturn("ko-KR");
        assertThat(resolver.resolveLocale(request)).isEqualTo("ko-KR");
    }

    @Test
    void resolveLocale_acceptLanguageHeader_used() {
        when(request.getParameter("locale")).thenReturn(null);
        when(request.getHeader("X-Locale")).thenReturn(null);
        when(request.getHeader("Accept-Language")).thenReturn("zh-CN,zh;q=0.9,en;q=0.8");
        assertThat(resolver.resolveLocale(request)).isEqualTo("zh-CN");
    }

    @Test
    void resolveLocale_allMissing_returnsDefault() {
        when(request.getParameter("locale")).thenReturn(null);
        when(request.getHeader("X-Locale")).thenReturn(null);
        when(request.getHeader("Accept-Language")).thenReturn(null);
        assertThat(resolver.resolveLocale(request)).isEqualTo("zh-CN");
    }

    @Test
    void resolveLocale_emptyAcceptLanguage_returnsDefault() {
        when(request.getParameter("locale")).thenReturn(null);
        when(request.getHeader("X-Locale")).thenReturn(null);
        when(request.getHeader("Accept-Language")).thenReturn("");
        assertThat(resolver.resolveLocale(request)).isEqualTo("zh-CN");
    }

    @Test
    void getDefaultLocale_returnsZhCn() {
        assertThat(resolver.getDefaultLocale()).isEqualTo("zh-CN");
    }
}
