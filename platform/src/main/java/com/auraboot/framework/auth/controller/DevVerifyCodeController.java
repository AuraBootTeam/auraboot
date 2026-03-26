package com.auraboot.framework.auth.controller;

import com.auraboot.framework.auth.entity.VerificationCode;
import com.auraboot.framework.auth.mapper.VerificationCodeMapper;
import com.auraboot.framework.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dev-only controller for querying verification codes during testing.
 * <p>
 * This controller is restricted to dev/local profiles and will NOT be loaded in production.
 * The endpoint is under /api/auth/verify-code/**, which is already in the security whitelist
 * (no JWT required).
 *
 * @since 7.0.0
 */
@RestController
@RequestMapping("/api/auth/verify-code/dev")
@Profile({"dev", "local"})
public class DevVerifyCodeController {

    private static final Logger log = LoggerFactory.getLogger(DevVerifyCodeController.class);

    private final VerificationCodeMapper verificationCodeMapper;

    public DevVerifyCodeController(VerificationCodeMapper verificationCodeMapper) {
        this.verificationCodeMapper = verificationCodeMapper;
    }

    /**
     * Retrieve the latest unused verification code for a given target (email or phone).
     * <p>
     * Example: GET /api/auth/verify-code/dev/latest?target=user@example.com
     *
     * @param target the email or phone number to look up
     * @return the code value, or null if no unused code exists
     */
    @GetMapping("/latest")
    public ApiResponse<Map<String, Object>> getLatestCode(@RequestParam String target) {
        log.info("[DEV] Querying latest verification code for target: {}", target);

        VerificationCode code = verificationCodeMapper.findLatestByTarget(target);
        if (code == null) {
            log.info("[DEV] No unused verification code found for target: {}", target);
            return ApiResponse.success(Map.of("code", "", "found", false));
        }

        log.info("[DEV] Found verification code for target: {}, type: {}, createdAt: {}",
                target, code.getType(), code.getCreatedAt());

        return ApiResponse.success(Map.of(
                "code", code.getCode(),
                "type", code.getType(),
                "expiresAt", code.getExpiresAt().toString(),
                "found", true
        ));
    }
}
