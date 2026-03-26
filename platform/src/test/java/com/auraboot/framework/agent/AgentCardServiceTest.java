package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentCardService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link AgentCardService}.
 * <p>
 * Verifies that generated A2A Agent Cards:
 * <ul>
 *   <li>Contain all required A2A fields (name, url, version, capabilities, skills, authentication)</li>
 *   <li>Expose only public metadata — no system_prompt, soul_profile, or API keys</li>
 *   <li>Return null for unknown / inactive agents</li>
 *   <li>Discovery document lists all active agents</li>
 * </ul>
 */
@Slf4j
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("AgentCardService - Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
@Rollback(true)
public class AgentCardServiceTest extends BaseIntegrationTest {

    @Autowired
    private AgentCardService agentCardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns the agent_code of the first active system-level agent (tenant_id = 0). */
    private String firstActiveAgentCode() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT agent_code FROM ab_agent_definition " +
                "WHERE status = 'active' AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "ORDER BY id LIMIT 1",
                String.class);
        return codes.isEmpty() ? null : codes.get(0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // generateAgentCard
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("generateAgentCard returns non-null for an active agent")
    void generateAgentCard_activeAgent_returnsCard() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);

        assertThat(card).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("generateAgentCard card contains required A2A top-level fields")
    void generateAgentCard_containsRequiredA2AFields() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);

        assertThat(card).isNotNull();
        assertThat(card).containsKey("name");
        assertThat(card).containsKey("url");
        assertThat(card).containsKey("version");
        assertThat(card).containsKey("capabilities");
        assertThat(card).containsKey("skills");
        assertThat(card).containsKey("authentication");
        assertThat(card).containsKey("defaultInputModes");
        assertThat(card).containsKey("defaultOutputModes");
    }

    @Test
    @Order(3)
    @DisplayName("generateAgentCard capabilities block has streaming and pushNotifications flags")
    void generateAgentCard_capabilitiesBlock_hasExpectedFlags() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);
        assertThat(card).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) card.get("capabilities");
        assertThat(caps).containsKey("streaming");
        assertThat(caps).containsKey("pushNotifications");
    }

    @Test
    @Order(4)
    @DisplayName("generateAgentCard authentication block contains bearer scheme")
    void generateAgentCard_authenticationBlock_containsBearerScheme() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);
        assertThat(card).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) card.get("authentication");
        assertThat(auth).containsKey("schemes");

        @SuppressWarnings("unchecked")
        List<String> schemes = (List<String>) auth.get("schemes");
        assertThat(schemes).contains("bearer");
    }

    @Test
    @Order(5)
    @DisplayName("generateAgentCard skills list is non-null (may be empty for tenant without skills)")
    void generateAgentCard_skillsList_isNonNull() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);
        assertThat(card).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) card.get("skills");
        assertThat(skills).isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("generateAgentCard skills each have id, name, description fields")
    void generateAgentCard_skills_haveRequiredFields() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);
        assertThat(card).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) card.get("skills");
        for (Map<String, Object> skill : skills) {
            assertThat(skill).containsKey("id");
            assertThat(skill).containsKey("name");
            assertThat(skill).containsKey("description");
        }
    }

    @Test
    @Order(7)
    @DisplayName("generateAgentCard does NOT expose sensitive fields")
    void generateAgentCard_doesNotExposeSensitiveFields() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);
        assertThat(card).isNotNull();

        // These internal fields must never appear in the public card
        assertThat(card).doesNotContainKey("system_prompt");
        assertThat(card).doesNotContainKey("soul_profile");
        assertThat(card).doesNotContainKey("model");
        assertThat(card).doesNotContainKey("guardrails");
        assertThat(card).doesNotContainKey("personality");
        assertThat(card).doesNotContainKey("boundaries");
        assertThat(card).doesNotContainKey("soul_goals");
    }

    @Test
    @Order(8)
    @DisplayName("generateAgentCard returns null for unknown agent code")
    void generateAgentCard_unknownCode_returnsNull() {
        Map<String, Object> card = agentCardService.generateAgentCard("nonexistent_agent_code_xyz_" + System.currentTimeMillis());

        assertThat(card).isNull();
    }

    @Test
    @Order(9)
    @DisplayName("generateAgentCard x-auraboot extension block contains agentCode")
    void generateAgentCard_extensionBlock_containsAgentCode() {
        String code = firstActiveAgentCode();
        assumeActiveAgentExists(code);

        Map<String, Object> card = agentCardService.generateAgentCard(code);
        assertThat(card).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) card.get("x-auraboot");
        assertThat(ext).isNotNull();
        assertThat(ext.get("agentCode")).isEqualTo(code);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // generateDiscoveryDocument
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("generateDiscoveryDocument returns non-null document")
    void generateDiscoveryDocument_returnsNonNull() {
        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();

        assertThat(doc).isNotNull();
    }

    @Test
    @Order(11)
    @DisplayName("generateDiscoveryDocument contains required top-level fields")
    void generateDiscoveryDocument_containsRequiredFields() {
        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();

        assertThat(doc).containsKey("platform");
        assertThat(doc).containsKey("version");
        assertThat(doc).containsKey("agents");
        assertThat(doc).containsKey("agentCount");
    }

    @Test
    @Order(12)
    @DisplayName("generateDiscoveryDocument agents list is non-null")
    void generateDiscoveryDocument_agentsListIsNonNull() {
        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) doc.get("agents");
        assertThat(agents).isNotNull();
    }

    @Test
    @Order(13)
    @DisplayName("generateDiscoveryDocument lists active agents (at least system templates)")
    void generateDiscoveryDocument_listsActiveAgents() {
        // Seed data contains at least 3 system agents (tenant_id = 0)
        long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition " +
                "WHERE status = 'active' AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                Long.class);

        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) doc.get("agents");
        assertThat(agents).hasSizeGreaterThanOrEqualTo((int) activeCount);

        Number agentCount = (Number) doc.get("agentCount");
        assertThat(agentCount.intValue()).isGreaterThanOrEqualTo((int) activeCount);
    }

    @Test
    @Order(14)
    @DisplayName("generateDiscoveryDocument each agent entry has name, code, description, cardUrl")
    void generateDiscoveryDocument_eachEntryHasRequiredFields() {
        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) doc.get("agents");
        // Only validate if there are agents
        if (!agents.isEmpty()) {
            for (Map<String, Object> entry : agents) {
                assertThat(entry).containsKey("name");
                assertThat(entry).containsKey("code");
                assertThat(entry).containsKey("description");
                assertThat(entry).containsKey("cardUrl");
            }
        }
    }

    @Test
    @Order(15)
    @DisplayName("generateDiscoveryDocument cardUrl contains agent code")
    void generateDiscoveryDocument_cardUrlContainsAgentCode() {
        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) doc.get("agents");
        if (!agents.isEmpty()) {
            Map<String, Object> first = agents.get(0);
            String code = (String) first.get("code");
            String cardUrl = (String) first.get("cardUrl");
            assertThat(cardUrl).contains(code);
            assertThat(cardUrl).endsWith(".json");
        }
    }

    @Test
    @Order(16)
    @DisplayName("generateDiscoveryDocument does NOT expose soul_profile or system_prompt in any entry")
    void generateDiscoveryDocument_doesNotExposeSensitiveData() {
        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) doc.get("agents");
        for (Map<String, Object> entry : agents) {
            assertThat(entry).doesNotContainKey("system_prompt");
            assertThat(entry).doesNotContainKey("soul_profile");
            assertThat(entry).doesNotContainKey("model");
            assertThat(entry).doesNotContainKey("guardrails");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void assumeActiveAgentExists(String code) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                code != null,
                "Skipping test: no active agents in db");
    }
}
