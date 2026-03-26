package com.auraboot.framework.saas.fingerprint;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Instance Fingerprint Service — generates and caches the instance fingerprint.
 *
 * <p>Fingerprint = SHA-256(instance_url + db_uuid)
 *
 * <p>Used for:
 * <ul>
 *   <li>License binding — Pro/Enterprise licenses are tied to a specific instance</li>
 *   <li>Marketplace authentication — instance identity for plugin purchases</li>
 * </ul>
 *
 * <p>If fingerprint in license JWT doesn't match local fingerprint → degrade to Community.
 *
 * @since 7.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceFingerprintService {

    private final SystemConfigService systemConfigService;

    private volatile String cachedFingerprint;

    /**
     * Get the instance fingerprint. Cached after first computation.
     *
     * @return SHA-256 hex string, or null if db_uuid/instance_url not yet initialized
     */
    public String getFingerprint() {
        if (cachedFingerprint != null) return cachedFingerprint;

        String dbUuid = systemConfigService.get(SystemConfigKeys.SYSTEM_DB_UUID).orElse(null);
        String instanceUrl = systemConfigService.get(SystemConfigKeys.SYSTEM_INSTANCE_URL).orElse(null);

        if (dbUuid == null || dbUuid.isBlank()) {
            log.debug("db_uuid not initialized — fingerprint unavailable");
            return null;
        }
        if (instanceUrl == null || instanceUrl.isBlank()) {
            log.debug("instance_url not initialized — fingerprint unavailable");
            return null;
        }

        cachedFingerprint = computeFingerprint(instanceUrl, dbUuid);
        log.info("Instance fingerprint computed: {}", cachedFingerprint);
        return cachedFingerprint;
    }

    /**
     * Get the db_uuid (stable across URL changes).
     */
    public String getDbUuid() {
        return systemConfigService.get(SystemConfigKeys.SYSTEM_DB_UUID).orElse(null);
    }

    /**
     * Get the instance URL.
     */
    public String getInstanceUrl() {
        return systemConfigService.get(SystemConfigKeys.SYSTEM_INSTANCE_URL).orElse(null);
    }

    /**
     * Invalidate cached fingerprint (e.g. after instance_url change).
     */
    public void invalidateCache() {
        cachedFingerprint = null;
    }

    /**
     * Check if a license fingerprint matches this instance.
     *
     * @param licenseFingerprint fingerprint from license JWT claims
     * @return true if matches or if local fingerprint is unavailable (graceful)
     */
    public boolean matches(String licenseFingerprint) {
        if (licenseFingerprint == null || licenseFingerprint.isBlank()) {
            return true; // no fingerprint in license — Community edition, skip check
        }

        String local = getFingerprint();
        if (local == null) {
            log.warn("Local fingerprint unavailable — skipping validation");
            return true; // graceful: don't block if system not fully initialized
        }

        boolean match = local.equals(licenseFingerprint);
        if (!match) {
            log.warn("License fingerprint mismatch — license={}, local={}", licenseFingerprint, local);
        }
        return match;
    }

    static String computeFingerprint(String instanceUrl, String dbUuid) {
        try {
            String input = instanceUrl + dbUuid;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
