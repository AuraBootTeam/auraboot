package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.AgentReleaseDeploymentService;
import com.auraboot.framework.agent.service.AgentReleaseDeploymentService.PublishedRelease;
import com.auraboot.framework.agent.service.AgentReleaseDeploymentService.RuntimeBinding;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Agent immutable release and deployment")
class AgentReleaseDeploymentIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentReleaseDeploymentService releases;

    @Test
    @DisplayName("draft edits stay isolated until publish, then deployment moves atomically")
    void draftIsolationAndAtomicPublish() {
        String definitionPid = UniqueIdGenerator.generate();
        String agentCode = "release_it_" + definitionPid.toLowerCase();
        insertDefinition(definitionPid, agentCode, "Original prompt");

        RuntimeBinding v1 = releases.requireActive(testTenant.getId(), agentCode);
        assertThat(v1.releaseNo()).isEqualTo(1);
        assertThat(v1.releaseSpec()).containsEntry("system_prompt", "Original prompt");

        jdbc.update(
                """
                UPDATE ab_agent_definition
                SET system_prompt = 'Updated prompt', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND pid = ?
                """,
                testTenant.getId(),
                definitionPid);

        RuntimeBinding stillV1 = releases.requireActive(testTenant.getId(), agentCode);
        assertThat(stillV1.releasePid()).isEqualTo(v1.releasePid());
        assertThat(stillV1.releaseHash()).isEqualTo(v1.releaseHash());
        assertThat(stillV1.releaseSpec()).containsEntry("system_prompt", "Original prompt");

        PublishedRelease published = releases.publish(
                testTenant.getId(),
                definitionPid,
                testUser.getId());
        RuntimeBinding v2 = releases.requireActive(testTenant.getId(), agentCode);

        assertThat(published.created()).isTrue();
        assertThat(published.releaseNo()).isEqualTo(2);
        assertThat(v2.releasePid()).isEqualTo(published.releasePid());
        assertThat(v2.deploymentPid()).isEqualTo(v1.deploymentPid());
        assertThat(v2.releaseSpec()).containsEntry("system_prompt", "Updated prompt");
        assertThat(v1.releaseSpec()).containsEntry("system_prompt", "Original prompt");
        assertThat(releases.runtimeDefinition(
                testTenant.getId(),
                agentCode,
                v1.releasePid(),
                v1.deploymentPid()))
                .containsEntry("system_prompt", "Original prompt")
                .containsEntry("_agent_release_pid", v1.releasePid());
        assertThat(releases.listReleases(testTenant.getId(), definitionPid))
                .extracting(row -> ((Number) row.get("release_no")).intValue())
                .containsExactly(2, 1);

        PublishedRelease rollback = releases.deployRelease(
                testTenant.getId(),
                definitionPid,
                v1.releasePid(),
                testUser.getId());
        RuntimeBinding rolledBack = releases.requireActive(testTenant.getId(), agentCode);
        assertThat(rollback.releaseNo()).isEqualTo(1);
        assertThat(rolledBack.releasePid()).isEqualTo(v1.releasePid());
        assertThat(rolledBack.deploymentPid()).isEqualTo(v1.deploymentPid());
        assertThat(rolledBack.releaseSpec())
                .containsEntry("system_prompt", "Original prompt");
    }

    @Test
    @DisplayName("identical publish is idempotent and published content cannot mutate")
    void idempotentPublishAndDatabaseImmutability() {
        String definitionPid = UniqueIdGenerator.generate();
        String agentCode = "release_it_" + definitionPid.toLowerCase();
        insertDefinition(definitionPid, agentCode, "Pinned prompt");

        RuntimeBinding initial = releases.requireActive(testTenant.getId(), agentCode);
        PublishedRelease same = releases.publish(
                testTenant.getId(),
                definitionPid,
                testUser.getId());

        assertThat(same.created()).isFalse();
        assertThat(same.releaseNo()).isEqualTo(1);
        assertThat(same.releasePid()).isEqualTo(initial.releasePid());
        assertThat(releases.listReleases(testTenant.getId(), definitionPid)).hasSize(1);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ab_agent_release SET release_spec = '{}'::jsonb WHERE pid = ?",
                initial.releasePid()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("published releases cannot be deleted")
    void publishedReleaseCannotBeDeleted() {
        String definitionPid = UniqueIdGenerator.generate();
        String agentCode = "release_it_" + definitionPid.toLowerCase();
        insertDefinition(definitionPid, agentCode, "Deletion guard prompt");

        RuntimeBinding initial = releases.requireActive(testTenant.getId(), agentCode);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ab_agent_release WHERE pid = ?",
                initial.releasePid()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("suspended deployment fails closed and can be resumed without republishing")
    void deploymentLifecycleFailsClosed() {
        String definitionPid = UniqueIdGenerator.generate();
        String agentCode = "release_it_" + definitionPid.toLowerCase();
        insertDefinition(definitionPid, agentCode, "Lifecycle prompt");

        RuntimeBinding initial = releases.requireActive(testTenant.getId(), agentCode);
        releases.setDeploymentStatus(
                testTenant.getId(),
                agentCode,
                "active",
                "suspended",
                testUser.getId());

        assertThatThrownBy(() -> releases.requireActive(testTenant.getId(), agentCode))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active immutable release deployment");

        releases.setDeploymentStatus(
                testTenant.getId(),
                agentCode,
                "suspended",
                "active",
                testUser.getId());
        assertThat(releases.requireActive(testTenant.getId(), agentCode).releasePid())
                .isEqualTo(initial.releasePid());
    }

    private void insertDefinition(String definitionPid, String agentCode, String prompt) {
        jdbc.update(
                """
                INSERT INTO ab_agent_definition (
                    pid, tenant_id, agent_code, name, model, system_prompt,
                    tools, skills, knowledge_base_ids, execution_config,
                    status, visibility, created_by, updated_by, deleted_flag)
                VALUES (?, ?, ?, ?, 'qwen-plus', ?, 'dsl.query,dsl.command',
                        'analysis', '["kb-public"]'::jsonb,
                        '{"thinking_enabled": true}'::jsonb,
                        'active', 'tenant', ?, ?, FALSE)
                """,
                definitionPid,
                testTenant.getId(),
                agentCode,
                "Release integration agent",
                prompt,
                testUser.getId(),
                testUser.getId());
    }
}
