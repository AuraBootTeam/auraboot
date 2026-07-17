package com.auraboot.framework.observability.clienterror;

import lombok.Data;

import java.time.Instant;

/**
 * Payload posted by the browser global error handler (window.onerror /
 * unhandledrejection). All fields are best-effort; only the tenant/user are
 * enforced server-side from the session.
 */
@Data
public class WebClientErrorRequest {
    private String errorType;
    private String message;
    private String stack;
    private String pageUrl;
    private String userAgent;
    private String appVersion;
    private String sessionId;
    private String traceId;
    private Instant clientTimestamp;
}
