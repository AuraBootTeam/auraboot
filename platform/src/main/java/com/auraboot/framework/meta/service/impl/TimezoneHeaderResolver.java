package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Resolves and validates the X-Timezone request header.
 *
 * <p>The X-Timezone header is sent by the frontend on every request, containing
 * the user's effective timezone (resolved cascade: user pref > tenant pref > browser).
 * It is used for query boundary calculations (e.g., "today's orders" in user timezone),
 * NOT for parsing datetime values (those require explicit offset in the payload).
 */
@Slf4j
@Component
public class TimezoneHeaderResolver {

    public static final String HEADER_NAME = "X-Timezone";

    /**
     * Resolve and validate the X-Timezone header from the request.
     *
     * @param request the HTTP request
     * @return the validated ZoneId, or null if header is absent
     * @throws BusinessException (HTTP 400) if the header value is not a valid IANA timezone
     */
    public ZoneId resolve(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(header);
        } catch (DateTimeException e) {
            log.warn("Invalid X-Timezone header value: '{}'", header);
            throw new BusinessException(ResponseCode.BadParam,
                "Invalid timezone identifier: '" + header + "'. Use IANA timezone format (e.g. Asia/Shanghai, UTC)");
        }
    }
}
