package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.mapper.UserSessionMapper;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.common.util.UlidGenerator;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session lifecycle on the auth plane. Every query here runs either before the
 * tenant context is bound (token validation IS how the context is established)
 * or addresses rows by token/pid/userId — {@code ab_user_session.tenant_id} is
 * a descriptive copy of the JWT claim, not a partition key. The service runs
 * under the explicit {@link MetaContext#runWithoutTenantFilter} scope so the
 * tenant-line filter can apply to {@code ab_user_session} everywhere else
 * (tenant-exemption cleanup W2a); future direct-mapper access outside this
 * service no longer silently bypasses the filter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManagementServiceImpl implements SessionManagementService {

    private final UserSessionMapper userSessionMapper;

    @Autowired(required = false)
    private JwtUtil jwtUtil;

    @Autowired(required = false)
    private AdminEventLogService adminEventLogService;

    // Throttle map: tokenHash -> lastUpdateTime (avoid DB writes on every request)
    private final ConcurrentHashMap<String, Instant> lastActiveThrottle = new ConcurrentHashMap<>();
    private static final Duration THROTTLE_DURATION = Duration.ofMinutes(5);

    @Override
    @Transactional
    public UserSession createSession(Long userId, String token, String ipAddress, String userAgent) {
        return createSessionInternal(userId, token, null, null, null, ipAddress, userAgent);
    }

    @Override
    @Transactional
    public UserSession createImpersonationSession(
            Long userId,
            String token,
            String authorizationMethod,
            String reason,
            String reference,
            String ipAddress,
            String userAgent) {
        if (jwtUtil == null || !jwtUtil.extractImpersonation(token)) {
            throw new IllegalArgumentException("A signed impersonation token is required");
        }
        return createSessionInternal(
                userId, token, authorizationMethod, reason, reference, ipAddress, userAgent);
    }

    private UserSession createSessionInternal(
            Long userId,
            String token,
            String authorizationMethod,
            String reason,
            String reference,
            String ipAddress,
            String userAgent) {
        return MetaContext.runWithoutTenantFilter(() -> {
            UserSession session = new UserSession();
            String sid = extractSidClaim(token);
            session.setPid(sid == null ? UlidGenerator.generate() : sid);
            session.setUserId(userId);
            session.setTokenHash(hashToken(token));
            populateExecutionContext(session, token);
            boolean impersonation = jwtUtil != null && jwtUtil.extractImpersonation(token);
            session.setSessionKind(impersonation ? "impersonation" : "user");
            if (impersonation) {
                session.setInitiatedByUserId(jwtUtil.extractOperatorUserId(token));
                session.setImpersonationExpiresAt(jwtUtil.extractExpiration(token).toInstant());
                session.setImpersonationAuthorizationMethod(authorizationMethod);
                session.setImpersonationReason(reason);
                session.setImpersonationReference(reference);
                session.setClientType(jwtUtil.extractClientType(token));
            }
            session.setIpAddress(ipAddress);
            session.setUserAgent(userAgent != null && userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
            session.setDeviceInfo(parseDeviceInfo(userAgent));
            session.setCreatedAt(Instant.now());
            session.setLastActiveAt(Instant.now());
            session.setRevoked(false);
            if (userSessionMapper.insertIfAbsent(session) == 0) {
                UserSession existing = sid == null ? userSessionMapper.findByTokenHash(session.getTokenHash())
                        : userSessionMapper.findByPid(sid);
                if (existing == null) {
                    throw new IllegalStateException("Session idempotency conflict did not expose the existing row");
                }
                log.debug("Session already exists for token, returning the persisted session");
                return existing;
            }
            return session;
        });
    }

    private void populateExecutionContext(UserSession session, String token) {
        if (jwtUtil == null) {
            session.setContextVersion(1L);
            session.setSessionStage("onboarding");
            return;
        }
        session.setApplicationId(jwtUtil.extractApplicationId(token));
        session.setLoginChannelId(jwtUtil.extractLoginChannelId(token));
        session.setTenantId(jwtUtil.extractTenantId(token));
        session.setTenantMemberId(jwtUtil.extractMemberId(token));
        session.setExecutionScope(jwtUtil.extractExecutionScope(token));
        session.setActorPartyId(jwtUtil.extractActorPartyId(token));
        session.setPartyMembershipId(jwtUtil.extractPartyMembershipId(token));
        session.setSessionStage(jwtUtil.extractSessionStage(token));
        session.setContextVersion(jwtUtil.extractContextVersion(token));
    }

    @Override
    public boolean isSessionValid(String token) {
        return MetaContext.runWithoutTenantFilter(() -> {
            UserSession session = findByToken(token);
            return session != null && !Boolean.TRUE.equals(session.getRevoked())
                    && (session.getImpersonationExpiresAt() == null
                        || session.getImpersonationExpiresAt().isAfter(Instant.now()));
        });
    }

    /**
     * Returns the sid claim of a platform-issued JWT, or null for opaque tokens.
     * Tokens arrive from the network and may be arbitrary strings; a parse failure
     * must fall back to the token-hash lookup, not surface as a 500 (the #1947
     * regression caught by SessionManagementIntegrationTest).
     */
    private String extractSidClaim(String token) {
        if (jwtUtil == null) {
            return null;
        }
        try {
            return jwtUtil.extractSessionId(token);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public UserSession findByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return MetaContext.runWithoutTenantFilter(() -> {
            String sid = extractSidClaim(token);
            return sid == null ? userSessionMapper.findByTokenHash(hashToken(token)) : userSessionMapper.findByPid(sid);
        });
    }

    @Override
    @Transactional
    public void revokeSession(Long userId, String sessionPid) {
        MetaContext.runWithoutTenantFilter(() -> {
            List<UserSession> sessions = userSessionMapper.findActiveByUserId(userId);
            UserSession target = sessions.stream()
                    .filter(s -> s.getPid().equals(sessionPid))
                    .findFirst()
                    .orElseThrow(() -> new RootUnCheckedException(ResponseCode.NOT_FOUND, "Session not found"));
            userSessionMapper.revokeSession(target.getId());
            log.info("Session {} revoked for user {}", sessionPid, userId);
        });
    }

    @Override
    @Transactional
    public void revokeSessionByToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        MetaContext.runWithoutTenantFilter(() -> {
            UserSession session = findByToken(token);
            if (session == null || Boolean.TRUE.equals(session.getRevoked())) {
                return;
            }
            userSessionMapper.revokeSession(session.getId());
            lastActiveThrottle.remove(hashToken(token));
            log.info("Session {} revoked by current token", session.getPid());
        });
    }

    @Override
    @Transactional
    public void revokeAllSessions(Long userId) {
        MetaContext.runWithoutTenantFilter(() -> {
            int count = userSessionMapper.revokeAllSessions(userId);
            log.info("Revoked {} sessions for user {}", count, userId);
        });
    }

    @Override
    public List<UserSession> getActiveSessions(Long userId) {
        return MetaContext.runWithoutTenantFilter(() -> userSessionMapper.findActiveByUserId(userId));
    }

    @Override
    public void updateLastActive(String token) {
        String hash = hashToken(token);
        Instant now = Instant.now();

        // Throttle: only update if last update was more than 5 minutes ago
        Instant lastUpdate = lastActiveThrottle.get(hash);
        if (lastUpdate != null && lastUpdate.plus(THROTTLE_DURATION).isAfter(now)) {
            return;
        }

        MetaContext.runWithoutTenantFilter(() -> {
            UserSession session = findByToken(token);
            if (session != null) {
                userSessionMapper.updateLastActive(session.getId());
                lastActiveThrottle.put(hash, now);
            }
        });
    }

    /**
     * Periodically clean up expired entries from the throttle map to prevent unbounded growth.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedRate = 600_000)
    public void cleanUpThrottleMap() {
        Instant cutoff = Instant.now().minus(THROTTLE_DURATION);
        int before = lastActiveThrottle.size();
        lastActiveThrottle.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        int removed = before - lastActiveThrottle.size();
        if (removed > 0) {
            log.debug("Cleaned up {} expired entries from lastActiveThrottle", removed);
        }
    }

    /** Revokes expired delegated sessions and writes one terminal audit event exactly once. */
    @Scheduled(fixedDelayString = "${security.impersonation.expiry-scan-ms:60000}")
    @Transactional
    public void expireImpersonationSessions() {
        // Cross-tenant system sweep on a scheduler thread: without a caller context the
        // tenant-line interceptor cannot resolve a tenant (it throws), so this runs under
        // the explicit bypass like the other pre-context seams in this class.
        MetaContext.runWithoutTenantFilter(() -> {
            expireImpersonationSessionsSweep();
            return null;
        });
    }

    private void expireImpersonationSessionsSweep() {
        for (UserSession session : userSessionMapper.findExpiredImpersonationSessions()) {
            if (userSessionMapper.revokeExpiredSession(session.getId()) != 1) {
                continue;
            }
            if (adminEventLogService != null) {
                adminEventLogService.record(AdminEventLog.builder()
                        .tenantId(session.getTenantId())
                        .actorUserId(session.getInitiatedByUserId())
                        .actorType("user")
                        .actionType("impersonation.expired")
                        .resourceType("user_session")
                        .resourcePid(session.getPid())
                        .success(true)
                        .reason("Delegated customer session reached its fixed expiry")
                        .build());
            }
        }
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String parseDeviceInfo(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        String ua = userAgent.toLowerCase();
        // Check tablet before mobile — many tablets also contain "mobile"
        if (ua.contains("ipad") || ua.contains("tablet") || (ua.contains("android") && !ua.contains("mobile"))) {
            return "Tablet";
        }
        if (ua.contains("mobile") || ua.contains("iphone") || ua.contains("ipod") || ua.contains("android")) {
            return "Mobile";
        }
        return "Desktop";
    }
}
