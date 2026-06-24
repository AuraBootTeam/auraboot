package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link UpdateRecordActionHandler} — context extraction + payload.fields → update call.
 * The executor→handler dispatch and the entityCode/recordPid extraction pattern are already verified
 * over the real stack by the comment/notify handler ITs; the full record-mutation real-stack IT
 * (needs a published meta-model + record fixture) is a documented follow-on (gap tracker).
 */
class UpdateRecordActionHandlerTest {

    private final DynamicDataService dynamicDataService = mock(DynamicDataService.class);
    private final UpdateRecordActionHandler handler = new UpdateRecordActionHandler(dynamicDataService);

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
        verify(dynamicDataService).update(eq("complaint"), eq("CMP-1"), eq(Map.of("status", "ESCALATED")));
    }

    @Test
    void throwsWhenRecordContextMissing() {
        assertThatThrownBy(() -> handler.execute(
                plan("UPDATE_RECORD", Map.of("fields", Map.of("status", "X"))), ctx(Map.of("data", Map.of()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void throwsWhenFieldsMissingOrEmpty() {
        var record = Map.<String, Object>of("entityCode", "complaint", "recordPid", "CMP-1");
        assertThatThrownBy(() -> handler.execute(plan("UPDATE_RECORD", Map.of()), ctx(record)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.execute(plan("UPDATE_RECORD", Map.of("fields", Map.of())), ctx(record)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
