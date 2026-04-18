package com.auraboot.framework.agent.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output-signature projector for Shadow Mode match comparisons (PR-60).
 *
 * <p><b>Problem.</b> PR-54 unified the hash algorithm (SHA-256 + canonical
 * JSON) but the two sides of the comparison still hashed structurally
 * different payloads:
 * <ul>
 *   <li>Shadow side: list of {@code {tool_ref, result}} maps where {@code result}
 *       is the invoker-specific full return (e.g. {@code {query_code,total,rows}}
 *       from NamedQuery, {@code {command_code,phase_reached,data}} from DSL command).</li>
 *   <li>Original side: {@code ab_agent_action.after_snapshot} which for write
 *       actions is a single record's field map, and for read actions is absent
 *       entirely — only {@code affected_count} survives.</li>
 * </ul>
 * These shapes never match byte-for-byte, so {@code output_match} stayed
 * pinned to {@code false} and no draft auto-promoted after PR-54.
 *
 * <p><b>Fix.</b> Both sides project to a tool-family-keyed canonical
 * "signature" before hashing. For reads the signature answers "did shadow
 * touch the same number of records as the original?"; for commands it
 * answers "did shadow target the same record and finish as cleanly as the
 * original?". The projection is deliberately coarser than byte-equality:
 * Shadow Mode's purpose is to prove the draft behaves like the
 * human-confirmed run, not to prove the result bytes are identical.
 *
 * <p><b>Schema-incompatibility note (read/query).</b>
 * {@link com.auraboot.framework.agent.service.ActionRecorder#recordReadAction}
 * never writes {@code after_snapshot} — only {@code affected_count}. The
 * query projection therefore omits {@code primary_keys_sorted} (the task
 * brief's preferred shape) and relies on {@code record_count} alone. The
 * shadow side drops its {@code rows} list for the same reason — without the
 * original rows there is nothing to compare them against. If the Action
 * Recorder ever persists query rows this projector can be tightened
 * symmetrically.
 *
 * <p><b>Unknown tool families.</b> Multi-tool drafts and exotic tool_refs
 * (e.g. {@code mcp_*}, {@code api_*}) fall back to full-canonical hashing
 * of the raw payload. That matches pre-PR-60 behaviour and cannot falsely
 * promote — only tools with a registered projection can ever match.
 *
 * <p><b>Failure marker.</b> When projection throws, we return a stable
 * {@code {projection:failed,error:...}} map and let the caller hash that.
 * Two consistent failures on both sides therefore hash the same — which
 * correctly yields {@code output_match=true} for a deterministic failure
 * mode, but the upstream code still records {@code shadow_status="failed"}
 * independently so the outcome surfaces in promotion review.
 */
public final class OutputSignatureProjector {

    /** Tool family tags carried in the projected map so a query projection can never hash-match a command projection. */
    public static final String TYPE_QUERY = "query";
    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_UNKNOWN = "unknown";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private OutputSignatureProjector() {}

    // ========================================================================
    // Tool-family classification
    // ========================================================================

    /** @return true if the tool_ref is a read (nq_* or dsl.query). */
    public static boolean isQueryTool(String toolRef) {
        if (toolRef == null) return false;
        return toolRef.startsWith("nq_") || "dsl.query".equals(toolRef);
    }

    /** @return true if the tool_ref is a write (cmd_* or dsl.command). */
    public static boolean isCommandTool(String toolRef) {
        if (toolRef == null) return false;
        return toolRef.startsWith("cmd_") || "dsl.command".equals(toolRef);
    }

    // ========================================================================
    // Shadow-side projection
    // ========================================================================

    /**
     * Project a single-tool shadow invocation result into the stable
     * signature map. Pass the raw return of {@code ShadowToolInvoker.invokeShadow}.
     *
     * @param toolRef      the tool_ref that produced the result
     * @param shadowResult the raw invoker return (never the executor's {tool_ref,result} wrapper)
     * @return canonical signature map, never null
     */
    public static Map<String, Object> projectShadow(String toolRef, Map<String, Object> shadowResult) {
        try {
            if (isQueryTool(toolRef)) {
                return queryProjection(toolRef, extractRecordCount(shadowResult));
            }
            if (isCommandTool(toolRef)) {
                String recordId = extractCommandRecordId(shadowResult);
                boolean success = extractCommandSuccess(shadowResult);
                return commandProjection(toolRef, recordId, success);
            }
            return unknownProjection(toolRef, shadowResult);
        } catch (RuntimeException e) {
            return failureProjection(toolRef, e);
        }
    }

    // ========================================================================
    // Original-side projection (reads ab_agent_action row fields)
    // ========================================================================

    /**
     * Project an original {@code ab_agent_action} row into the same stable
     * signature as {@link #projectShadow} would produce for the equivalent
     * successful shadow run.
     *
     * @param toolRef           tool_ref parsed from the draft's contract_yaml
     *                          (NOT {@code ab_agent_action.tool_ref} — that column
     *                          is unreliable for legacy rows, and the draft's
     *                          declared tool_ref is what the shadow side used)
     * @param actionStatus      {@code ab_agent_action.action_status}
     * @param targetRecordId    {@code ab_agent_action.target_record_id}; null for reads
     * @param affectedCount     {@code ab_agent_action.affected_count}; for reads this
     *                          is the query result count
     * @param afterSnapshotJson raw {@code after_snapshot::text}; may be null for reads
     * @return canonical signature map, never null
     */
    public static Map<String, Object> projectOriginal(String toolRef,
                                                      String actionStatus,
                                                      String targetRecordId,
                                                      Integer affectedCount,
                                                      String afterSnapshotJson) {
        try {
            if (isQueryTool(toolRef)) {
                // Reads write affected_count but not a rows list (ActionRecorder.recordReadAction).
                long count = affectedCount == null ? 0L : affectedCount.longValue();
                return queryProjection(toolRef, count);
            }
            if (isCommandTool(toolRef)) {
                boolean success = "success".equals(actionStatus);
                return commandProjection(toolRef, targetRecordId, success);
            }
            // Unknown: hash the after_snapshot as a map if we can parse it; else fall back to empty.
            Map<String, Object> snapshot = parseSnapshot(afterSnapshotJson);
            return unknownProjection(toolRef, snapshot);
        } catch (RuntimeException e) {
            return failureProjection(toolRef, e);
        }
    }

    // ========================================================================
    // Hash helper — delegates to CanonicalJsonHasher
    // ========================================================================

    /** Compute the SHA-256 canonical hash of a projection map. */
    public static String computeMatchHash(Map<String, Object> projection) {
        return CanonicalJsonHasher.sha256Canonical(projection);
    }

    // ========================================================================
    // Internals
    // ========================================================================

    private static Map<String, Object> queryProjection(String toolRef, long recordCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", TYPE_QUERY);
        out.put("tool_ref", toolRef);
        out.put("record_count", recordCount);
        return out;
    }

    private static Map<String, Object> commandProjection(String toolRef, String recordId, boolean success) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", TYPE_COMMAND);
        out.put("tool_ref", toolRef);
        out.put("target_record_id", recordId);     // may be null — both sides will match on null
        out.put("success", success);
        return out;
    }

    private static Map<String, Object> unknownProjection(String toolRef, Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", TYPE_UNKNOWN);
        out.put("tool_ref", toolRef);
        out.put("raw", raw == null ? Collections.emptyMap() : raw);
        return out;
    }

    private static Map<String, Object> failureProjection(String toolRef, Throwable e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projection", "failed");
        out.put("tool_ref", toolRef);
        out.put("error", e.getClass().getSimpleName());
        return out;
    }

    private static long extractRecordCount(Map<String, Object> shadowResult) {
        if (shadowResult == null) return 0L;
        Object total = shadowResult.get("total");
        if (total instanceof Number n) return n.longValue();
        Object rows = shadowResult.get("rows");
        if (rows instanceof List<?> l) return l.size();
        return 0L;
    }

    // TODO(record_ids): tighten query projection once ActionRecorder
    // persists row IDs — see ActionRecorder#recordReadAction. Until then,
    // the query projection can only compare record_count symmetrically.

    private static String extractCommandRecordId(Map<String, Object> shadowResult) {
        if (shadowResult == null) return null;
        Object data = shadowResult.get("data");
        if (data instanceof Map<?, ?> m) {
            Object v = m.get("recordId");
            if (v == null) v = m.get("pid");
            if (v == null) v = m.get("id");
            if (v != null) return String.valueOf(v);
        }
        Object direct = shadowResult.get("target_record_id");
        return direct == null ? null : String.valueOf(direct);
    }

    private static boolean extractCommandSuccess(Map<String, Object> shadowResult) {
        if (shadowResult == null) return false;
        // N-R3-1: primary signal is the explicit "success" key written by
        // DslCommandShadowInvoker. The fallback heuristic is retained for
        // back-compat with hand-constructed maps (tests, future invokers
        // that haven't migrated yet), but it is imprecise: a partial phase
        // like "validation" with no exception would otherwise count as
        // success even though CommandExecutorImpl never reached the
        // "completed_dry_run" terminal phase.
        Object explicit = shadowResult.get("success");
        if (explicit instanceof Boolean b) return b;
        if (shadowResult.containsKey("error")) return false;
        Object phase = shadowResult.get("phase_reached");
        return phase != null;
    }

    private static Map<String, Object> parseSnapshot(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
