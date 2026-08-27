package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.TokenRenewResponse;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.service.SessionRenewalService;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRenewalServiceImpl implements SessionRenewalService {

    private final SessionManagementService sessionManagementService;
    private final LoginCompletionHelper loginCompletionHelper;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    @Value("${security.session.renew-window-seconds:604800}")
    private long renewWindowSeconds;

    @Override
    public TokenRenewResponse renew(String bearerToken, String ipAddress, String userAgent) {
        UserSession session = sessionManagementService.findByToken(bearerToken);
        if (session == null || Boolean.TRUE.equals(session.getRevoked())) {
            log.warn("Session renewal rejected: session missing or revoked");
            throw new BusinessException(ResponseCode.Unauthorized, "Session is no longer valid, please login again");
        }

        Instant now = Instant.now();
        Instant createdAt = session.getCreatedAt();
        if (createdAt != null && createdAt.plus(Duration.ofSeconds(renewWindowSeconds)).isBefore(now)) {
            log.info("Session renewal rejected: outside {}s renewal window", renewWindowSeconds);
            throw new BusinessException(ResponseCode.Unauthorized, "Session has expired, please login again");
        }

        String userPid = jwtUtil.extractIdentifier(bearerToken);
        User user = userPid == null ? null : userService.findByPid(userPid);
        if (user == null || !user.isEnabled()) {
            log.warn("Session renewal rejected: user {} no longer usable", userPid);
            throw new BusinessException(ResponseCode.Unauthorized, "User is no longer active, please login again");
        }

        // Re-run the shared login-completion pipeline: re-resolves tenant/member
        // context, re-checks tenant suspension and security version, mints a fresh
        // token and creates the new session record.
        AuthenticationResponse auth = loginCompletionHelper.completeLogin(user, ipAddress, userAgent);
        if (!sessionManagementService.isSessionValid(auth.getJwt())) {
            log.warn("Session renewal failed: renewed token has no valid session record");
            throw new BusinessException(ResponseCode.Unauthorized, "Session renewal failed, please login again");
        }

        // Deliberately keep the old session row active: the renewed cookie is a
        // best-effort header that can be dropped by an intermediate redirect, so
        // the previous token must keep working until its natural JWT expiry. The
        // new token has its own session row; old rows expire with the token and
        // remain covered by revokeAllSessions / password security-version checks.

        Long expiresAt = jwtUtil.extractExpiration(auth.getJwt()).toInstant().getEpochSecond();
        log.info("Session renewed for user {}", userPid);
        return new TokenRenewResponse(auth.getJwt(), expiresAt);
    }
}
