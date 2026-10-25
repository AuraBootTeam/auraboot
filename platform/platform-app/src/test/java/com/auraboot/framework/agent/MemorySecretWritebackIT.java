package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
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
 * M6: the secret guard is only real if the row does not appear in the database.
 * A unit test on the matcher proves the matcher; this proves the write path
 * consults it — the two are separate claims, and it is the second one that has
 * historically been the gap (a guard nobody calls).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("M6: memory carrying a credential never reaches the table")
class MemorySecretWritebackIT extends BaseIntegrationTest {

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private final String agentCode = "secret-guard-" + UniqueIdGenerator.generate().substring(18);

    @AfterEach
    void cleanup() {
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_memory WHERE memory_agent_id = #{params.agent}",
                Map.of("agent", agentCode));
    }

    @Test
    @DisplayName("a credential-bearing writeback is refused; an ordinary one still lands")
    void credentialBearingMemoryIsRefused() {
        Long tenantId = getTestTenant().getId();

        // Control first: without it, a guard that rejected *everything* would
        // make the assertion below pass while silently destroying memory.
        String okPid = agentMemoryService.createScopedMemory(
                tenantId, agentCode, "fact", "user",
                "Shipping preference", "The customer prefers delivery after 18:00.",
                5, false, "user", "42");
        assertThat(okPid).as("ordinary content must still be written").isNotBlank();

        String refusedPid = agentMemoryService.createScopedMemory(
                tenantId, agentCode, "fact", "user",
                "Integration setup", "Use api_key: sk-EXAMPLEEXAMPLEEXAMPLE for the vendor call.",
                5, false, "user", "42");
        assertThat(refusedPid).as("a credential-bearing writeback must be refused").isNull();

        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT pid, memory_content FROM ab_agent_memory "
                        + "WHERE memory_agent_id = #{params.agent}",
                Map.of("agent", agentCode));
        assertThat(rows).as("exactly the ordinary memory survived").hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("memory_content")))
                .doesNotContain("sk-EXAMPLEEXAMPLEEXAMPLE");
    }

    @Test
    @DisplayName("the unscoped create path is guarded too, not just the scoped one")
    void unscopedCreatePathIsGuarded() {
        Long tenantId = getTestTenant().getId();
        assertThat(agentMemoryService.createMemory(
                tenantId, agentCode, "lesson", "agent",
                "Vendor call", "password: hunter2000 worked", 5, false))
                .isNull();
        assertThat(dynamicDataMapper.selectByQuery(
                "SELECT pid FROM ab_agent_memory WHERE memory_agent_id = #{params.agent}",
                Map.of("agent", agentCode)))
                .isEmpty();
    }
}
