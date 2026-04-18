package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.service.BifRecorder;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-20: BIF v1.1.2 profile_id + channel columns persist correctly and
 * survive the JSONB round-trip through BifRecorder.
 */
@Commit
@DisplayName("BIF v1.1.2 profile_id + channel (PR-20)")
class BifV11ExtensionIntegrationTest extends BaseIntegrationTest {

    @Autowired private BifRecorder recorder;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_100_000L + System.nanoTime() % 1_000_000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_bif WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("BifRecorder persists profile_id + channel when present")
    void persists_profile_and_channel() {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("query").object("crm_lead").riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.9, 0.85))
                .candidateSkillsMode("hint")
                .profileId("prof_sales_rep")
                .channel("mobile")
                .build();

        String pid = recorder.record(tenantId, "show my leads", bif, null, "sess-20");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT profile_id, channel FROM ab_agent_bif WHERE pid = ?", pid);
        assertThat(row.get("profile_id")).isEqualTo("prof_sales_rep");
        assertThat(row.get("channel")).isEqualTo("mobile");
    }

    @Test
    @DisplayName("BifRecorder tolerates null profile_id + null channel (built-in AuraBot)")
    void tolerates_null_extensions() {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("query").object("crm_lead").riskLevel("L0")
                .confidence(ConfidenceScore.of(0.9, 0.85))
                .candidateSkillsMode("hint")
                .build();

        String pid = recorder.record(tenantId, "query", bif, null, null);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT profile_id, channel FROM ab_agent_bif WHERE pid = ?", pid);
        assertThat(row.get("profile_id")).isNull();
        assertThat(row.get("channel")).isNull();
    }
}
