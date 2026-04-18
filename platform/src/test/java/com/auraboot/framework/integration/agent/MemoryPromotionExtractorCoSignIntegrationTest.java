package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryPromotionExtractor;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-66 follow-up to the Phase 1 extractor tests: exercise the newly
 * functional {@code implicit_co_sign} strategy now that
 * {@code ab_agent_memory_access_log} backs {@code countCoSigners}.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryPromotionExtractor — implicit_co_sign (PR-66)")
class MemoryPromotionExtractorCoSignIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryPromotionExtractor extractor;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_795_000L + System.nanoTime() % 10_000;
        ReflectionTestUtils.setField(extractor, "importanceSpikeEnabled", false);
        ReflectionTestUtils.setField(extractor, "minUsersPerTenant", 3);
        ReflectionTestUtils.setField(extractor, "minSimilarity", 0.85d);
        ReflectionTestUtils.setField(extractor, "rationaleEnabled", false);
    }

    @AfterEach
    void cleanup() {
        // access log is owned by pids; clean both.
        jdbc.update("DELETE FROM ab_agent_memory_access_log WHERE memory_pid IN "
                + "(SELECT pid FROM ab_agent_memory WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    /** Seed a shareable high-importance user memory owned by authorUserId. */
    private String seedSharedMemory(String authorUserId) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'default', 'fact', 'ops', 't', 'c', 8, TRUE, 'user', ?, "
                        + "NOW(), NOW(), FALSE)",
                pid, tenantId, authorUserId);
        return pid;
    }

    private void seedAccess(String memoryPid, String userId, String ageInterval) {
        // PR-73: tenant_id now NOT NULL on ab_agent_memory_access_log.
        jdbc.update("INSERT INTO ab_agent_memory_access_log "
                        + "  (memory_pid, tenant_id, user_id, access_day, access_count, first_seen_at, last_seen_at) "
                        + "VALUES (?, ?, ?, (NOW() " + ageInterval + ")::date, 1, NOW() " + ageInterval
                        + ", NOW() " + ageInterval + ")",
                memoryPid, tenantId, userId);
    }

    @Test
    @DisplayName("3 distinct co-signers within 90d → 1 implicit_co_sign proposal")
    void coSign_threeUsers() {
        String pid = seedSharedMemory("author1");
        seedAccess(pid, "u2", "- INTERVAL '3 days'");
        seedAccess(pid, "u3", "- INTERVAL '10 days'");
        seedAccess(pid, "u4", "- INTERVAL '60 days'");

        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reason_code, confidence_score, reason_detail::text AS detail "
                        + "FROM ab_agent_memory_promotion WHERE tenant_id = ? AND reason_code = 'implicit_co_sign'",
                tenantId);
        assertThat(row.get("reason_code")).isEqualTo("implicit_co_sign");
        assertThat(((Number) row.get("confidence_score")).doubleValue()).isGreaterThanOrEqualTo(0.60d);
        String detail = (String) row.get("detail");
        assertThat(detail).contains("co_signer_count").contains("co_signer_user_ids");
    }

    @Test
    @DisplayName("access rows older than 90 days do not count toward co-sign threshold")
    void coSign_ignoresStaleAccess() {
        String pid = seedSharedMemory("author1");
        seedAccess(pid, "u2", "- INTERVAL '3 days'");
        seedAccess(pid, "u3", "- INTERVAL '100 days'");
        seedAccess(pid, "u4", "- INTERVAL '120 days'");

        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isZero();
    }

    @Test
    @DisplayName("author's own access does not count as co-sign")
    void coSign_excludesAuthor() {
        String pid = seedSharedMemory("author1");
        // author read it multiple times — irrelevant
        seedAccess(pid, "author1", "- INTERVAL '1 day'");
        // only 2 true co-signers — below threshold
        seedAccess(pid, "u2", "- INTERVAL '2 days'");
        seedAccess(pid, "u3", "- INTERVAL '5 days'");

        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isZero();
    }
}
