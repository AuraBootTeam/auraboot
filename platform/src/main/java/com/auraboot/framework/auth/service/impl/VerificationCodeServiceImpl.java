package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.VerificationCode;
import com.auraboot.framework.auth.mapper.VerificationCodeMapper;
import com.auraboot.framework.auth.service.VerificationCodeService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.notification.service.EmailSender;
import com.auraboot.framework.notification.sms.SmsSendResult;
import com.auraboot.framework.notification.sms.SmsSenderRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Implementation of {@link VerificationCodeService} with rate limiting and
 * multi-channel delivery (SMS for phone numbers, email for email addresses).
 *
 * @since 7.0.0
 */
@Slf4j
@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    /** Minimum interval between sends to the same target */
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);

    /** Code validity duration */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    /** Max codes per IP per hour */
    private static final int MAX_IP_CODES_PER_HOUR = 10;

    /** Max failed verification attempts per code */
    private static final int MAX_ATTEMPTS = 3;

    /** Phone number pattern: starts with + or all digits with length 10-15 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{10,15}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationCodeMapper verificationCodeMapper;
    private final SmsSenderRouter smsSenderRouter;
    private final EmailSender emailSender;

    public VerificationCodeServiceImpl(VerificationCodeMapper verificationCodeMapper,
                                       SmsSenderRouter smsSenderRouter,
                                       EmailSender emailSender) {
        this.verificationCodeMapper = verificationCodeMapper;
        this.smsSenderRouter = smsSenderRouter;
        this.emailSender = emailSender;
    }

    @Override
    @Transactional
    public void sendCode(String target, String type, String ipAddress) {
        Instant now = Instant.now();

        // Rate limit: same target cannot receive codes within 60 seconds
        VerificationCode latest = verificationCodeMapper.findLatestUnverified(target, type);
        if (latest != null && latest.getCreatedAt() != null) {
            Duration elapsed = Duration.between(latest.getCreatedAt(), now);
            if (elapsed.compareTo(SEND_INTERVAL) < 0) {
                long remainingSeconds = SEND_INTERVAL.minus(elapsed).getSeconds();
                throw new BusinessException("Please wait " + remainingSeconds + " seconds before requesting a new code");
            }
        }

        // Rate limit: same IP cannot send more than 10 codes per hour
        if (ipAddress != null) {
            int ipCount = verificationCodeMapper.countByIpInLastHour(ipAddress);
            if (ipCount >= MAX_IP_CODES_PER_HOUR) {
                throw new BusinessException("Too many verification code requests. Please try again later");
            }
        }

        // Generate 6-digit code
        String code = generateCode();

        // Persist the code
        VerificationCode entity = new VerificationCode();
        entity.setTarget(target);
        entity.setCode(code);
        entity.setType(type);
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plus(CODE_TTL));
        entity.setVerified(false);
        entity.setAttempts(0);
        entity.setIpAddress(ipAddress);
        verificationCodeMapper.insert(entity);

        // Send via appropriate channel
        if (isPhoneNumber(target)) {
            sendSms(target, code);
        } else {
            sendEmail(target, code, type);
        }

        log.info("Verification code sent to {} (type={})", maskTarget(target), type);
    }

    @Override
    @Transactional
    public boolean verifyCode(String target, String code, String type) {
        VerificationCode latest = verificationCodeMapper.findLatestUnverified(target, type);
        if (latest == null) {
            log.debug("No unverified code found for target={}, type={}", maskTarget(target), type);
            return false;
        }

        // Check if code is expired
        if (Instant.now().isAfter(latest.getExpiresAt())) {
            log.debug("Code expired for target={}, type={}", maskTarget(target), type);
            return false;
        }

        // Check max attempts
        if (latest.getAttempts() != null && latest.getAttempts() >= MAX_ATTEMPTS) {
            log.debug("Code max attempts exceeded for target={}, type={}", maskTarget(target), type);
            // Mark as expired by setting expiresAt to now
            latest.setExpiresAt(Instant.now());
            verificationCodeMapper.updateById(latest);
            return false;
        }

        // Increment attempts
        latest.setAttempts(latest.getAttempts() == null ? 1 : latest.getAttempts() + 1);

        if (code.equals(latest.getCode())) {
            // Success: mark as verified
            latest.setVerified(true);
            verificationCodeMapper.updateById(latest);
            log.info("Verification code verified for target={}, type={}", maskTarget(target), type);
            return true;
        } else {
            // Failed attempt
            verificationCodeMapper.updateById(latest);
            log.debug("Invalid code attempt ({}/{}) for target={}, type={}",
                    latest.getAttempts(), MAX_ATTEMPTS, maskTarget(target), type);
            return false;
        }
    }

    /**
     * Generate a random 6-digit code.
     */
    private String generateCode() {
        int code = RANDOM.nextInt(900000) + 100000; // 100000 ~ 999999
        return String.valueOf(code);
    }

    /**
     * Determine if the target looks like a phone number.
     * Matches: starts with + or all digits with length 10-15.
     */
    private boolean isPhoneNumber(String target) {
        return target != null && PHONE_PATTERN.matcher(target).matches();
    }

    /**
     * Send verification code via SMS.
     */
    private void sendSms(String phone, String code) {
        SmsSendResult result = smsSenderRouter.send(
                phone,
                "verification_code",
                Map.of("code", code)
        );
        if (!result.isSuccess()) {
            log.error("Failed to send SMS to {}: {}", maskTarget(phone), result.getErrorMessage());
            throw new BusinessException("Failed to send verification code via SMS");
        }
    }

    /**
     * Send verification code via email.
     */
    private void sendEmail(String email, String code, String type) {
        String subject = "Your Verification Code";
        String htmlBody = buildEmailHtml(code, type);
        emailSender.send(email, subject, htmlBody);
    }

    /**
     * Build a simple HTML email body for the verification code.
     */
    private String buildEmailHtml(String code, String type) {
        return """
                <div style="font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto; padding: 24px;">
                    <h2 style="color: #333;">Verification Code</h2>
                    <p style="color: #555; font-size: 14px;">Your verification code is:</p>
                    <div style="background: #f5f5f5; border-radius: 8px; padding: 16px; text-align: center; margin: 16px 0;">
                        <span style="font-size: 32px; font-weight: bold; letter-spacing: 8px; color: #1a1a1a;">%s</span>
                    </div>
                    <p style="color: #999; font-size: 12px;">
                        This code will expire in 5 minutes. Do not share it with anyone.
                    </p>
                </div>
                """.formatted(code);
    }

    /**
     * Mask the target for logging (privacy).
     */
    private String maskTarget(String target) {
        if (target == null || target.length() < 4) {
            return "***";
        }
        if (target.contains("@")) {
            // Email: show first 2 chars + ***@domain
            int atIndex = target.indexOf('@');
            String local = target.substring(0, Math.min(2, atIndex));
            return local + "***" + target.substring(atIndex);
        }
        // Phone: show first 3 + ****  + last 4
        if (target.length() >= 7) {
            return target.substring(0, 3) + "****" + target.substring(target.length() - 4);
        }
        return target.substring(0, 2) + "***";
    }
}
