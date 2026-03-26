package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.mapper.UserSessionMapper;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManagementServiceImpl implements SessionManagementService {

    private final UserSessionMapper userSessionMapper;

    // Throttle map: tokenHash -> lastUpdateTime (avoid DB writes on every request)
    private final ConcurrentHashMap<String, Instant> lastActiveThrottle = new ConcurrentHashMap<>();
    private static final Duration THROTTLE_DURATION = Duration.ofMinutes(5);

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public UserSession createSession(Long userId, String token, String ipAddress, String userAgent) {
        UserSession session = new UserSession();
        session.setPid(UlidGenerator.generate());
        session.setUserId(userId);
        session.setTokenHash(hashToken(token));
        session.setIpAddress(ipAddress);
        session.setUserAgent(userAgent != null && userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
        session.setDeviceInfo(parseDeviceInfo(userAgent));
        session.setCreatedAt(Instant.now());
        session.setLastActiveAt(Instant.now());
        session.setRevoked(false);
        try {
            userSessionMapper.insert(session);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("Session already exists for token, skipping: {}", e.getMessage());
        }
        return session;
    }

    @Override
    public boolean isSessionValid(String token) {
        String hash = hashToken(token);
        UserSession session = userSessionMapper.findByTokenHash(hash);
        return session != null && !Boolean.TRUE.equals(session.getRevoked());
    }

    @Override
    @Transactional
    public void revokeSession(Long userId, String sessionPid) {
        List<UserSession> sessions = userSessionMapper.findActiveByUserId(userId);
        UserSession target = sessions.stream()
                .filter(s -> s.getPid().equals(sessionPid))
                .findFirst()
                .orElseThrow(() -> new RootUnCheckedException(ResponseCode.NOT_FOUND, "Session not found"));
        userSessionMapper.revokeSession(target.getId());
        log.info("Session {} revoked for user {}", sessionPid, userId);
    }

    @Override
    @Transactional
    public void revokeAllSessions(Long userId) {
        int count = userSessionMapper.revokeAllSessions(userId);
        log.info("Revoked {} sessions for user {}", count, userId);
    }

    @Override
    public List<UserSession> getActiveSessions(Long userId) {
        return userSessionMapper.findActiveByUserId(userId);
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

        UserSession session = userSessionMapper.findByTokenHash(hash);
        if (session != null) {
            userSessionMapper.updateLastActive(session.getId());
            lastActiveThrottle.put(hash, now);
        }
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
