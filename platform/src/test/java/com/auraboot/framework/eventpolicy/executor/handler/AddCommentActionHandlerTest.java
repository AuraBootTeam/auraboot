package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.service.RecordCommentService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddCommentActionHandlerTest {

    private final RecordCommentService recordCommentService = mock(RecordCommentService.class);
    private final AddCommentActionHandler handler = new AddCommentActionHandler(recordCommentService);

    private DecisionContext ctx(Map<String, Object> record) {
        return DecisionContext.builder().scope(Scope.RECORD, record).build();
    }

    private ResolvedActionPlan plan(Map<String, Object> payload) {
        return new ResolvedActionPlan("R-CMT", "ADD_COMMENT", "RECORD", 10, payload, "idem-cmt");
    }

    @Test
    void supportsAddCommentOnly() {
        assertThat(handler.supports("ADD_COMMENT")).isTrue();
        assertThat(handler.supports("WRITE_AUDIT")).isFalse();
    }

    @Test
    void addsRenderedCommentAndReturnsStructuredPayload() {
        when(recordCommentService.addComment(eq("complaint"), eq("CMP-1"),
                eq("auto: CMP-1 needs triage"), eq("@ops")))
                .thenReturn(Map.of("commentPid", "CMT-1"));

        Map<String, Object> result = handler.executeWithResult(
                plan(Map.of(
                        "content", "auto: ${record.recordPid} needs triage",
                        "mentions", "@ops")),
                ctx(Map.of("entityCode", "complaint", "recordPid", "CMP-1")));

        assertThat(result)
                .containsEntry("modelCode", "complaint")
                .containsEntry("recordPid", "CMP-1")
                .containsEntry("content", "auto: CMP-1 needs triage")
                .containsEntry("mentions", "@ops")
                .containsEntry("commentPid", "CMT-1");
        verify(recordCommentService).addComment("complaint", "CMP-1",
                "auto: CMP-1 needs triage", "@ops");
    }

    @Test
    void throwsStructuredFailureWhenRecordContextMissing() {
        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(
                        plan(Map.of("content", "needs triage")),
                        ctx(Map.of("data", Map.of()))));

        assertThat(error)
                .hasMessage("ADD_COMMENT requires record.entityCode + record.recordPid in the context");
        assertThat(error.resultPayload())
                .containsEntry("failureReason", "comment_context_missing")
                .containsEntry("actionType", "ADD_COMMENT");
        assertThat(error.resultPayload().get("requiredContext")).asList()
                .containsExactly("record.entityCode", "record.recordPid");
    }

    @Test
    void throwsStructuredFailureWhenContentMissing() {
        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(
                        plan(Map.of("content", "  ")),
                        ctx(Map.of("entityCode", "complaint", "recordPid", "CMP-1"))));

        assertThat(error)
                .hasMessage("ADD_COMMENT requires a non-empty payload.content");
        assertThat(error.resultPayload())
                .containsEntry("failureReason", "comment_content_missing")
                .containsEntry("modelCode", "complaint")
                .containsEntry("recordPid", "CMP-1")
                .containsEntry("field", "payload.content")
                .containsEntry("actionType", "ADD_COMMENT");
    }

    @Test
    void wrapsCommentServiceFailureWithStructuredPayload() {
        when(recordCommentService.addComment(eq("complaint"), eq("CMP-1"), eq("needs triage"), eq("@ops")))
                .thenThrow(new IllegalStateException("comment table unavailable"));

        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(
                        plan(Map.of("content", "needs triage", "mentions", "@ops")),
                        ctx(Map.of("entityCode", "complaint", "recordPid", "CMP-1"))));

        assertThat(error)
                .hasMessage("ADD_COMMENT failed: comment table unavailable")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(error.resultPayload())
                .containsEntry("failureReason", "comment_write_failed")
                .containsEntry("modelCode", "complaint")
                .containsEntry("recordPid", "CMP-1")
                .containsEntry("content", "needs triage")
                .containsEntry("mentions", "@ops")
                .containsEntry("errorMessage", "comment table unavailable")
                .containsEntry("actionType", "ADD_COMMENT");
    }
}
