package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JdbcDurableWorkflowCheckpointStore")
class JdbcDurableWorkflowCheckpointStoreTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("recordPlanCheckpoint appends a JSONB plan snapshot row")
    void recordPlanCheckpointAppendsJsonbPlanSnapshotRow() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDurableWorkflowCheckpointStore store =
                new JdbcDurableWorkflowCheckpointStore(jdbcTemplate, new ObjectMapper());

        store.recordPlanCheckpoint(
                1L,
                "run-1",
                2,
                List.of(new AgentPlanStep(0, "first"), new AgentPlanStep(1, "second")),
                "step_completed");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO ab_agent_run_checkpoint")
                .contains("plan_snapshot")
                .contains("?::jsonb");
        assertThat(args.getValue()[1]).isEqualTo(1L);
        assertThat(args.getValue()[2]).isEqualTo("run-1");
        assertThat(args.getValue()[3]).isEqualTo(2);
        assertThat(args.getValue()[4]).isEqualTo("step_completed");
        assertThat(String.valueOf(args.getValue()[5])).contains("first").contains("second");
    }
}
