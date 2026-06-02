package com.auraboot.framework.i18n.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Audits the i18n multi-layer override chain for observability.
 *
 * <p>i18n values resolve through YAML (low) → compiled JSON (mid) → DB (wins),
 * and {@code seed/i18n-base.json} is loaded once into the DB at bootstrap.
 * This auditor makes the override visible so "I edited it but nothing changed"
 * can be answered immediately: it reports, per key, which layers hold a value,
 * which one wins, and whether the DB has drifted from the seed file.
 *
 * <p>Pure observability — never mutates data, never changes the override chain.
 * Orthogonal to {@link OrphanKeyDetector} (which handles {@code model.*} DSL
 * orphans whose source entity was deleted).
 *
 * @author AuraBoot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nOverrideAuditor {

    public static final String LAYER_YAML = "yaml";
    public static final String LAYER_JSON = "json";
    public static final String LAYER_DB = "db";

    public static final String CLASS_CONSISTENT = "CONSISTENT";
    public static final String CLASS_OVERRIDDEN = "OVERRIDDEN";
    public static final String CLASS_SEED_DRIFT = "SEED_DRIFT";

    /** Platform preset scope: seed rows are written at tenant_id = 0. */
    private static final long SYSTEM_TENANT_ID = 0L;

    private final I18nService i18nService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Audit a single locale's override chain.
     *
     * @param lang locale code, e.g. {@code zh-CN}
     * @return structured report with per-key classification
     */
    public OverrideAuditReport audit(String lang) {
        Map<String, String> yaml = stringify(safe(i18nService.loadFromYaml(lang)));
        Map<String, String> json = stringify(safe(i18nService.loadFromJson(lang)));
        Map<String, String> db = loadDb(lang);
        Map<String, String> seed = loadSeed(lang);
        Map<String, String> dbSystemSource = loadDbSystemSources();
        return classify(lang, yaml, json, db, seed, dbSystemSource);
    }

    /**
     * Pure classification over the five already-loaded layers. Package-private so
     * it can be unit-tested without DB / classpath wiring.
     *
     * @param dbSystemKeys keys whose DB row is platform-owned and un-edited
     *                     ({@code source='system' AND updated_by IS NULL});
     *                     only these are eligible for {@code SEED_DRIFT}.
     */
    OverrideAuditReport classify(String lang,
                                 Map<String, String> yaml,
                                 Map<String, String> json,
                                 Map<String, String> db,
                                 Map<String, String> seed,
                                 Map<String, String> dbSystemKeys) {
        TreeSet<String> allKeys = new TreeSet<>();
        allKeys.addAll(yaml.keySet());
        allKeys.addAll(json.keySet());
        allKeys.addAll(db.keySet());

        List<OverrideAuditEntry> entries = new ArrayList<>();
        int overriddenCount = 0;
        int driftCount = 0;

        for (String key : allKeys) {
            String yv = yaml.get(key);
            String jv = json.get(key);
            String dv = db.get(key);
            String sv = seed.get(key);

            // Winner: DB > JSON > YAML
            String winnerLayer;
            String winnerValue;
            if (dv != null) {
                winnerLayer = LAYER_DB;
                winnerValue = dv;
            } else if (jv != null) {
                winnerLayer = LAYER_JSON;
                winnerValue = jv;
            } else {
                winnerLayer = LAYER_YAML;
                winnerValue = yv;
            }

            int layerCount = (yv != null ? 1 : 0) + (jv != null ? 1 : 0) + (dv != null ? 1 : 0);

            // Seed drift: seed has a value, DB has a value, they differ, and the
            // DB row for this key is platform-owned (source='system'). This is the
            // "edited the seed but DB is stale" smell.
            boolean isDrift = sv != null && dv != null
                && !Objects.equals(sv, dv)
                && "system".equals(dbSystemKeys.get(key));

            String classification;
            if (isDrift) {
                classification = CLASS_SEED_DRIFT;
                driftCount++;
            } else if (layerCount >= 2) {
                boolean allEqual = valuesEqualAcrossPresentLayers(yv, jv, dv);
                classification = allEqual ? CLASS_CONSISTENT : CLASS_OVERRIDDEN;
                if (!allEqual) {
                    overriddenCount++;
                }
            } else {
                classification = null; // single-layer, nothing notable
            }

            entries.add(new OverrideAuditEntry(
                key, lang, yv, jv, dv, sv, winnerLayer, winnerValue, classification));
        }

        return new OverrideAuditReport(lang, allKeys.size(), overriddenCount, driftCount, entries);
    }

    private boolean valuesEqualAcrossPresentLayers(String yv, String jv, String dv) {
        String ref = null;
        for (String v : new String[] {yv, jv, dv}) {
            if (v == null) continue;
            if (ref == null) {
                ref = v;
            } else if (!ref.equals(v)) {
                return false;
            }
        }
        return true;
    }

    /**
     * DB resource map for the locale at the platform preset scope (tenant_id = 0).
     * Uses JdbcTemplate directly so it works at bootstrap, before any tenant
     * context (MetaContext) is established.
     */
    private Map<String, String> loadDb(String lang) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT i18n_key, value FROM ab_i18n_resource " +
                "WHERE tenant_id = ? AND lang = ? AND deleted_flag = FALSE",
                SYSTEM_TENANT_ID, lang);
            for (Map<String, Object> row : rows) {
                result.put((String) row.get("i18n_key"), (String) row.get("value"));
            }
        } catch (Exception e) {
            log.warn("Override audit: failed to read DB layer for lang={}", lang, e);
        }
        return result;
    }

    /**
     * Set of i18n keys that are platform-owned ({@code source='system'}) and have
     * never been hand-edited ({@code updated_by IS NULL}), at the preset scope.
     * Only such keys are eligible for SEED_DRIFT — a user-edited row is respected,
     * not flagged as stale.
     */
    private Map<String, String> loadDbSystemSources() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DISTINCT i18n_key, source FROM ab_i18n_resource " +
                "WHERE tenant_id = ? AND deleted_flag = FALSE " +
                "  AND source = 'system' AND updated_by IS NULL",
                SYSTEM_TENANT_ID);
            for (Map<String, Object> row : rows) {
                result.put((String) row.get("i18n_key"), (String) row.get("source"));
            }
        } catch (Exception e) {
            log.warn("Override audit: failed to read DB system sources", e);
        }
        return result;
    }

    /** Parse seed/i18n-base.json into key->value for the given locale column. */
    private Map<String, String> loadSeed(String lang) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            ClassPathResource resource = new ClassPathResource("seed/i18n-base.json");
            if (!resource.exists()) {
                return result;
            }
            try (InputStream is = resource.getInputStream()) {
                List<Map<String, String>> entries =
                    objectMapper.readValue(is, new TypeReference<>() {});
                for (Map<String, String> entry : entries) {
                    String key = entry.get("key");
                    String value = entry.get(lang);
                    if (key != null && value != null) {
                        result.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Override audit: failed to read seed/i18n-base.json", e);
        }
        return result;
    }

    private static Map<String, Object> safe(Map<String, Object> m) {
        return m != null ? m : Map.of();
    }

    private static Map<String, String> stringify(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : null);
        }
        return out;
    }

    /** One audited key across all layers. */
    public record OverrideAuditEntry(
        String key,
        String lang,
        String yaml,
        String json,
        String db,
        String seed,
        String winnerLayer,
        String winnerValue,
        String classification
    ) {}

    /** Full audit report for a locale. */
    public record OverrideAuditReport(
        String lang,
        int totalKeys,
        int overriddenCount,
        int driftCount,
        List<OverrideAuditEntry> entries
    ) {}
}
