package com.auraboot.framework.view.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.DataNotFoundException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.auraboot.framework.view.dto.ViewShareResponse;
import com.auraboot.framework.view.dto.ViewShareStatusResponse;
import com.auraboot.framework.view.dto.PublicSharedViewResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

/**
 * View Share Service (GAP-121)
 *
 * Manages public share links for SavedViews.
 * Share tokens are stored in the view's viewConfig JSONB as "__share" metadata.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewShareService {

    private final SavedViewMapper savedViewMapper;
    private final SavedViewService savedViewService;
    private final PasswordEncoder passwordEncoder;

    /**
     * Create a public share link for a view.
     */
    public ViewShareResponse createShareLink(String viewPid, String password, Integer expireHours) {
        savedViewService.checkPublicShareAccess(viewPid);

        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = expireHours != null
                ? Instant.now().plus(expireHours, ChronoUnit.HOURS)
                : null;

        // Use raw JSON to preserve existing config keys not mapped in ViewConfig class
        String rawJson = savedViewMapper.selectRawViewConfigJson(viewPid);
        Map<String, Object> config = parseRawJson(rawJson);

        Map<String, Object> shareMeta = new HashMap<>();
        shareMeta.put("token", token);
        shareMeta.put("createdAt", Instant.now().toString());
        if (password != null && !password.isBlank()) {
            shareMeta.put("passwordHash", passwordEncoder.encode(password));
        }
        if (expiresAt != null) {
            shareMeta.put("expiresAt", expiresAt.toString());
        }
        shareMeta.put("active", true);

        config.put("__share", shareMeta);
        // Update view with share metadata - use native JSONB cast to avoid type casting error
        savedViewMapper.updateViewConfigJson(viewPid, serializeConfig(config));

        return new ViewShareResponse(token, "/api/views/shared/" + token,
                expiresAt != null ? expiresAt.toString() : null,
                password != null && !password.isBlank());
    }

    /**
     * Revoke share link for a view.
     */
    public void revokeShareLink(String viewPid) {
        savedViewService.checkPublicShareAccess(viewPid);
        String rawJson = savedViewMapper.selectRawViewConfigJson(viewPid);
        Map<String, Object> config = parseRawJson(rawJson);
        config.remove("__share");
        savedViewMapper.updateViewConfigJson(viewPid, serializeConfig(config));
        log.info("Revoked share link for view {}", viewPid);
    }

    /**
     * Get share status.
     * Uses raw JSON query to avoid losing __share metadata during ViewConfig deserialization.
     */
    public ViewShareStatusResponse getShareStatus(String viewPid) {
        savedViewService.checkPublicShareAccess(viewPid);
        // Use raw JSON to preserve __share key (not mapped in ViewConfig class)
        String rawJson = savedViewMapper.selectRawViewConfigJson(viewPid);
        Map<String, Object> config = parseRawJson(rawJson);
        @SuppressWarnings("unchecked")
        Map<String, Object> share = (Map<String, Object>) config.get("__share");

        return new ViewShareStatusResponse(
                share != null && Boolean.TRUE.equals(share.get("active")),
                share != null ? String.class.cast(share.get("token")) : null,
                share != null ? String.class.cast(share.get("expiresAt")) : null,
                share != null ? share.containsKey("passwordHash") : null);
    }

    /**
     * Access a shared view by token (public endpoint, no tenant context).
     * Uses direct JSONB query to bypass TenantLineInterceptor and ViewConfigTypeHandler.
     * Returns a configuration DTO without resolving tenant-scoped business records.
     */
    public PublicSharedViewResponse accessSharedView(String shareToken, String password) {
        // JSONB query returns raw Map — bypasses TenantLineInterceptor and ViewConfigTypeHandler
        // PostgreSQL returns column aliases in lowercase
        Map<String, Object> viewMeta = savedViewMapper.findRawViewByShareToken(shareToken);
        if (viewMeta == null || viewMeta.isEmpty()) {
            throw new DataNotFoundException(ResponseCode.NOT_FOUND, "Share link not found");
        }

        // Parse raw view_config JSON to access __share metadata
        String viewConfigRaw = Objects.toString(viewMeta.get("viewconfigraw"), null);
        Map<String, Object> config = parseRawJson(viewConfigRaw);

        @SuppressWarnings("unchecked")
        Map<String, Object> share = (Map<String, Object>) config.get("__share");
        if (share == null || !Boolean.TRUE.equals(share.get("active"))) {
            throw new ValidationException(ResponseCode.BadParam, "Share link has been revoked");
        }

        // Check expiration
        String expiresAt = (String) share.get("expiresAt");
        if (expiresAt != null && Instant.parse(expiresAt).isBefore(Instant.now())) {
            throw new ValidationException(ResponseCode.BadParam, "Share link has expired");
        }

        // Check password
        String storedHash = (String) share.get("passwordHash");
        if (storedHash != null) {
            if (password == null || !passwordMatches(password, storedHash)) {
                throw new ValidationException(ResponseCode.BadParam, "Invalid password");
            }
        }

        // Return view data (without share metadata)
        Map<String, Object> cleanConfig = new HashMap<>(config);
        cleanConfig.remove("__share");
        return new PublicSharedViewResponse(
                String.class.cast(viewMeta.get("name")),
                String.class.cast(viewMeta.get("modelcode")),
                String.class.cast(viewMeta.get("viewtype")), cleanConfig);
    }

    private Map<String, Object> parseRawJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return new HashMap<>();
        try {
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    rawJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (parsed == null) {
                throw new IllegalStateException("Saved view configuration must be a JSON object");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Saved view configuration is not valid JSON", e);
        }
    }

    private String serializeConfig(Map<String, Object> config) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Saved view configuration cannot be serialized", e);
        }
    }

    private boolean passwordMatches(String rawPassword, String storedHash) {
        if (storedHash.startsWith("$2")) {
            return passwordEncoder.matches(rawPassword, storedHash);
        }
        // Shares created before BCrypt stored Integer.toHexString(hashCode()); keep verifying
        // them — this public endpoint stays read-only, so no in-place rewrite. Recreating the
        // share link rotates the stored hash to BCrypt.
        return legacyHash(rawPassword).equals(storedHash);
    }

    private String legacyHash(String password) {
        return Integer.toHexString(password.hashCode());
    }
}
