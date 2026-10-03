package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowPayloadContractTest {
    private Map<String, Object> sagaResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("status", "compensated");
        result.put("sagaExecutionId", "owned-saga");
        result.put("processInstanceId", null);
        result.put("stepResults", null);
        return result;
    }

    private void assertSnapshot(Map<String, Object> original, Map<String, Object> copy) {
        assertEquals(original, copy);
        assertTrue(copy.containsKey("processInstanceId"));
        assertNull(copy.get("processInstanceId"));
        original.put("status", "mutated");
        assertEquals("compensated", copy.get("status"));
        assertThrows(UnsupportedOperationException.class, () -> copy.put("status", "changed"));
    }

    @Test
    void resultPreservesNullableSagaFieldsAndImmutableSnapshot() {
        Map<String, Object> input = sagaResult();
        assertSnapshot(input, new WorkflowCapability.WorkflowResult(input).payload());
    }

    @Test
    void requestPreservesExplicitNullBusinessFieldsAndImmutableSnapshot() {
        Map<String, Object> input = sagaResult();
        WorkflowCapability.WorkflowRequest request = new WorkflowCapability.WorkflowRequest(7L, 9L, input);
        assertEquals(7L, request.tenantId());
        assertEquals(9L, request.actorUserId());
        assertSnapshot(input, request.payload());
    }

    @Test
    void partialFailurePreservesNullableFieldsAndOriginalCause() {
        Map<String, Object> input = sagaResult();
        IllegalStateException cause = new IllegalStateException("step failed");
        var failure = new WorkflowCapability.WorkflowExecutionException("workflow failed", cause, input);
        assertSame(cause, failure.getCause());
        assertEquals("workflow failed", failure.getMessage());
        assertSnapshot(input, failure.payload());
    }

    @Test
    void nullKeysRemainInvalidAcrossThePort() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(null, "invalid");
        assertThrows(NullPointerException.class, () -> new WorkflowCapability.WorkflowResult(input));
        assertThrows(NullPointerException.class, () -> new WorkflowCapability.WorkflowRequest(7L, 9L, input));
        assertThrows(NullPointerException.class, () ->
                new WorkflowCapability.WorkflowExecutionException("failed", null, input));
    }

    @Test
    void absentPayloadKeepsExistingEmptyContract() {
        assertEquals(Map.of(), new WorkflowCapability.WorkflowRequest(7L, 9L, null).payload());
        assertEquals(Map.of(), new WorkflowCapability.WorkflowResult(null).payload());
        assertEquals(Map.of(), WorkflowCapability.WorkflowResult.empty().payload());
        assertEquals(Map.of(), new WorkflowCapability.WorkflowExecutionException("failed", null, null).payload());
    }
}
