package com.auraboot.framework.auth.service;

/**
 * Service for sending and verifying OTP codes via SMS or email.
 * <p>
 * Rate limiting rules:
 * <ul>
 *   <li>Same target: 60-second interval between sends</li>
 *   <li>Same IP: max 10 codes per hour</li>
 *   <li>Same code: max 3 failed verification attempts (then invalidated)</li>
 * </ul>
 *
 * @since 7.0.0
 */
public interface VerificationCodeService {

    /**
     * Generate and send a verification code to the target (phone or email).
     *
     * @param target    phone number or email address
     * @param type      code type: LOGIN, BIND, RESET_PASSWORD, DEACTIVATION
     * @param ipAddress requester's IP address for rate limiting
     * @throws com.auraboot.framework.exception.BusinessException on rate limit violation
     */
    void sendCode(String target, String type, String ipAddress);

    /**
     * Verify a code for the given target and type.
     *
     * @param target the phone number or email address
     * @param code   the 6-digit code to verify
     * @param type   code type: LOGIN, BIND, RESET_PASSWORD, DEACTIVATION
     * @return true if the code is valid and not expired; false otherwise
     */
    boolean verifyCode(String target, String code, String type);
}
