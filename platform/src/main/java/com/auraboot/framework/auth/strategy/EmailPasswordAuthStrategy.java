package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
@Component
@RequiredArgsConstructor
public class EmailPasswordAuthStrategy implements AuthStrategy {

    private final AuthenticationManager authenticationManager;
    private final PasswordManagementService passwordManagementService;
    private final LoginCompletionHelper loginCompletionHelper;
    private final UserService userService;

    @Override
    public String getChannelCode() {
        return "email_password";
    }

    @Override
    public AuthenticationResponse authenticate(AuthStrategyRequest request) {
        String identifier = request.resolveIdentifier();
        String password = request.getPassword();

        // Pre-check: is account locked?
        User user = findUserByIdentifier(identifier);
        if (user != null && passwordManagementService.isAccountLocked(user)) {
            throw new RootUnCheckedException(ResponseCode.AccountLocked,
                    "Account is locked due to too many failed login attempts");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(identifier, password)
            );

            if (user == null) {
                user = findUserByIdentifier(identifier);
            }
            if (user == null) {
                throw new BadCredentialsException("Authenticated user could not be resolved");
            }
            passwordManagementService.resetLoginFailures(user);

            return loginCompletionHelper.completeLogin(user, request.getIpAddress(), request.getUserAgent());

        } catch (BadCredentialsException e) {
            // Record failed login attempt
            if (user != null) {
                passwordManagementService.recordLoginFailure(user);
            }
            throw e;
        }
    }

    private User findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        // Use the same normalized email lookup as Spring Security and account creation.
        User byEmail = userService.findByEmail(identifier.trim());
        return byEmail != null ? byEmail : userService.findByUserName(identifier.trim());
    }
}
