package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.auraboot.framework.application.tenant.MetaContext;
import static org.mockito.ArgumentMatchers.any;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link UpdateRecordActionHandler} — context extraction + payload.fields → update call.
 * The executor→handler dispatch and the entityCode/recordPid extraction pattern are already verified
 * over the real stack by the comment/notify handler ITs; the full record-mutation real-stack IT
 * (needs a published meta-model + record fixture) is a documented follow-on (gap tracker).
 */
class UpdateRecordActionHandlerTest {

    private final CommandService commandService = mock(CommandService.class);
    private final CommandExecutor commandExecutor = mock(CommandExecutor.class);
    private final UpdateRecordActionHandler handler = new UpdateRecordActionHandler(commandService, commandExecutor);
    @AfterEach void clearActor() { MetaContext.clear(); }
    @BeforeEach void command() {
        MetaContext.setContext(42L, 7L, "user-pid", "operator");
        when(commandService.resolveCrudCommands("complaint")).thenReturn(Map.of("update", "complaint:update"));
    }

    private DecisionContext ctx(Map<String, Object> record) {
        return DecisionContext.builder().scope(Scope.RECORD, record).build();
    }

    private ResolvedActionPlan plan(String type, Map<String, Object> payload) {
        return new ResolvedActionPlan("R-1", type, "RECORD", 10, payload, "idem-1");
    }

    @Test
    void supportsUpdateAndPatch() {
        assertThat(handler.supports("UPDATE_RECORD")).isTrue();
        assertThat(handler.supports("PATCH_RECORD")).isTrue();
        assertThat(handler.supports("NOTIFY")).isFalse();
    }

    @Test
    void updatesRecordFieldsFromContextAndPayload() {
        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-1",
                "data", Map.of("priority", "HIGH"));
        handler.execute(plan("UPDATE_RECORD", Map.of("fields", Map.of("status", "ESCALATED"))), ctx(record));
        var request = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("complaint:update"), request.capture());
        assertThat(request.getValue().getTargetRecordPid()).isEqualTo("CMP-1");
        assertThat(request.getValue().getPayload()).containsEntry("status", "ESCALATED");
        assertThat(request.getValue().getClientRequestId()).isEqualTo("idem-1");
    }

    @Test
    void returnsStructuredUpdatedRecordResult() throws Exception {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("status", "ESCALATED");
        fields.put("priority", "HIGH");


        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-1");
        Map<String, Object> result = handler.executeWithResult(
                plan("UPDATE_RECORD", Map.of("fields", fields)), ctx(record));

        assertThat(result)
                .containsEntry("modelCode", "complaint")
                .containsEntry("recordPid", "CMP-1");
        assertThat(result.get("updatedFields")).asList().containsExactly("status", "priority");
    }

    @Test
    void throwsStructuredFailureWhenRecordContextMissing() {
        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.execute(
                        plan("UPDATE_RECORD", Map.of("fields", Map.of("status", "X"))),
                        ctx(Map.of("data", Map.of()))));

        assertThat(error)
                .hasMessage("UPDATE_RECORD requires record.entityCode + record.recordPid in the context");
        assertThat(error.resultPayload())
                .containsEntry("failureReason", "record_context_missing")
                .containsEntry("actionType", "UPDATE_RECORD");
        assertThat(error.resultPayload().get("requiredContext")).asList()
                .containsExactly("record.entityCode", "record.recordPid");
    }

    @Test
    void throwsStructuredFailureWhenFieldsMissingOrEmpty() {
        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-1");
        ActionExecutionException missing = assertThrows(ActionExecutionException.class,
                () -> handler.execute(plan("UPDATE_RECORD", Map.of()), ctx(record)));
        ActionExecutionException empty = assertThrows(ActionExecutionException.class,
                () -> handler.execute(plan("UPDATE_RECORD", Map.of("fields", Map.of())), ctx(record)));

        assertThat(missing.resultPayload())
                .containsEntry("failureReason", "update_fields_missing")
                .containsEntry("modelCode", "complaint")
                .containsEntry("recordPid", "CMP-1")
                .containsEntry("field", "payload.fields");
        assertThat(empty.resultPayload())
                .containsEntry("failureReason", "update_fields_missing")
                .containsEntry("modelCode", "complaint")
                .containsEntry("recordPid", "CMP-1")
                .containsEntry("field", "payload.fields");
    }

    @Test
    void wrapsAuthorizedCommandFailureWithStructuredPayload() {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("status", "ESCALATED");
        fields.put("priority", "HIGH");
        when(commandExecutor.execute(eq("complaint:update"), any()))
                .thenThrow(new IllegalStateException("model field status is readonly"));

        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-1");
        ActionExecutionException error = assertThrows(ActionExecutionException.class,
                () -> handler.executeWithResult(plan("PATCH_RECORD", Map.of("fields", fields)), ctx(record)));

        assertThat(error)
                .hasMessage("UPDATE_RECORD failed: model field status is readonly")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(error.resultPayload())
                .containsEntry("failureReason", "record_update_failed")
                .containsEntry("modelCode", "complaint")
                .containsEntry("recordPid", "CMP-1")
                .containsEntry("fieldCount", 2)
                .containsEntry("errorMessage", "model field status is readonly")
                .containsEntry("actionType", "PATCH_RECORD");
        assertThat(error.resultPayload().get("updatedFields")).asList()
                .containsExactly("status", "priority");
    }
}
