package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.RunLifecycleService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually happens to an agent run when the process serving it dies.
 *
 * <p>This is worth pinning because the surrounding vocabulary invites the wrong
 * answer. The runtime writes a checkpoint at every plan-step boundary and the
 * component is called a durable workflow engine, which reads as "an interrupted
 * run resumes where it stopped". It does not:
 * {@link com.auraboot.framework.agent.runtime.DurableWorkflowCheckpointStore} has
 * no read side at all, and the only consumer of {@code ab_agent_run_checkpoint}
 * is a read-only ops endpoint for a person to look at. The checkpoint trail is
 * evidence, not a restore point.
 *
 * <p>The real guarantee is narrower and worth having: a run whose heartbeat
 * stopped is noticed and closed out as failed, rather than sitting in
 * {@code running} forever and holding a concurrency slot against its agent. That
 * behaviour had no test, so nothing stopped it from quietly regressing into the
 * leak it exists to prevent.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A run abandoned by a dead process is closed out, not resumed")
class StaleRunRecoveryIT extends BaseIntegrationTest {

    @Autowired
    private RunLifecycleService runLifecycleService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private final String runTag = UniqueIdGenerator.generate().substring(18);
    private final String agentCode = "stale-run-" + runTag;

    @AfterEach
    void cleanup() {
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_run WHERE agent_id = #{params.agent}",
                Map.of("agent", agentCode));
    }

    /** A run row as the runtime leaves it: started, heartbeating, not yet finished. */
    private String seedRunningRun(LocalDateTime lastHeartbeat) {
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> run = new HashMap<>();
        run.put("pid", pid);
        run.put("tenant_id", getTestTenant().getId());
        run.put("agent_id", agentCode);
        run.put("task_id", "task-" + runTag);
        run.put("run_status", "running");
        run.put("started_at", lastHeartbeat.minusMinutes(1));
        run.put("created_at", lastHeartbeat.minusMinutes(1));
        run.put("updated_at", lastHeartbeat);
        dynamicDataMapper.insert("ab_agent_run", run);
        return pid;
    }

    private String statusOf(String runPid) {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT pid, run_status FROM ab_agent_run WHERE pid = #{params.pid}",
                Map.of("pid", runPid));
        assertThat(rows).as("the seeded run must exist").hasSize(1);
        return (String) rows.get(0).get("run_status");
    }

    @Test
    @DisplayName("a run whose heartbeat stopped is failed; a live one is left alone")
    void stalledRunIsFailedAndHealthyRunSurvives() {
        // Older than the 5-minute threshold: the process that owned it is gone.
        String stalled = seedRunningRun(LocalDateTime.now().minusMinutes(30));
        // Heartbeating normally. Without this control, a sweep that failed every
        // running row would pass the assertion above and kill live work.
        String healthy = seedRunningRun(LocalDateTime.now());

        runLifecycleService.detectStaleRuns();

        assertThat(statusOf(stalled))
                .as("an abandoned run must not sit in 'running' forever, holding a concurrency slot")
                .isEqualTo("failed");
        assertThat(statusOf(healthy))
                .as("a run that is still heartbeating must be left alone")
                .isEqualTo("running");
    }

    @Test
    @DisplayName("the sweep does not resume anything — recovery is re-triggering, not continuing")
    void stalledRunIsNotResumed() {
        String stalled = seedRunningRun(LocalDateTime.now().minusMinutes(30));

        runLifecycleService.detectStaleRuns();

        // Deliberately asserting the absence of resumption. Checkpoints are written
        // at every step boundary but have no read side, so there is no state to
        // continue from; if a resume path is ever added, this test should be the
        // thing that notices and gets rewritten rather than a surprise in production.
        assertThat(statusOf(stalled)).isNotEqualTo("running");
        assertThat(statusOf(stalled)).isNotEqualTo("success");
    }
}
