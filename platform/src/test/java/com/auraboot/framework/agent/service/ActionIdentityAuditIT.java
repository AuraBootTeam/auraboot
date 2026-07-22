package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who did it, and on whose authority.
 *
 * <p>An agent executes inside the initiating user's MetaContext: its tools run
 * with that person's permissions and data scope. The action audit recorded
 * {@code actor_type='agent'} and nothing else — not which agent, and not whose
 * authority was spent. "An agent deleted this record" is true, and it is not an
 * answer anyone can act on; in an incident review it is the only question worth
 * asking.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("An agent action records the agent and the person it acted for")
class ActionIdentityAuditIT extends BaseIntegrationTest {

    @Autowired
    private ActionRecorder actionRecorder;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private final String runTag = UniqueIdGenerator.generate().substring(18);
    private final String agentCode = "identity-" + runTag;
    private final String runPid = UniqueIdGenerator.generate();

    @AfterEach
    void cleanup() {
        StepContext.clearAgentCode();
        StepContext.clearRunPid();
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_action WHERE run_id = #{params.run}",
                Map.of("run", runPid));
    }

    private Map<String, Object> recordedRow() {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT pid, actor_type, actor_id, on_behalf_of_user_id "
                        + "FROM ab_agent_action WHERE run_id = #{params.run} ORDER BY id DESC",
                Map.of("run", runPid));
        assertThat(rows).as("the action must have been recorded at all").isNotEmpty();
        return rows.get(0);
    }

    @Test
    @DisplayName("the acting agent and the authorising user are both on the row")
    void recordsActorAndOnBehalfOf() {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:crm:delete_account").toolType("dsl_command").riskLevel("L4").build();
        CommandExecuteResult result = CommandExecuteResult.builder()
                .commandCode("crm:delete_account").phaseReached("EFFECT").build();

        StepContext.setRunPid(runPid);
        StepContext.setAgentCode(agentCode);
        try {
            actionRecorder.recordAction(getTestTenant().getId(), runPid, "crm:delete_account",
                    tool, Map.of("recordPid", "acc-1"), result, null, null, null);
        } finally {
            StepContext.clearAgentCode();
            StepContext.clearRunPid();
        }

        Map<String, Object> row = recordedRow();
        assertThat(row.get("actor_type")).isEqualTo("agent");
        assertThat(row.get("actor_id"))
                .as("which agent — the column existed and was never written")
                .isEqualTo(agentCode);
        assertThat(row.get("on_behalf_of_user_id"))
                .as("whose authority the action spent")
                .isEqualTo(getTestUser().getId());
    }

    @Test
    @DisplayName("a run with no acting agent in scope leaves the column empty rather than guessing")
    void leavesActorBlankWhenUnknown() {
        // Recording without an agent in scope must not borrow an identity from
        // somewhere else. An audit row that names the wrong actor is worse than
        // one that admits it does not know.
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:crm:list_accounts").toolType("dsl_query").riskLevel("L0").build();
        CommandExecuteResult result = CommandExecuteResult.builder()
                .commandCode("crm:list_accounts").phaseReached("EFFECT").build();

        StepContext.setRunPid(runPid);
        try {
            actionRecorder.recordAction(getTestTenant().getId(), runPid, "crm:list_accounts",
                    tool, Map.of(), result, null, null, null);
        } finally {
            StepContext.clearRunPid();
        }

        assertThat(recordedRow().get("actor_id")).isNull();
    }
}
