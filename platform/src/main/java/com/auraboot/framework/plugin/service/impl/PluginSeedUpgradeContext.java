package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** Per-import state; persisted only in the existing successful import receipt. */
final class PluginSeedUpgradeContext {
    enum Kind {
        AUTOMATION("ab_automation", null, null),
        DECISION("ab_drt_definition", "ab_drt_version", "decision_code"),
        CONDITION("ab_drt_condition_fragment", null, "fragment_code"),
        EVENT_POLICY("ab_drt_policy_definition", "ab_drt_policy_version", "policy_code");
        final String table;
        final String versions;
        final String codeColumn;
        Kind(String table, String versions, String codeColumn) {
            this.table = table; this.versions = versions; this.codeColumn = codeColumn;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<String, Object> baselines;
    private final Set<Kind> locked = EnumSet.noneOf(Kind.class);
    private final Long tenant;

    PluginSeedUpgradeContext(JdbcTemplate jdbc, ObjectMapper json, Map<String, Object> previous) {
        this.jdbc = jdbc;
        this.json = json;
        this.tenant = jdbc == null ? null : MetaContext.getCurrentTenantId();
        this.baselines = new LinkedHashMap<>(previous);
    }

    static PluginSeedUpgradeContext untracked() {
        return new PluginSeedUpgradeContext(null, null, Map.of());
    }

    /** Serialize snapshot comparison with metadata edits, including new version inserts. */
    void lock(Kind kind) {
        if (jdbc == null || locked.contains(kind)) return;
        if (tenant == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new PluginException("Seed upgrade requires a tenant and an active import transaction");
        }
        // Metadata-only locks: readers continue; writers wait at most the bounded lock timeout.
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        jdbc.execute("LOCK TABLE " + kind.table + (kind.versions == null ? "" : ", " + kind.versions)
                + " IN SHARE ROW EXCLUSIVE MODE");
        locked.add(kind);
    }

    boolean preserve(Kind kind, String code, boolean exists, String pid, ImportRequest.ConflictStrategy strategy,
                     ImportExecuteResult result) {
        if (!exists) return false;
        if (strategy != ImportRequest.ConflictStrategy.OVERWRITE_SAFE || jdbc == null) {
            return PluginImportSupport.preserveExistingSeed(true, strategy, kind + ":" + code, result::addWarning);
        }
        if (pid == null) throw new PluginException("Existing seed has no stable resource identity");
        Object baseline = baselines.get(key(kind, code));
        if (baseline != null && json.valueToTree(baseline).equals(json.valueToTree(snapshot(kind, code, pid)))) {
            return false;
        }
        result.addWarning("Preserved " + kind + ":" + PluginImportSupport.logSafe(code)
                + ": customer changes or an unknown import baseline; review this definition before replacing it.");
        return true;
    }

    void record(Kind kind, String code, String pid) {
        if (jdbc == null) return;
        if (pid == null) throw new PluginException("Imported seed has no stable resource identity");
        baselines.put(key(kind, code), snapshot(kind, code, pid));
    }

    boolean isTracked() { return jdbc != null; }

    Map<String, Object> baselines() { return new LinkedHashMap<>(baselines); }

    private String key(Kind kind, String code) { return kind.name() + ":" + code; }

    private Map<String, Object> snapshot(Kind kind, String code, String pid) {
        lock(kind);
        Map<String, Object> state = new LinkedHashMap<>();
        // Only top-level audit/counter columns are excluded. JSON business payloads are never stripped.
        String projection = "(to_jsonb(t) - ARRAY['created_at','updated_at','created_by','updated_by',"
                + "'published_at','published_by','last_triggered_at','trigger_count']::text[])::text";
        String filter = kind == Kind.CONDITION ? kind.codeColumn + " = ?" : "pid = ?";
        state.put("definition", rows("SELECT " + projection + " FROM " + kind.table
                + " t WHERE tenant_id = ? AND " + filter + " ORDER BY id", kind == Kind.CONDITION ? code : pid));
        if (kind.versions != null) {
            state.put("versions", rows("SELECT " + projection + " FROM " + kind.versions
                    + " t WHERE tenant_id = ? AND " + kind.codeColumn + " = ? ORDER BY version, id", code));
        }
        if (((List<?>) state.get("definition")).isEmpty()) {
            throw new PluginException("Seed state changed or disappeared during import");
        }
        return state;
    }

    private List<Object> rows(String sql, String identity) {
        return jdbc.query(sql, (rs, index) -> {
            try { return json.readValue(rs.getString(1), Object.class); }
            catch (java.io.IOException e) { throw new PluginException("Cannot read module seed baseline", e); }
        }, tenant, identity);
    }
}
