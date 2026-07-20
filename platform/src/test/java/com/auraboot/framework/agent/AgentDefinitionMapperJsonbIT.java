package com.auraboot.framework.agent;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the finders on {@link AgentDefinitionMapper} against the JSONB handler regression.
 *
 * <p>When these were {@code @Select} annotations they bypassed the entity result map, so the
 * {@code @TableField} type handlers on the JSONB list columns did not apply and MyBatis fell back
 * to a globally registered handler for {@code List} — one built for {@code Dict.items}, which
 * cannot parse {@code "query"}. Reading any agent with a non-empty {@code allowed_operations}
 * threw, and the failure surfaced as a 500 during digital-employee enrollment, nowhere near the
 * mapper. The fixture below is exactly that shape: revert the finders to {@code @Select} and this
 * test fails again.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("AgentDefinitionMapper - JSONB column reads")
@Transactional
@Rollback(true)
class AgentDefinitionMapperJsonbIT extends BaseIntegrationTest {

    @Autowired
    private AgentDefinitionMapper agentDefinitionMapper;

    private AgentDefinition insertAgent(List<String> allowedOperations) {
        AgentDefinition agent = new AgentDefinition();
        agent.setPid(UniqueIdGenerator.generate());
        // The multi-tenant filter scopes reads to the context tenant; a hardcoded id
        // would make findByPid return null for reasons unrelated to what this test guards.
        agent.setTenantId(MetaContext.getCurrentTenantId());
        agent.setAgentCode("it_jsonb_" + System.nanoTime());
        agent.setName("JSONB Fixture Agent");
        agent.setAgentType("reactive");
        agent.setStatus("active");
        agent.setDeletedFlag(false);
        agent.setAllowedOperations(allowedOperations);
        agentDefinitionMapper.insert(agent);
        return agent;
    }

    @Test
    @DisplayName("reads an agent whose allowed_operations is a non-empty string array")
    void readsJsonbStringListWithoutBlowingUp() {
        List<String> ops = List.of("query", "create", "update", "delete", "transition");
        AgentDefinition inserted = insertAgent(ops);

        AgentDefinition loaded = agentDefinitionMapper.findByPid(inserted.getPid());

        assertThat(loaded).as("findByPid must return the agent, not throw").isNotNull();
        assertThat(loaded.getAllowedOperations())
                .as("the JSONB array must come back as the declared List<String>")
                .containsExactlyElementsOf(ops);
    }

    @Test
    @DisplayName("still hides soft-deleted agents — the replaced SQL filtered them explicitly")
    void doesNotReturnSoftDeletedAgents() {
        AgentDefinition inserted = insertAgent(List.of("query"));

        // deleteById performs the logical delete; updateById cannot, because MyBatis-Plus treats
        // the globally configured logic-delete field as off-limits to ordinary updates.
        agentDefinitionMapper.deleteById(inserted.getId());

        assertThat(agentDefinitionMapper.findByPid(inserted.getPid()))
                .as("a soft-deleted agent must not come back from findByPid")
                .isNull();
    }
}
