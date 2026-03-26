package com.auraboot.framework.auth.controller;

import com.auraboot.framework.auth.dto.SendCodeRequest;
import com.auraboot.framework.auth.service.VerificationCodeService;
import com.auraboot.framework.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for sending and verifying OTP codes.
 * <p>
 * Endpoints under /api/auth/verify-code are public (no JWT required)
 * to support pre-authentication flows like login-by-code and password reset.
 *
 * @since 7.0.0
 */
@RestController
@RequestMapping("/api/auth/verify-code")
public class VerifyCodeController {

    private final VerificationCodeService verificationCodeService;

    public VerifyCodeController(VerificationCodeService verificationCodeService) {
        this.verificationCodeService = verificationCodeService;
    }

    /**
     * Send a verification code to the target (phone or email).
     */
    @PostMapping("/send")
    public ApiResponse<Void> sendCode(@Valid @RequestBody SendCodeRequest request,
                                      HttpServletRequest httpRequest) {
        String ipAddress = extractIpAddress(httpRequest);
        verificationCodeService.sendCode(request.getTarget(), request.getType(), ipAddress);
        return ApiResponse.success();
    }

    /**
     * Verify a code for the given target.
     */
    @PostMapping("/verify")
    public ApiResponse<Boolean> verifyCode(@Valid @RequestBody VerifyRequest request) {
        boolean result = verificationCodeService.verifyCode(
                request.getTarget(), request.getCode(), request.getType());
        return ApiResponse.success(result);
    }

    /**
     * Extract the real client IP from headers or remote address.
     */
    private String extractIpAddress(HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            // X-Forwarded-For may contain a chain: client, proxy1, proxy2
            return ip.split(",")[0].trim();
        }
        ip = httpRequest.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }
        return httpRequest.getRemoteAddr();
    }

    /**
     * Inline request DTO for verify endpoint.
     */
    @lombok.Data
    public static class VerifyRequest {
        @jakarta.validation.constraints.NotBlank
        private String target;
        @jakarta.validation.constraints.NotBlank
        private String code;
        @jakarta.validation.constraints.NotBlank
        private String type;
    }
}
