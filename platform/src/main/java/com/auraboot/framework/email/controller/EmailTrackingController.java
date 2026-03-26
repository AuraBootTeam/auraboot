package com.auraboot.framework.email.controller;

import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.service.EmailTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Public endpoints for email open and click tracking.
 *
 * <p><b>Authentication note:</b> These endpoints are intentionally public (no JWT required).
 * Email clients loading the tracking pixel do not have a user session, so authentication
 * must not be enforced here. The whitelist entry in the security config covers
 * {@code /api/email/tracking/**}.
 *
 * @since 6.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/email/tracking")
@RequiredArgsConstructor
@Tag(name = "Email Tracking", description = "Public open/click tracking endpoints (no auth required)")
public class EmailTrackingController {

    /**
     * 43-byte transparent 1×1 GIF.
     * Standard minimal GIF89a binary representation.
     */
    private static final byte[] TRANSPARENT_GIF = {
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00,
        0x01, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
        0x00, 0x00, 0x00, 0x21, (byte) 0xF9, 0x04, 0x01, 0x00,
        0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
        0x01, 0x00, 0x3B
    };

    private final EmailTrackingService emailTrackingService;

    // ──────────────────────────────────────────────────────────────────────────
    // Open tracking
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Records an open event and returns a 1×1 transparent GIF.
     *
     * <p>Email clients request this image when they render the email.
     * The response includes {@code Cache-Control: no-cache, no-store} to ensure
     * every open triggers a new HTTP request.
     *
     * @param trackingId the 32-char tracking token embedded in the email
     * @param request    HTTP request (used to extract IP and User-Agent)
     * @return 43-byte transparent GIF with Content-Type: image/gif
     */
    @GetMapping(value = "/{trackingId}/open.gif", produces = MediaType.IMAGE_GIF_VALUE)
    @Operation(summary = "Email open tracking pixel (no auth required)")
    public ResponseEntity<byte[]> trackOpen(
            @PathVariable String trackingId,
            HttpServletRequest request) {

        String ipAddress = resolveIp(request);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);

        log.debug("Open event: trackingId={}, ip={}", trackingId, ipAddress);

        emailTrackingService.recordEvent(
                trackingId,
                EmailConstants.TRACKING_OPEN,
                null,
                ipAddress,
                userAgent,
                null,   // tenantId — unknown without lookup; background job can reconcile
                null    // messageId — resolved by trackingId lookup if needed
        );

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_GIF)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(TRANSPARENT_GIF);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Click tracking
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Records a click event and 302-redirects the user to the original URL.
     *
     * @param trackingId  the 32-char tracking token embedded in the link
     * @param url         original URL (URL-decoded by Spring from the {@code url} query param)
     * @param request     HTTP request (IP + User-Agent)
     * @param response    HTTP response for the redirect
     * @throws IOException if writing the redirect fails
     */
    @GetMapping("/{trackingId}/click")
    @Operation(summary = "Email click tracking redirect (no auth required)")
    public void trackClick(
            @PathVariable String trackingId,
            @RequestParam(required = false) String url,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        String ipAddress = resolveIp(request);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);

        log.debug("Click event: trackingId={}, url={}, ip={}", trackingId, url, ipAddress);

        emailTrackingService.recordEvent(
                trackingId,
                EmailConstants.TRACKING_CLICK,
                url,
                ipAddress,
                userAgent,
                null,
                null
        );

        if (url == null || url.isBlank()) {
            log.warn("Click tracking called without url param: trackingId={}", trackingId);
            response.sendError(HttpStatus.BAD_REQUEST.value(), "Missing url parameter");
            return;
        }

        // 302 redirect to original URL
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, url);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Resolves the real client IP, respecting common reverse-proxy headers. */
    private String resolveIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Take the first (original client) IP from the chain
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
