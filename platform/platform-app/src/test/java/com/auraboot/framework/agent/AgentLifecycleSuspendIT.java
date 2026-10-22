package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentCollaborationService;
import com.auraboot.framework.agent.service.AgentLifecycleService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5: an operator must be able to stop one misbehaving agent without silencing
 * every agent in the deployment (the only lever that existed was the global
 * {@code aura.agent.enabled} flag).
 *
 * <p>The assertion that matters is not "the column changed" — it is that a real
 * consumer stops accepting work for that agent. {@link AgentCollaborationService}
 * is used as the witness because it resolves agent definitions through the same
 * {@code status = 'active'} predicate the dispatch and chat engines use, so a
 * suspension that this test sees is a suspension those engines see too.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("M5: suspending one agent stops that agent, and only that agent")
class AgentLifecycleSuspendIT extends BaseIntegrationTest {

    @Autowired
    private AgentLifecycleService agentLifecycleService;

    @Autowired
    private AgentCollaborationService agentCollaborationService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    /** Short enough for agent_code, still unique per execution (G-test-hermetic-1). */
    private final String runTag = UniqueIdGenerator.generate().substring(18);
    private final String parentTaskPid = UniqueIdGenerator.generate();
    private String agentPid;
    private String agentCode;

    @BeforeEach
    void seedAgent() {
        agentPid = UniqueIdGenerator.generate();
        agentCode = "lifecycle-victim-" + runTag;
        Map<String, Object> agent = new HashMap<>();
        agent.put("pid", agentPid);
        agent.put("tenant_id", getTestTenant().getId());
        agent.put("agent_code", agentCode);
        agent.put("name", "Lifecycle victim " + runTag);
        agent.put("model", "test-model");
        agent.put("system_prompt", "test");
        agent.put("status", "active");
        agent.put("deleted_flag", false);
        agent.put("created_at", LocalDateTime.now());
        agent.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.insert("ab_agent_definition", agent);
    }

    @AfterEach
    void cleanup() {
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_definition WHERE pid = #{params.pid}", Map.of("pid", agentPid));
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_task WHERE assignee_id = #{params.code}", Map.of("code", agentCode));
    }

    @Test
    @DisplayName("a suspended agent stops accepting delegated work; resuming brings it back")
    void suspendedAgentIsUnreachableUntilResumed() {
        Long tenantId = getTestTenant().getId();
        Long actor = getTestUser().getId();

        // Before: the agent takes work. If this ever fails the witness is broken,
        // and the suspension assertion below would pass for the wrong reason.
        String childPid = agentCollaborationService.delegateTask(
                tenantId, parentTaskPid, null, agentCode, "warm-up", "pre-suspension delegation", Map.of());
        assertThat(childPid).as("an active agent must accept delegated work").isNotBlank();

        AgentLifecycleService.Transition suspended =
                agentLifecycleService.suspend(agentPid, actor, "misbehaving in production");
        assertThat(suspended.changed()).isTrue();
        assertThat(suspended.previousStatus()).isEqualTo("active");
        assertThat(suspended.status()).isEqualTo("suspended");

        assertThatThrownBy(() -> agentCollaborationService.delegateTask(
                tenantId, parentTaskPid, null, agentCode, "blocked", "post-suspension delegation", Map.of()))
                .as("a suspended agent must be unreachable to the runtime")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(agentCode);

        // The operator who suspended it is recorded on the row.
        assertThat(dynamicDataMapper.selectByQuery(
                "SELECT pid, updated_by FROM ab_agent_definition WHERE pid = #{params.pid}",
                Map.of("pid", agentPid)).get(0).get("updated_by"))
                .as("the suspension must record who did it")
                .isEqualTo(actor);

        AgentLifecycleService.Transition resumed = agentLifecycleService.resume(agentPid, actor);
        assertThat(resumed.changed()).isTrue();
        assertThat(resumed.status()).isEqualTo("active");
        assertThat(agentCollaborationService.delegateTask(
                tenantId, parentTaskPid, null, agentCode, "back", "post-resume delegation", Map.of()))
                .as("resuming must restore reachability")
                .isNotBlank();
    }

    @Test
    @DisplayName("suspending twice is a no-op, not an error")
    void suspendIsIdempotent() {
        Long actor = getTestUser().getId();
        assertThat(agentLifecycleService.suspend(agentPid, actor, "first").changed()).isTrue();
        AgentLifecycleService.Transition second = agentLifecycleService.suspend(agentPid, actor, "again");
        assertThat(second.changed()).isFalse();
        assertThat(second.status()).isEqualTo("suspended");
    }

    @Test
    @DisplayName("an unknown agent pid is refused, not silently ignored")
    void unknownAgentIsRefused() {
        assertThatThrownBy(() -> agentLifecycleService.suspend("no-such-agent", 1L, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-agent");
    }
}
