package com.auraboot.framework.agent.runtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates pending tool record freshness against the dynamic data audit log.
 */
@Service
public class DataChangeLogPendingContextFreshnessValidator implements PendingContextFreshnessValidator {

    private final JdbcTemplate jdbcTemplate;

    public DataChangeLogPendingContextFreshnessValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PendingContextFreshnessDecision validate(PendingToolSnapshot pending) {
        FreshnessRef ref = resolveRef(pending);
        if (!ref.verifiable()) {
            return PendingContextFreshnessDecision.freshDecision();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, changed_at
                  FROM ab_data_change_log
                 WHERE tenant_id = ?
                   AND model_code = ?
                   AND record_id = ?
                 ORDER BY changed_at DESC, id DESC
                 LIMIT 1
                """, ref.tenantId(), ref.modelCode(), ref.recordId());
        if (rows.isEmpty()) {
            return stale(pending, ref, "record_version_unavailable");
        }
        if (matchesExpected(ref.expectedVersion(), rows.get(0))) {
            return PendingContextFreshnessDecision.freshDecision();
        }
        return stale(pending, ref, "record_version_stale");
    }

    private PendingContextFreshnessDecision stale(PendingToolSnapshot pending,
                                                 FreshnessRef ref,
                                                 String reasonCode) {
        return PendingContextFreshnessDecision.stale(
                conflictPolicy(pending),
                reasonCode,
                "Pending context is stale for " + ref.modelCode() + "/" + ref.recordId());
    }

    private FreshnessRef resolveRef(PendingToolSnapshot pending) {
        if (pending == null || pending.getTenantId() == null) {
            return FreshnessRef.unverifiable();
        }
        ParsedContextVersion parsed = parseContextVersion(pending.getContextVersion());
        String modelCode = firstNonBlank(pending.getModelCode(), parsed.modelCode());
        String recordId = firstNonBlank(recordIdFromInput(pending.getInput()), parsed.recordId());
        String expectedVersion = firstNonBlank(pending.getRecordVersion(), parsed.expectedVersion());
        if (!hasText(modelCode) || !hasText(recordId) || !hasText(expectedVersion)) {
            return FreshnessRef.unverifiable();
        }
        return new FreshnessRef(pending.getTenantId(), modelCode, recordId, expectedVersion);
    }

    private ParsedContextVersion parseContextVersion(String contextVersion) {
        if (!hasText(contextVersion)) {
            return ParsedContextVersion.empty();
        }
        String[] parts = contextVersion.split(":");
        if (parts.length < 3) {
            return ParsedContextVersion.empty();
        }
        StringBuilder expected = new StringBuilder(parts[2]);
        for (int i = 3; i < parts.length; i++) {
            expected.append(':').append(parts[i]);
        }
        return new ParsedContextVersion(parts[0], parts[1], expected.toString());
    }

    private String recordIdFromInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        for (String key : List.of("recordId", "recordPid", "pid", "id")) {
            Object value = input.get(key);
            if (value != null && hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private boolean matchesExpected(String expectedVersion, Map<String, Object> row) {
        if (!hasText(expectedVersion)) {
            return true;
        }
        return currentTokens(row).contains(expectedVersion);
    }

    private Set<String> currentTokens(Map<String, Object> row) {
        Set<String> tokens = new LinkedHashSet<>();
        Object id = row.get("id");
        if (id != null) {
            tokens.add(String.valueOf(id));
            tokens.add("change:" + id);
        }
        Object changedAt = row.get("changed_at");
        if (changedAt != null) {
            String text = changedAt instanceof TemporalAccessor ? changedAt.toString() : String.valueOf(changedAt);
            tokens.add(text);
            tokens.add("changed_at:" + text);
        }
        return tokens;
    }

    private ContextConflictPolicy conflictPolicy(PendingToolSnapshot pending) {
        if (pending == null || !hasText(pending.getContextConflictPolicy())) {
            return ContextConflictPolicy.REJECT_AND_REPLAN;
        }
        try {
            return ContextConflictPolicy.valueOf(pending.getContextConflictPolicy());
        } catch (IllegalArgumentException e) {
            return ContextConflictPolicy.REJECT_AND_REPLAN;
        }
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record FreshnessRef(Long tenantId, String modelCode, String recordId, String expectedVersion) {
        static FreshnessRef unverifiable() {
            return new FreshnessRef(null, null, null, null);
        }

        boolean verifiable() {
            return tenantId != null
                    && modelCode != null && !modelCode.isBlank()
                    && recordId != null && !recordId.isBlank()
                    && expectedVersion != null && !expectedVersion.isBlank();
        }
    }

    private record ParsedContextVersion(String modelCode, String recordId, String expectedVersion) {
        static ParsedContextVersion empty() {
            return new ParsedContextVersion(null, null, null);
        }
    }
}
