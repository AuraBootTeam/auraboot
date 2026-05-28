package com.auraboot.framework.agent.memory.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExtractionRuleMatcher — Spike-4 phase 1 unit tests")
class ExtractionRuleMatcherTest {

    private static ExtractionSignal toolCall(String name, Map<String, Object> payload) {
        return new ExtractionSignal("tool_call", name, payload);
    }

    private static ExtractionSignal toolResp(String name, Map<String, Object> payload) {
        return new ExtractionSignal("tool_response", name, payload);
    }

    private static ExtractionSignal bpm(String event, Map<String, Object> payload) {
        return new ExtractionSignal("bpm_event", event, payload);
    }

    @Test
    @DisplayName("empty signals → empty candidates")
    void empty() {
        assertTrue(ExtractionRuleMatcher.match(List.of()).isEmpty());
        assertTrue(ExtractionRuleMatcher.match(null).isEmpty());
    }

    @Test
    @DisplayName("p1: record_user_preference tool call → PREFERENCE")
    void p1() {
        ExtractedMemoryCandidate c = ExtractionRuleMatcher.tryMatchUserPreference(
                toolCall("record_user_preference",
                        Map.of("field", "logistics_provider", "value", "JD Logistics")));
        assertNotNull(c);
        assertEquals("PREFERENCE", c.memoryType());
        assertEquals("User preference: logistics_provider", c.title());
        assertEquals("JD Logistics", c.content());
        assertEquals(5, c.importance());
    }

    @Test
    @DisplayName("p2: tool response with data.recordId + success=true → FACT")
    void p2() {
        ExtractedMemoryCandidate c = ExtractionRuleMatcher.tryMatchRecordCreated(
                toolResp("create_order", Map.of(
                        "success", true,
                        "data", Map.of("recordId", "01XYZ123", "entityType", "Order"))));
        assertNotNull(c);
        assertEquals("FACT", c.memoryType());
        assertTrue(c.title().contains("Order 01XYZ123"));
    }

    @Test
    @DisplayName("p2 ignored when success=false")
    void p2NoMatchOnFailure() {
        assertNull(ExtractionRuleMatcher.tryMatchRecordCreated(
                toolResp("create_order", Map.of("success", false, "data", Map.of("recordId", "X")))));
    }

    @Test
    @DisplayName("p2 ignored when no recordId")
    void p2NoMatchWithoutRecordId() {
        assertNull(ExtractionRuleMatcher.tryMatchRecordCreated(
                toolResp("ping", Map.of("success", true, "data", Map.of("status", "ok")))));
    }

    @Test
    @DisplayName("p3: tool response success=false → LESSON")
    void p3() {
        ExtractedMemoryCandidate c = ExtractionRuleMatcher.tryMatchToolFailure(
                toolResp("create_invoice", Map.of(
                        "success", false,
                        "error", "supplier credit limit exceeded")));
        assertNotNull(c);
        assertEquals("LESSON", c.memoryType());
        assertTrue(c.title().contains("Failed"));
        assertTrue(c.content().contains("credit limit"));
    }

    @Test
    @DisplayName("p4: BPM task_assigned event → FACT")
    void p4() {
        ExtractedMemoryCandidate c = ExtractionRuleMatcher.tryMatchTaskAssigned(
                bpm("task_assigned", Map.of("assignee", "alice", "taskTitle", "Review PR")));
        assertNotNull(c);
        assertEquals("FACT", c.memoryType());
        assertTrue(c.title().contains("alice"));
        assertTrue(c.title().contains("Review PR"));
    }

    @Test
    @DisplayName("p5: BPM task_completed → LESSON")
    void p5() {
        ExtractedMemoryCandidate c = ExtractionRuleMatcher.tryMatchTaskCompleted(
                bpm("task_completed", Map.of("taskTitle", "Build", "outcome", "passed")));
        assertNotNull(c);
        assertEquals("LESSON", c.memoryType());
        assertTrue(c.content().contains("passed"));
    }

    @Test
    @DisplayName("p6: update_status* tool call → FACT")
    void p6() {
        ExtractedMemoryCandidate c = ExtractionRuleMatcher.tryMatchStateTransition(
                toolCall("update_status_order",
                        Map.of("entity", "Order-42", "from", "draft", "to", "published")));
        assertNotNull(c);
        assertEquals("FACT", c.memoryType());
        assertTrue(c.title().contains("Order-42"));
        assertTrue(c.title().contains("published"));
    }

    @Test
    @DisplayName("p7: approve / reject tool calls → DECISION")
    void p7() {
        ExtractedMemoryCandidate approve = ExtractionRuleMatcher.tryMatchApprovalDecision(
                toolCall("approve", Map.of("entity", "Invoice-7", "reason", "amount within budget")));
        assertNotNull(approve);
        assertEquals("DECISION", approve.memoryType());

        ExtractedMemoryCandidate reject = ExtractionRuleMatcher.tryMatchApprovalDecision(
                toolCall("reject", Map.of("entity", "Request-X", "reason", "missing data")));
        assertNotNull(reject);
        assertEquals("DECISION", reject.memoryType());

        assertNull(ExtractionRuleMatcher.tryMatchApprovalDecision(
                toolCall("create_order", Map.of())));
    }

    @Test
    @DisplayName("match() chains all rules — single failed update_status fires both p3 + p6")
    void multipleRulesFireOnOneSignal() {
        // Note: a failed update_status would come as TWO signals (tool_call + tool_response).
        // Here we just verify match() iterates all signals + tries all rules.
        List<ExtractionSignal> signals = List.of(
                toolCall("update_status_invoice",
                        Map.of("entity", "Invoice-9", "from", "pending", "to", "paid")),
                toolResp("update_status_invoice",
                        Map.of("success", false, "error", "transaction rolled back")));
        List<ExtractedMemoryCandidate> out = ExtractionRuleMatcher.match(signals);
        assertEquals(2, out.size(),
                "tool_call → p6 state-transition + tool_response → p3 failure");
        assertTrue(out.stream().anyMatch(c -> "p6-state-transition".equals(c.patternId())));
        assertTrue(out.stream().anyMatch(c -> "p3-tool-failure".equals(c.patternId())));
    }

    @Test
    @DisplayName("non-matching signal yields no candidates")
    void noMatch() {
        ExtractionSignal s = toolCall("get_current_time", Map.of());
        assertTrue(ExtractionRuleMatcher.match(List.of(s)).isEmpty());
    }
}
