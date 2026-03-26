package com.auraboot.framework.auth.dto;

import lombok.Data;

/**
 * Unified request DTO for multi-channel authentication.
 * <p>
 * Different channels use different field subsets:
 * <ul>
 *   <li>EMAIL_PASSWORD: email + password</li>
 *   <li>SMS: mobile + code</li>
 *   <li>EMAIL_CODE: email + code</li>
 * </ul>
 *
 * @since 7.0.0
 */
@Data
public class AuthStrategyRequest {

    /** Email address (used by EMAIL_PASSWORD and EMAIL_CODE channels) */
    private String email;

    /** Password (used by EMAIL_PASSWORD channel) */
    private String password;

    /** Mobile phone number (used by SMS channel) */
    private String mobile;

    /** Verification code (used by SMS and EMAIL_CODE channels) */
    private String code;

    /** Login channel identifier: EMAIL_PASSWORD | SMS | EMAIL_CODE */
    private String channelCode;

    /** Client IP address (populated by controller) */
    private String ipAddress;

    /** Client User-Agent header (populated by controller) */
    private String userAgent;
}
