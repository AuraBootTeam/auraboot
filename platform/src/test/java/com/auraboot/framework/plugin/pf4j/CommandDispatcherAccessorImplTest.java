package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandDispatcherAccessorImplTest {

    @Test
    void dispatch_builds_pipeline_request_and_returns_result_data() {
        CommandExecutor executor = mock(CommandExecutor.class);
        CommandExecuteResult result = CommandExecuteResult.builder()
                .commandCode("m:compute_fee").data(Map.of("fee", 120)).build();
        when(executor.execute(eq("m:compute_fee"), any())).thenReturn(result);

        CommandDispatcherAccessorImpl accessor = new CommandDispatcherAccessorImpl(executor);
        Object out = accessor.execute("m:compute_fee", "m_model", "record-1", Map.of("autoTriggeredBy", "parse"));

        var captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(executor).execute(eq("m:compute_fee"), captor.capture());
        CommandExecuteRequest request = captor.getValue();
        assertThat(request.getTargetRecordId()).isEqualTo("record-1");
        assertThat(request.getTargetRecordPid()).isEqualTo("record-1");
        assertThat(request.getPayload()).containsEntry("autoTriggeredBy", "parse");
        assertThat(out).isEqualTo(Map.of("fee", 120));
    }

    @Test
    void null_payload_becomes_empty_map_and_blank_code_short_circuits() {
        CommandExecutor executor = mock(CommandExecutor.class);
        when(executor.execute(eq("m:compute_fee"), any())).thenReturn(
                CommandExecuteResult.builder().commandCode("m:compute_fee").build());

        CommandDispatcherAccessorImpl accessor = new CommandDispatcherAccessorImpl(executor);
        assertThat(accessor.execute("m:compute_fee", "m_model", "record-1", null)).isNull();

        var captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(executor).execute(eq("m:compute_fee"), captor.capture());
        assertThat(captor.getValue().getPayload()).isEmpty();

        assertThat(accessor.execute(null, "m_model", "record-1", Map.of())).isNull();
        assertThat(accessor.execute("  ", "m_model", "record-1", Map.of())).isNull();
        assertThat(new CommandDispatcherAccessorImpl(null).execute("m:x", "m", "r", Map.of())).isNull();
    }

    @Test
    void pipeline_failure_propagates_as_runtime_exception() {
        CommandExecutor executor = mock(CommandExecutor.class);
        when(executor.execute(any(), any())).thenThrow(new IllegalStateException("invariant violated"));

        CommandDispatcherAccessorImpl accessor = new CommandDispatcherAccessorImpl(executor);
        assertThatThrownBy(() -> accessor.execute("m:compute_fee", "m_model", "record-1", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invariant violated");
    }
}
