package com.auraboot.framework.i18n.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Detects orphan i18n keys — translation entries in ab_i18n_resource whose
 * DSL-defined source entity (model, field) no longer exists.
 *
 * <p>Scope of analysis:
 * <ul>
 *   <li>{@code model.{modelCode}.*} — orphan if {@code modelCode} not in ab_meta_model</li>
 * </ul>
 *
 * <p>Conservative approach: only {@code model.*} keys are analyzed.
 * Keys with other prefixes (action.*, admin.*, page.*, field.*) are left untouched
 * to avoid accidental deletion of system-level translations.
 *
 * @author AuraBoot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanKeyDetector {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Scans ab_i18n_resource for orphan keys in the current tenant.
     *
     * <p>Only analyzes {@code model.*} prefix keys — the largest and most volatile
     * category of i18n keys that are auto-generated from DSL model definitions.
     *
     * @param tenantId the tenant to scan
     * @return scan result containing orphan key list and summary statistics
     */
    public OrphanKeyScanResult scan(Long tenantId) {
        log.info("Scanning orphan i18n keys for tenant={}", tenantId);

        // 1. Fetch all distinct model.* keys for this tenant (zh-CN only to avoid counting each lang)
        List<String> modelKeys = jdbcTemplate.queryForList(
            "SELECT DISTINCT i18n_key FROM ab_i18n_resource " +
            "WHERE tenant_id = ? AND lang = 'zh-CN' " +
            "  AND deleted_flag = FALSE " +
            "  AND i18n_key LIKE 'model.%'",
            String.class, tenantId);

        log.debug("Found {} distinct model.* i18n keys for tenant={}", modelKeys.size(), tenantId);

        // 2. Fetch all currently valid model codes for this tenant
        Set<String> validModelCodes = new HashSet<>(jdbcTemplate.queryForList(
            "SELECT DISTINCT code FROM ab_meta_model " +
            "WHERE tenant_id = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
            String.class, tenantId));

        log.debug("Found {} valid model codes for tenant={}", validModelCodes.size(), tenantId);

        // 3. Classify each key as orphan or valid
        List<String> orphanKeys = new ArrayList<>();
        for (String key : modelKeys) {
            // key format: model.{modelCode}.{fieldCode}.label  OR  model.{modelCode}._meta.label
            // parts[0] = "model", parts[1] = modelCode
            String[] parts = key.split("\\.", 3);
            if (parts.length >= 2) {
                String modelCode = parts[1];
                if (!validModelCodes.contains(modelCode)) {
                    orphanKeys.add(key);
                }
            }
        }

        log.info("Orphan scan complete for tenant={}: scanned={}, orphans={}",
            tenantId, modelKeys.size(), orphanKeys.size());

        return new OrphanKeyScanResult(modelKeys.size(), orphanKeys.size(), orphanKeys);
    }

    /**
     * Permanently deletes orphan i18n keys across ALL locales for the given tenant.
     *
     * <p>A key deleted here removes ALL language variants (zh-CN, en-US, etc.) for
     * the same logical key, since an orphan model is gone regardless of locale.
     *
     * @param tenantId the tenant to clean up
     * @param keys     the list of orphan keys to delete (as returned by {@link #scan})
     * @return total number of rows deleted
     */
    public int deleteOrphans(Long tenantId, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }

        int deleted = 0;
        int batchSize = 100;

        for (int i = 0; i < keys.size(); i += batchSize) {
            List<String> batch = keys.subList(i, Math.min(i + batchSize, keys.size()));
            String placeholders = batch.stream().map(k -> "?").collect(Collectors.joining(", "));
            Object[] params = Stream.concat(Stream.of(tenantId), batch.stream()).toArray();

            // Delete across all locales — orphan keys are orphans in every language
            int rowsDeleted = jdbcTemplate.update(
                "DELETE FROM ab_i18n_resource WHERE tenant_id = ? AND i18n_key IN (" + placeholders + ")",
                params);

            deleted += rowsDeleted;
            log.debug("Deleted {} rows for batch of {} orphan keys", rowsDeleted, batch.size());
        }

        log.info("Deleted {} orphan i18n key rows for tenant={}", deleted, tenantId);
        return deleted;
    }

    /**
     * Result of an orphan key scan.
     *
     * @param totalScanned total number of distinct model.* keys examined
     * @param orphanCount  number of keys identified as orphans
     * @param orphanKeys   the list of orphan key strings (de-duplicated, zh-CN locale only)
     */
    public record OrphanKeyScanResult(
        int totalScanned,
        int orphanCount,
        List<String> orphanKeys
    ) {}
}
