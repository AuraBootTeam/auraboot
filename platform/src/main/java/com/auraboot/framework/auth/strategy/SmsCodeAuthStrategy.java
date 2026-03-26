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
 * SMS verification code authentication strategy.
 * <p>
 * Verifies the OTP code sent to a mobile number, finds the user by mobile,
 * and if the user doesn't exist but the code is valid, auto-registers a
 * passwordless account. Then delegates to {@link LoginCompletionHelper}.
 *
 * @since 7.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsCodeAuthStrategy implements AuthStrategy {

    private final VerificationCodeService verificationCodeService;
    private final LoginCompletionHelper loginCompletionHelper;
    private final UserMapper userMapper;

    @Override
    public String getChannelCode() {
        return "sms";
    }

    @Override
    public AuthenticationResponse authenticate(AuthStrategyRequest request) {
        String mobile = request.getMobile();
        String code = request.getCode();

        if (mobile == null || mobile.isBlank()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Mobile number is required");
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Verification code is required");
        }

        // Verify SMS code
        boolean valid = verificationCodeService.verifyCode(mobile, code, "login");
        if (!valid) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Invalid or expired verification code");
        }

        // Find user by mobile
        User user = findUserByMobile(mobile);

        // Auto-register if user not found
        if (user == null) {
            log.info("Auto-registering new user with mobile: {}", mobile);
            user = autoRegisterByMobile(mobile);
        }

        // Check account status
        if (!user.isEnabled()) {
            throw new BusinessException(ResponseCode.AccountLocked, "Account is disabled");
        }

        // Mark phone as verified
        if (!Boolean.TRUE.equals(user.getPhoneVerified())) {
            user.setPhoneVerified(true);
            userMapper.updateById(user);
        }

        return loginCompletionHelper.completeLogin(user, request.getIpAddress(), request.getUserAgent());
    }

    private User findUserByMobile(String mobile) {
        QueryWrapper<User> qw = new QueryWrapper<>();
        qw.eq("mobile", mobile);
        return userMapper.selectOne(qw);
    }

    /**
     * Create a minimal user account using only a mobile number (no password).
     */
    private User autoRegisterByMobile(String mobile) {
        User user = new User();
        user.setPid(UlidGenerator.generate());
        user.setMobile(mobile);
        user.setUserName(mobile);
        user.setNickName(mobile);
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        user.setPhoneVerified(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setSecurityVersion(0);
        user.setSignInCount(0);
        user.setFailedLoginAttempts(0);

        userMapper.insert(user);
        log.info("Auto-registered user id={} pid={} for mobile {}", user.getId(), user.getPid(), mobile);
        return user;
    }
}
