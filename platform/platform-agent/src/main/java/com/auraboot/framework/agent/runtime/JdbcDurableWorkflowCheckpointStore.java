package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentPlanStep;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * JDBC checkpoint store for ACP durable workflow step history.
 */
@Service
@RequiredArgsConstructor
public class JdbcDurableWorkflowCheckpointStore implements DurableWorkflowCheckpointStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordPlanCheckpoint(Long tenantId,
                                     String runPid,
                                     int currentStep,
                                     List<AgentPlanStep> plan,
                                     String reason) {
        if (tenantId == null || runPid == null || runPid.isBlank()) {
            throw new IllegalArgumentException("tenantId and runPid are required for workflow checkpoints");
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO ab_agent_run_checkpoint
                        (pid, tenant_id, run_pid, checkpoint_type, step_index, reason, plan_snapshot, created_at)
                    VALUES (?, ?, ?, 'plan', ?, ?, ?::jsonb, NOW())
                    """,
                    UniqueIdGenerator.generate(),
                    tenantId,
                    runPid,
                    currentStep,
                    reason,
                    objectMapper.writeValueAsString(plan != null ? plan : List.of()));
        } catch (Exception e) {
            throw new IllegalStateException("Workflow checkpoint persistence failed for runPid=" + runPid, e);
        }
    }
}
