package com.auraboot.framework.agent.memory.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure-function rule matcher for Spike-4. Implements the patterns in
 * {@code platform/src/test/resources/extraction-prefilter/patterns.json}
 * as inline rules (Phase 1 — keeps simple). Phase 2 will load patterns from
 * JSON if pattern catalog grows.
 *
 * <p>Each {@code tryMatch_*} static method returns either a candidate or
 * null. The matcher itself is stateless / no side effects.
 *
 * <p>See {@code docs/backlog/2026-05-28-spike-4-extraction-rule-prefilter-design.md} §3.
 */
public final class ExtractionRuleMatcher {

    private ExtractionRuleMatcher() {}

    /**
     * Match all rules against the given signals, returning candidates.
     * Multiple rules can fire on the same signal (e.g. failed update_status
     * matches both p3 failure and p6 state-transition).
     */
    public static List<ExtractedMemoryCandidate> match(List<ExtractionSignal> signals) {
        List<ExtractedMemoryCandidate> out = new ArrayList<>();
        if (signals == null) return out;
        for (ExtractionSignal s : signals) {
            ExtractedMemoryCandidate c;
            if ((c = tryMatchUserPreference(s)) != null) out.add(c);
            if ((c = tryMatchRecordCreated(s)) != null) out.add(c);
            if ((c = tryMatchToolFailure(s)) != null) out.add(c);
            if ((c = tryMatchTaskAssigned(s)) != null) out.add(c);
            if ((c = tryMatchTaskCompleted(s)) != null) out.add(c);
            if ((c = tryMatchStateTransition(s)) != null) out.add(c);
            if ((c = tryMatchApprovalDecision(s)) != null) out.add(c);
        }
        return out;
    }

    // ---- p1 ----
    static ExtractedMemoryCandidate tryMatchUserPreference(ExtractionSignal s) {
        if (!"tool_call".equals(s.type())) return null;
        if (!s.name().startsWith("record_user_preference")) return null;
        String field = String.valueOf(s.payload().getOrDefault("field", "preference"));
        String value = String.valueOf(s.payload().getOrDefault("value", ""));
        return new ExtractedMemoryCandidate("p1-tool-record-user-preference",
                "PREFERENCE",
                "User preference: " + field,
                value, 5,
                "Explicit user-preference tool call");
    }

    // ---- p2 ----
    @SuppressWarnings("unchecked")
    static ExtractedMemoryCandidate tryMatchRecordCreated(ExtractionSignal s) {
        if (!"tool_response".equals(s.type())) return null;
        Object data = s.payload().get("data");
        if (!(data instanceof Map)) return null;
        Map<String, Object> dataMap = (Map<String, Object>) data;
        Object recordPid = dataMap.get("recordPid");
        if (recordPid == null) return null;
        Object success = s.payload().get("success");
        if (!Boolean.TRUE.equals(success)) return null;
        Object entityTypeRaw = dataMap.getOrDefault("entityType", "record");
        String entityType = entityTypeRaw == null ? "record" : entityTypeRaw.toString();
        return new ExtractedMemoryCandidate("p2-tool-success-with-record-pid",
                "FACT",
                "Created " + entityType + " " + recordPid,
                s.name() + " created " + entityType + " record",
                3,
                "Recordable entity created");
    }

    // ---- p3 ----
    static ExtractedMemoryCandidate tryMatchToolFailure(ExtractionSignal s) {
        if (!"tool_response".equals(s.type())) return null;
        Object success = s.payload().get("success");
        if (!Boolean.FALSE.equals(success)) return null;
        String error = String.valueOf(s.payload().getOrDefault("error", "unknown error"));
        return new ExtractedMemoryCandidate("p3-tool-failure",
                "LESSON",
                "Failed: " + s.name(),
                error, 4,
                "Tool failure — LESSON for future avoidance");
    }

    // ---- p4 ----
    static ExtractedMemoryCandidate tryMatchTaskAssigned(ExtractionSignal s) {
        if (!"bpm_event".equals(s.type())) return null;
        if (!"task_assigned".equals(s.name())) return null;
        String who = String.valueOf(s.payload().getOrDefault("assignee", "?"));
        String task = String.valueOf(s.payload().getOrDefault("taskTitle", s.payload().getOrDefault("taskId", "?")));
        return new ExtractedMemoryCandidate("p4-bpm-task-assigned",
                "FACT",
                "Assigned: " + who + " → " + task,
                "Task " + task + " assigned to " + who,
                3,
                "BPM assignment");
    }

    // ---- p5 ----
    static ExtractedMemoryCandidate tryMatchTaskCompleted(ExtractionSignal s) {
        if (!"bpm_event".equals(s.type())) return null;
        if (!"task_completed".equals(s.name())) return null;
        String task = String.valueOf(s.payload().getOrDefault("taskTitle", "?"));
        String outcome = String.valueOf(s.payload().getOrDefault("outcome", "completed"));
        return new ExtractedMemoryCandidate("p5-bpm-task-completed",
                "LESSON",
                "Completed: " + task,
                "Outcome: " + outcome, 4,
                "Task outcome");
    }

    // ---- p6 ----
    static ExtractedMemoryCandidate tryMatchStateTransition(ExtractionSignal s) {
        if (!"tool_call".equals(s.type())) return null;
        if (!s.name().startsWith("update_status")) return null;
        String entity = String.valueOf(s.payload().getOrDefault("entity", "?"));
        String oldS = String.valueOf(s.payload().getOrDefault("from", "?"));
        String newS = String.valueOf(s.payload().getOrDefault("to", "?"));
        return new ExtractedMemoryCandidate("p6-state-transition",
                "FACT",
                "State: " + entity + " → " + newS,
                entity + " transitioned from " + oldS + " to " + newS,
                2,
                "Status change");
    }

    // ---- p7 ----
    static ExtractedMemoryCandidate tryMatchApprovalDecision(ExtractionSignal s) {
        if (!"tool_call".equals(s.type())) return null;
        if (!isApproval(s.name())) return null;
        String entity = String.valueOf(s.payload().getOrDefault("entity", "?"));
        String reason = String.valueOf(s.payload().getOrDefault("reason", ""));
        return new ExtractedMemoryCandidate("p7-approval-decision",
                "DECISION",
                s.name() + " " + entity,
                "Reason: " + reason, 6,
                "Approval/rejection — explicit DECISION");
    }

    private static boolean isApproval(String name) {
        return "approve".equals(name) || "reject".equals(name)
                || "approve_request".equals(name) || "reject_request".equals(name);
    }
}
