package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentPromptAssemblyService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-82 R5-C1 — {@link AgentPromptAssemblyService} is the second entry point
 * that injects {@code memory_content} into the LLM system prompt (the first
 * being {@code AgentRunService.loadMemorySection}, covered by
 * {@link AgentRunShadowAnnotationIntegrationTest}). Prior to PR-82 this path
 * read {@code memory_content} without applying
 * {@link ActiveMemoryService#SHADOW_ANNOTATION_PREFIX}, so shadow-mode
 * tenant memories leaked into the prompt as if they were fully endorsed.
 *
 * <p>This test pins the post-fix contract:
 * <ul>
 *   <li>shadow_mode=TRUE memory content appears prefixed in the assembled prompt</li>
 *   <li>shadow_mode=FALSE memory content appears verbatim (no prefix)</li>
 *   <li>shared-memories path (cross-agent, shareable=TRUE) applies the same marker</li>
 * </ul>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Agent prompt assembly shadow annotation (PR-82 R5-C1)")
class AgentPromptAssemblyShadowAnnotationIntegrationTest extends BaseIntegrationTest {

    @Autowired private AgentPromptAssemblyService assembly;
    @Autowired private JdbcTemplate jdbc;

    private final String runId    = String.valueOf(System.nanoTime());
    private final String agentCode = "prompt-shadow-test-" + runId;

    @BeforeEach
    void seedAgentDefinition() {
        jdbc.update(
                "INSERT INTO ab_agent_definition "
                + "  (pid, tenant_id, agent_code, name, description, personality, expertise, "
                + "   communication_style, boundaries, soul_goals, status, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'Shadow Test Bot', 'shadow marker coverage', "
                + "        'neutral', 'testing', 'concise', 'none', 'verify markers', "
                + "        'active', NOW(), NOW(), FALSE)",
                UniqueIdGenerator.generate(), tenantId(), agentCode);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE memory_agent_id = ?", agentCode);
        jdbc.update("DELETE FROM ab_agent_definition WHERE agent_code = ?", agentCode);
    }

    private Long tenantId() {
        return getTestTenant().getId();
    }

    private void insertMemory(String category, String title, String content,
                              int importance, boolean shadowMode, boolean shareable) {
        jdbc.update(
                "INSERT INTO ab_agent_memory "
                + "  (pid, tenant_id, memory_agent_id, memory_type, category, "
                + "   memory_title, memory_content, importance, shadow_mode, shareable, "
                + "   created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'fact', ?, ?, ?, ?, ?, ?, NOW(), NOW(), FALSE)",
                UniqueIdGenerator.generate(), tenantId(), agentCode, category,
                title, content, importance, shadowMode, shareable);
    }

    @Test
    @DisplayName("shadow_mode=TRUE memory is prefixed with SHADOW_ANNOTATION_PREFIX in assembled prompt")
    void shadow_memory_annotated_in_assembled_prompt() {
        insertMemory("agent", "Release cadence",
                "Release every other Thursday morning", 9, true, false);

        String prompt = assembly.assemblePrompt(tenantId(), agentCode, getTestUser().getId());

        assertThat(prompt)
                .isNotBlank()
                .contains(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX
                        + "Release every other Thursday morning");
    }

    @Test
    @DisplayName("shadow_mode=FALSE memory appears verbatim in assembled prompt (no prefix)")
    void active_memory_not_annotated_in_assembled_prompt() {
        insertMemory("agent", "Retro cadence",
                "Retro every second Friday afternoon", 9, false, false);

        String prompt = assembly.assemblePrompt(tenantId(), agentCode, getTestUser().getId());

        assertThat(prompt).isNotBlank()
                .contains("Retro every second Friday afternoon")
                .doesNotContain(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX);
    }

    @Test
    @DisplayName("applyShadowMarker idempotency — already-prefixed Chinese content is not double-prefixed")
    void apply_shadow_marker_is_idempotent_on_chinese_content() {
        // R5-N5: the idempotency guard is exact-prefix startsWith. Verify the
        // non-ASCII SHADOW_ANNOTATION_PREFIX literal round-trips cleanly so
        // callers can safely call applyShadowMarker on already-marked content.
        String raw = "客户偏好早上开会"; // "customer prefers morning meetings"
        String marked = ActiveMemoryService.applyShadowMarker(raw, true);
        String remarked = ActiveMemoryService.applyShadowMarker(marked, true);

        assertThat(marked).isEqualTo(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX + raw);
        assertThat(remarked).isEqualTo(marked)
                .as("idempotent: second applyShadowMarker call must not double-prefix");
        // Sanity: the prefix appears exactly once.
        int firstIdx = remarked.indexOf(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX);
        int lastIdx  = remarked.lastIndexOf(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX);
        assertThat(firstIdx).isZero();
        assertThat(lastIdx).isEqualTo(firstIdx);
    }

    @Test
    @DisplayName("loadSharedMemories applies shadow annotation for shareable=TRUE rows")
    void shared_memory_path_applies_shadow_marker() {
        insertMemory("agent", "Cross-agent fact",
                "Customer prefers morning meetings", 8, true, true);

        String shared = assembly.loadSharedMemories(tenantId(), 4000);

        assertThat(shared)
                .isNotBlank()
                .contains(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX
                        + "Customer prefers morning meetings");
    }
}
