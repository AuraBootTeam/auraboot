package com.auraboot.framework.agent.service;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run deadline is the only thing standing between a stuck agent and an
 * unbounded spend of time and tokens, and it had no test — so nothing would have
 * noticed it quietly becoming a no-op.
 *
 * <p>It is easy for it to become one. The value is read back out of the database
 * as a {@code timeout_at} column and compared against now; a type the branch does
 * not recognise falls through and returns without complaint (the DATE-column
 * family of defect), and the surrounding catch swallows everything that is not a
 * RuntimeException. Both are silent failures shaped exactly like success.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A run past its deadline is stopped")
class RunDeadlineEnforcementIT extends BaseIntegrationTest {

    @Autowired
    private StepLoopService stepLoopService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private final String runTag = UniqueIdGenerator.generate().substring(18);
    private final String agentCode = "deadline-" + runTag;

    @AfterEach
    void cleanup() {
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_run WHERE agent_id = #{params.agent}",
                Map.of("agent", agentCode));
    }

    private String seedRun(LocalDateTime timeoutAt) {
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> run = new HashMap<>();
        run.put("pid", pid);
        run.put("tenant_id", getTestTenant().getId());
        run.put("agent_id", agentCode);
        run.put("task_id", "task-" + runTag);
        run.put("run_status", "running");
        run.put("started_at", LocalDateTime.now().minusMinutes(10));
        run.put("timeout_at", timeoutAt);
        run.put("created_at", LocalDateTime.now().minusMinutes(10));
        run.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.insert("ab_agent_run", run);
        return pid;
    }

    @Test
    @DisplayName("a run past timeout_at is stopped, and one inside it is left to continue")
    void expiredRunIsStoppedAndLiveRunContinues() {
        String expired = seedRun(LocalDateTime.now().minusMinutes(1));
        // The control matters: a check that threw for every run would satisfy the
        // first assertion while killing everything the moment it shipped.
        String live = seedRun(LocalDateTime.now().plusHours(1));

        assertThatThrownBy(() -> stepLoopService.checkTimeout(expired, LocalDateTime.now().minusMinutes(10)))
                .as("a run past its deadline must not be allowed to keep spending")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exceeded timeout");

        assertThatCode(() -> stepLoopService.checkTimeout(live, LocalDateTime.now().minusMinutes(10)))
                .as("a run still inside its deadline must be left alone")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a run with no deadline set is not stopped by accident")
    void runWithoutDeadlineIsNotStopped() {
        String noDeadline = seedRun(null);
        assertThatCode(() -> stepLoopService.checkTimeout(noDeadline, LocalDateTime.now().minusMinutes(10)))
                .doesNotThrowAnyException();
    }
}
