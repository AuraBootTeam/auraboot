package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.service.impl.AsyncTaskServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncTaskAccessorImplTest {
    @org.junit.jupiter.api.BeforeEach void setup() { MetaContext.setContext(10L, 20L, "user-pid", "tester"); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void restart_replay_requires_explicit_host_api_opt_in() {
        var service = mock(AsyncTaskServiceImpl.class);
        var accessor = new AsyncTaskAccessorImpl(service, new ObjectMapper(), 10L, 20L);
        accessor.submitCommandTask("ordinary", "model", "record", Map.of("resumeOnRestart", true));
        accessor.submitResumableCommandTask("checkpointed", "model", "record", Map.of());
        var request = ArgumentCaptor.forClass(AsyncTaskSubmitRequest.class);
        verify(service, times(2)).submitTask(request.capture(), eq(10L), eq(20L));
        assertThat(request.getAllValues().get(0).getInputParams().has("resumeOnRestart")).isFalse();
        assertThat(request.getAllValues().get(1).getInputParams().path("resumeOnRestart").asBoolean()).isTrue();
    }

    @Test void same_model_followup_preserves_server_authorization_and_identity() {
        var service = mock(AsyncTaskServiceImpl.class);
        var dto = new AsyncTaskDTO();
        dto.setTaskCode("queued-1");
        when(service.submitTask(any(), eq(10L), eq(20L))).thenReturn(dto);
        var accessor = new AsyncTaskAccessorImpl(service, new ObjectMapper(), 10L, 20L);
        String result = MetaContext.runWithCommandAuthority("bom.convert.execute", () ->
                MetaContext.runWithCommandPermitPlan("SELF", null, "bom_task", "old-task", () ->
                        accessor.submitCommandTask("bom:process_conversion_task", "bom_task", "new-task", Map.of())));
        var request = ArgumentCaptor.forClass(AsyncTaskSubmitRequest.class);
        verify(service).submitTask(request.capture(), eq(10L), eq(20L));
        var input = request.getValue().getInputParams();
        assertThat(result).isEqualTo("queued-1");
        assertThat(input.path("commandPermitScope").asText()).isEqualTo("SELF");
        assertThat(input.path("commandAuthority").asText()).isEqualTo("bom.convert.execute");
        assertThat(input.path("modelCode").asText()).isEqualTo("bom_task");
        assertThat(input.path("recordPid").asText()).isEqualTo("new-task");
        assertThat(input.has("commandExpectedVersion")).isFalse();
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }

    @Test void cross_model_followup_does_not_inherit_permission_and_payload_cannot_forge_it() {
        var service = mock(AsyncTaskServiceImpl.class);
        var accessor = new AsyncTaskAccessorImpl(service, new ObjectMapper(), 10L, 20L);
        MetaContext.runWithCommandPermitPlan("ALL", null, "bom_task", "task", () ->
                accessor.submitCommandTask("other:command", "other_model", "other-record",
                        Map.of("commandPermitScope", "ALL", "commandAuthority", "admin")));
        var request = ArgumentCaptor.forClass(AsyncTaskSubmitRequest.class);
        verify(service).submitTask(request.capture(), eq(10L), eq(20L));
        var input = request.getValue().getInputParams();
        assertThat(input.has("commandPermitScope")).isFalse();
        assertThat(input.has("commandAuthority")).isFalse();
    }
}
