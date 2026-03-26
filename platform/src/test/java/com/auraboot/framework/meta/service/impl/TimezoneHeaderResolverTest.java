package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimezoneHeaderResolverTest {

    private final TimezoneHeaderResolver resolver = new TimezoneHeaderResolver();

    @Test
    void validTimezone_returnsZoneId() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Timezone")).thenReturn("Asia/Shanghai");

        ZoneId result = resolver.resolve(request);

        assertThat(result).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void utcTimezone_returnsZoneId() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Timezone")).thenReturn("UTC");

        ZoneId result = resolver.resolve(request);

        assertThat(result).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void nullHeader_returnsNull() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Timezone")).thenReturn(null);

        ZoneId result = resolver.resolve(request);

        assertThat(result).isNull();
    }

    @Test
    void blankHeader_returnsNull() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Timezone")).thenReturn("  ");

        ZoneId result = resolver.resolve(request);

        assertThat(result).isNull();
    }

    @Test
    void invalidTimezone_throwsBusinessException() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Timezone")).thenReturn("invalid/zone");

        assertThatThrownBy(() -> resolver.resolve(request))
            .isInstanceOf(BusinessException.class);
    }
}
