package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.service.VerificationCodeService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Email verification code (OTP) authentication strategy.
 * <p>
 * Verifies the OTP code sent to an email address, finds the user by email,
 * and completes login. If the user does not exist, auto-registers a
 * passwordless account (Passwordless / Email OTP flow).
 *
 * @since 7.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailCodeAuthStrategy implements AuthStrategy {

    private final VerificationCodeService verificationCodeService;
    private final LoginCompletionHelper loginCompletionHelper;
    private final UserMapper userMapper;

    @Override
    public String getChannelCode() {
        return "email_code";
    }

    @Override
    public AuthenticationResponse authenticate(AuthStrategyRequest request) {
        String email = request.getEmail();
        String code = request.getCode();

        if (email == null || email.isBlank()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Email is required");
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Verification code is required");
        }

        // Verify email code
        boolean valid = verificationCodeService.verifyCode(email, code, "login");
        if (!valid) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Invalid or expired verification code");
        }

        // Find user by email, auto-register if not found
        User user = findUserByEmail(email);
        if (user == null) {
            log.info("Auto-registering new user via email OTP");
            user = autoRegisterByEmail(email);
        }

        // Check account status
        if (!user.isEnabled()) {
            throw new BusinessException(ResponseCode.AccountLocked, "Account is disabled");
        }

        // Mark email as verified
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            user.setEmailVerified(true);
            userMapper.updateById(user);
        }

        // CodeQL cannot infer the prior OTP verification gate; completeLogin is
        // reached only after VerificationCodeService accepts the email code.
        return loginCompletionHelper.completeLogin(user, request.getIpAddress(), request.getUserAgent());
    }

    private User findUserByEmail(String email) {
        QueryWrapper<User> qw = new QueryWrapper<>();
        qw.eq("email", email);
        return userMapper.selectOne(qw);
    }

    /**
     * Create a minimal user account using only an email address (no password).
     * Display name defaults to the local part of the email (before @).
     */
    private User autoRegisterByEmail(String email) {
        String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

        User user = new User();
        user.setPid(UlidGenerator.generate());
        user.setEmail(email);
        user.setUserName(email);
        user.setNickName(localPart);
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        user.setEmailVerified(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setSecurityVersion(0);
        user.setSignInCount(0);
        user.setFailedLoginAttempts(0);

        userMapper.insert(user);
        log.info("Auto-registered user id={} pid={} for email {}", user.getId(), user.getPid(), email);
        return user;
    }
}
