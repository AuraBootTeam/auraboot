package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Email + password authentication strategy.
 * <p>
 * Delegates credential verification to Spring Security's {@link AuthenticationManager},
 * handles account lock checks and login failure tracking, then hands off to
 * {@link LoginCompletionHelper} for JWT and session creation.
 *
 * @since 7.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailPasswordAuthStrategy implements AuthStrategy {

    private final AuthenticationManager authenticationManager;
    private final PasswordManagementService passwordManagementService;
    private final LoginCompletionHelper loginCompletionHelper;
    private final UserMapper userMapper;

    @Override
    public String getChannelCode() {
        return "email_password";
    }

    @Override
    public AuthenticationResponse authenticate(AuthStrategyRequest request) {
        String email = request.getEmail();
        String password = request.getPassword();

        // Pre-check: is account locked?
        User user = findUserByEmail(email);
        if (user != null && passwordManagementService.isAccountLocked(user)) {
            throw new RootUnCheckedException(ResponseCode.AccountLocked,
                    "Account is locked due to too many failed login attempts");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, password)
            );

            CustomUserDetails userDetail = (CustomUserDetails) authentication.getPrincipal();

            // Reset login failures on successful authentication
            if (user != null) {
                passwordManagementService.resetLoginFailures(user);
            }

            // Reload user entity to ensure we have full data for JWT
            if (user == null) {
                user = findUserByEmail(email);
            }

            return loginCompletionHelper.completeLogin(user, request.getIpAddress(), request.getUserAgent());

        } catch (BadCredentialsException e) {
            // Record failed login attempt
            if (user != null) {
                passwordManagementService.recordLoginFailure(user);
            }
            throw e;
        }
    }

    private User findUserByEmail(String email) {
        try {
            QueryWrapper<User> qw = new QueryWrapper<>();
            qw.eq("email", email);
            return userMapper.selectOne(qw);
        } catch (Exception e) {
            log.warn("Failed to lookup user by email: {}", e.getMessage());
            return null;
        }
    }
}
