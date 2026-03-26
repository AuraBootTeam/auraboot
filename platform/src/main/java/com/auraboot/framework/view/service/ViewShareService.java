package com.auraboot.framework.view.service;

import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Create a public share link for a view.
     */
    public Map<String, Object> createShareLink(String viewPid, String password, Integer expireHours) {
        // findViewByPid to verify existence, but use raw JSON to get config
        findViewByPid(viewPid);

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
            shareMeta.put("passwordHash", hashPassword(password));
        }
        if (expiresAt != null) {
            shareMeta.put("expiresAt", expiresAt.toString());
        }
        shareMeta.put("active", true);

        config.put("__share", shareMeta);
        // Update view with share metadata - use native JSONB cast to avoid type casting error
        savedViewMapper.updateViewConfigJson(viewPid, serializeConfig(config));

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("shareUrl", "/api/views/shared/" + token);
        result.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
        result.put("passwordProtected", password != null && !password.isBlank());
        return result;
    }

    /**
     * Revoke share link for a view.
     */
    public void revokeShareLink(String viewPid) {
        findViewByPid(viewPid); // verify exists
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
    public Map<String, Object> getShareStatus(String viewPid) {
        // Use raw JSON to preserve __share key (not mapped in ViewConfig class)
        String rawJson = savedViewMapper.selectRawViewConfigJson(viewPid);
        Map<String, Object> config = parseRawJson(rawJson);
        @SuppressWarnings("unchecked")
        Map<String, Object> share = (Map<String, Object>) config.get("__share");

        Map<String, Object> result = new HashMap<>();
        result.put("shared", share != null && Boolean.TRUE.equals(share.get("active")));
        if (share != null) {
            result.put("token", share.get("token"));
            result.put("expiresAt", share.get("expiresAt"));
            result.put("passwordProtected", share.containsKey("passwordHash"));
        }
        return result;
    }

    /**
     * Access a shared view by token (public endpoint, no tenant context).
     * Uses direct JSONB query to bypass TenantLineInterceptor and ViewConfigTypeHandler.
     * Returns Map<String, Object> to avoid MetaContext dependency on public endpoints.
     */
    public Map<String, Object> accessSharedView(String shareToken, String password) {
        // JSONB query returns raw Map — bypasses TenantLineInterceptor and ViewConfigTypeHandler
        // PostgreSQL returns column aliases in lowercase
        Map<String, Object> viewMeta = savedViewMapper.findRawViewByShareToken(shareToken);
        if (viewMeta == null || viewMeta.isEmpty()) {
            throw new RuntimeException("Share link not found");
        }

        // Parse raw view_config JSON to access __share metadata
        String viewConfigRaw = Objects.toString(viewMeta.get("viewconfigraw"), null);
        Map<String, Object> config = parseRawJson(viewConfigRaw);

        @SuppressWarnings("unchecked")
        Map<String, Object> share = (Map<String, Object>) config.get("__share");
        if (share == null || !Boolean.TRUE.equals(share.get("active"))) {
            throw new RuntimeException("Share link has been revoked");
        }

        // Check expiration
        String expiresAt = (String) share.get("expiresAt");
        if (expiresAt != null && Instant.parse(expiresAt).isBefore(Instant.now())) {
            throw new RuntimeException("Share link has expired");
        }

        // Check password
        String storedHash = (String) share.get("passwordHash");
        if (storedHash != null) {
            if (password == null || !hashPassword(password).equals(storedHash)) {
                throw new RuntimeException("Invalid password");
            }
        }

        // Return view data (without share metadata)
        Map<String, Object> result = new HashMap<>();
        result.put("name", viewMeta.get("name"));
        result.put("modelCode", viewMeta.get("modelcode"));
        result.put("viewType", viewMeta.get("viewtype"));
        Map<String, Object> cleanConfig = new HashMap<>(config);
        cleanConfig.remove("__share");
        result.put("viewConfig", cleanConfig);
        return result;
    }

    private SavedView findViewByPid(String pid) {
        SavedView view = savedViewMapper.selectOne(
                new QueryWrapper<SavedView>().eq("pid", pid));
        if (view == null) throw new RuntimeException("View not found: " + pid);
        return view;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRawJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return new HashMap<>();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawJson, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse raw view_config JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String serializeConfig(Map<String, Object> config) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(config);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String hashPassword(String password) {
        // Simple hash — in production use BCrypt
        return Integer.toHexString(password.hashCode());
    }
}
