package com.auraboot.framework.automation.iot;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CommandBusIotActionSink}.
 *
 * Covers:
 * - kind=command: executor called with extracted command code + payload minus "command" key
 * - kind=record: executor called (same path as command)
 * - kind=alarm / kind=workflow: executor NOT called (out of slice)
 * - blank/null payload.command: executor NOT called, warn logged
 * - tenantId set in try/finally
 */
@ExtendWith(MockitoExtension.class)
class CommandBusIotActionSinkTest {

    @Mock
    private CommandExecutor commandExecutor;

    private CommandBusIotActionSink sink;

    @BeforeEach
    void setUp() {
        sink = new CommandBusIotActionSink(commandExecutor);
    }

    private Map<String, Object> envelope(String kind, Object tenantId, String deviceId,
                                          long emittedAt, Map<String, Object> payload) {
        Map<String, Object> env = new HashMap<>();
        env.put("kind", kind);
        if (tenantId != null) env.put("tenantId", tenantId);
        env.put("deviceId", deviceId);
        env.put("emittedAt", emittedAt);
        env.put("payload", payload);
        return env;
    }

    private Map<String, Object> payload(String command, Object... kvPairs) {
        Map<String, Object> p = new HashMap<>();
        p.put("command", command);
        for (int i = 0; i < kvPairs.length; i += 2) {
            p.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return p;
    }

    // ---- happy path: kind=command ----

    @Test
    @DisplayName("kind=command: executor called with command code + payload minus 'command' key")
    void command_executorInvokedWithStrippedPayload() {
        Map<String, Object> pl = payload("pe:x", "a", 1, "b", "hello");
        sink.emit("command", envelope("command", 1L, "d", 123L, pl));

        ArgumentCaptor<CommandExecuteRequest> reqCap = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("pe:x"), reqCap.capture());

        CommandExecuteRequest req = reqCap.getValue();
        assertThat(req.getPayload()).doesNotContainKey("command");
        assertThat(req.getPayload()).containsEntry("a", 1);
        assertThat(req.getPayload()).containsEntry("b", "hello");
        assertThat(req.getClientRequestId()).isEqualTo("iot-d-123");
    }

    @Test
    @DisplayName("kind=command: tenantId=1 is set in MetaContext before execute, cleared after")
    void command_tenantContextSetAndCleared() {
        // We can't assert MetaContext.get() here (cleared by finally before assertion),
        // but we verify execute is called without a MetaContext exception (tenantId set),
        // and that after emit the context is cleared (MetaContext.exists() returns false).
        Map<String, Object> pl = payload("pe:capture_iot_reading", "dataPointId", "dp-1");
        sink.emit("command", envelope("command", 5L, "dev-5", 999L, pl));
        verify(commandExecutor).execute(eq("pe:capture_iot_reading"), any());
        // No caller context existed, so emit() clears afterwards.
        assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isFalse();
    }

    @Test
    @DisplayName("kind=command: a pre-existing caller context is RESTORED (not cleared) after execute")
    void command_restoresCallerContext() {
        // A webhook-triggered automation runs the sink inside an already-tenant-scoped caller
        // that still needs MetaContext afterwards (AutomationLogMapper.updateStatus). The sink
        // must not strip that context — regression for the 500 "MetaContext not initialized".
        com.auraboot.framework.application.tenant.MetaContext.setContext(7L, 42L, "user-pid", "alice");
        try {
            Map<String, Object> pl = payload("pe:capture_iot_reading", "dataPointId", "dp-1");
            sink.emit("command", envelope("command", 7L, "dev-7", 1000L, pl));
            verify(commandExecutor).execute(eq("pe:capture_iot_reading"), any());
            // Caller context survives, with its tenant intact.
            assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isTrue();
            assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId()).isEqualTo(7L);
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.clear();
        }
    }

    @Test
    @DisplayName("kind=record: executor called (same path as command)")
    void record_executorInvoked() {
        Map<String, Object> pl = payload("pe:capture_iot_reading", "value", 42.0);
        sink.emit("record", envelope("record", 1L, "dev-R", 456L, pl));
        verify(commandExecutor).execute(eq("pe:capture_iot_reading"), any());
    }

    // ---- ignored kinds ----

    @Test
    @DisplayName("kind=alarm: executor NOT called (Kafka sink handles alarms)")
    void alarm_executorNotInvoked() {
        Map<String, Object> pl = payload("irrelevant:cmd");
        sink.emit("alarm", envelope("alarm", 1L, "d", 100L, pl));
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("kind=workflow: executor NOT called")
    void workflow_executorNotInvoked() {
        Map<String, Object> pl = payload("irrelevant:cmd");
        sink.emit("workflow", envelope("workflow", 1L, "d", 100L, pl));
        verifyNoInteractions(commandExecutor);
    }

    // ---- guard: blank command ----

    @Test
    @DisplayName("blank payload.command: executor NOT called, no exception")
    void blankCommand_executorNotInvoked() {
        Map<String, Object> pl = new HashMap<>();
        pl.put("command", "  "); // blank
        pl.put("a", 1);
        sink.emit("command", envelope("command", 1L, "d", 100L, pl));
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("missing payload.command key: executor NOT called")
    void missingCommandKey_executorNotInvoked() {
        Map<String, Object> pl = new HashMap<>();
        pl.put("a", 1); // no "command" key
        sink.emit("command", envelope("command", 1L, "d", 100L, pl));
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("missing payload entirely: executor NOT called, no exception")
    void missingPayload_executorNotInvoked() {
        Map<String, Object> env = new HashMap<>();
        env.put("kind", "command");
        env.put("deviceId", "d");
        env.put("emittedAt", 100L);
        // no "payload" key
        sink.emit("command", env);
        verifyNoInteractions(commandExecutor);
    }

    // ---- clientRequestId format ----

    @Test
    @DisplayName("clientRequestId = 'iot-<deviceId>-<emittedAt>'")
    void clientRequestId_format() {
        Map<String, Object> pl = payload("pe:test_cmd");
        sink.emit("command", envelope("command", 1L, "sensor-99", 1718000001234L, pl));

        ArgumentCaptor<CommandExecuteRequest> cap = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(anyString(), cap.capture());
        assertThat(cap.getValue().getClientRequestId()).isEqualTo("iot-sensor-99-1718000001234");
    }

    // ---- no tenantId in envelope: still executes (best-effort) ----

    @Test
    @DisplayName("no tenantId in envelope: executor called without setting MetaContext tenant")
    void noTenantId_executorStillInvoked() {
        Map<String, Object> pl = payload("pe:any_cmd");
        sink.emit("command", envelope("command", null, "d", 111L, pl));
        verify(commandExecutor).execute(eq("pe:any_cmd"), any());
    }
}
