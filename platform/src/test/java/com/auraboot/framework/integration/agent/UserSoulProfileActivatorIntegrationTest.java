package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.UserSoulProfileActivator;
import com.auraboot.framework.agent.service.UserSoulProfileDeriver;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-76 Phase 2 — {@link UserSoulProfileActivator}. */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("User Soul Profile activator (PR-76)")
class UserSoulProfileActivatorIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserSoulProfileActivator activator;
    @Autowired private UserSoulProfileDeriver deriver;

    private Long tenantId;
    private String userId;

    @BeforeEach
    void setup() {
        tenantId = 9_770_000L + System.nanoTime() % 10_000;
        userId = "ua_" + Long.toString(System.nanoTime() & 0xffff, 36);
        ReflectionTestUtils.setField(activator, "enabled", true);
        ReflectionTestUtils.setField(activator, "shadowPeriodHours", 24);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String seedProfile(Long tid, String uid, int version, String status,
                               String createdAtInterval) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW() " + createdAtInterval + ")",
                pid, tid, uid, version, status, "{}", "h:" + pid);
        return pid;
    }

    @Test
    @DisplayName("DRAFT >24h → ACTIVE; previous ACTIVE → SUPERSEDED")
    void activatesOldDraft() {
        String prevActive = seedProfile(tenantId, userId, 1, "ACTIVE", "- INTERVAL '5 days'");
        String oldDraft = seedProfile(tenantId, userId, 2, "DRAFT", "- INTERVAL '2 days'");

        int activated = activator.runOnce();
        assertThat(activated).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_user_soul_profile WHERE pid = ?", String.class, oldDraft))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_user_soul_profile WHERE pid = ?", String.class, prevActive))
                .isEqualTo("SUPERSEDED");
        // Exactly one ACTIVE row remains for this user.
        Long activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Long.class, tenantId, userId);
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("DRAFT <24h stays DRAFT")
    void skipsYoungDraft() {
        String freshDraft = seedProfile(tenantId, userId, 1, "DRAFT", "- INTERVAL '2 hours'");
        int activated = activator.runOnce();
        assertThat(activated).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_user_soul_profile WHERE pid = ?", String.class, freshDraft))
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Only one ACTIVE per (tenant, user) after activation")
    void singleActiveInvariant() {
        seedProfile(tenantId, userId, 1, "ACTIVE", "- INTERVAL '10 days'");
        seedProfile(tenantId, userId, 2, "DRAFT", "- INTERVAL '3 days'");
        activator.runOnce();
        Long activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Long.class, tenantId, userId);
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Hidden DRAFT not activated")
    void hiddenDraftSkipped() {
        String pid = seedProfile(tenantId, userId, 1, "DRAFT", "- INTERVAL '3 days'");
        jdbc.update("UPDATE ab_agent_user_soul_profile SET hidden_at = NOW() WHERE pid = ?", pid);
        int activated = activator.runOnce();
        assertThat(activated).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_user_soul_profile WHERE pid = ?", String.class, pid))
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Disabled scheduler is a no-op")
    void disabledSchedulerNoop() {
        seedProfile(tenantId, userId, 1, "DRAFT", "- INTERVAL '3 days'");
        ReflectionTestUtils.setField(activator, "enabled", false);
        activator.runScheduled();
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND status = 'ACTIVE'",
                Long.class, tenantId);
        assertThat(active).isZero();
    }

    @Test
    @DisplayName("Concurrent runOnce() respects advisory lock — never duplicates")
    void advisoryLockSerialises() throws InterruptedException {
        seedProfile(tenantId, userId, 1, "DRAFT", "- INTERVAL '2 days'");
        Thread t1 = new Thread(activator::runOnce);
        Thread t2 = new Thread(activator::runOnce);
        t1.start(); t2.start();
        t1.join(); t2.join();
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Long.class, tenantId, userId);
        assertThat(active).isEqualTo(1L);
    }

    @Test
    @DisplayName("Deriver + activator round-trip produces ACTIVE profile")
    void deriverActivatorRoundTrip() {
        ReflectionTestUtils.setField(deriver, "enabled", true);
        ReflectionTestUtils.setField(deriver, "minMemories", 3);
        ReflectionTestUtils.setField(deriver, "lookBackDays", 90);
        ReflectionTestUtils.setField(deriver, "llmEnabled", false);

        String tag = "udar" + Long.toString(System.nanoTime() & 0xfffff, 36) + "_";
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO ab_agent_memory "
                            + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                            + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                            + " created_at, updated_at, deleted_flag) "
                            + "VALUES (?, ?, 'default', 'fact', 'profile', 't', 'c', 8, FALSE, 'user', ?, NOW(), NOW(), FALSE)",
                    tag + i, tenantId, userId);
        }
        var r = deriver.deriveForUser(tenantId, userId);
        assertThat(r.outcome()).isEqualTo(UserSoulProfileDeriver.Outcome.DRAFTED);

        // Age the row past the shadow window so the activator picks it up.
        jdbc.update("UPDATE ab_agent_user_soul_profile SET created_at = NOW() - INTERVAL '2 days' WHERE pid = ?",
                r.profilePid());

        int activated = activator.runOnce();
        assertThat(activated).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_user_soul_profile WHERE pid = ?", String.class, r.profilePid()))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Returns count of activations per tick")
    void returnsCount() {
        String otherUser = userId + "_b";
        seedProfile(tenantId, userId, 1, "DRAFT", "- INTERVAL '2 days'");
        seedProfile(tenantId, otherUser, 1, "DRAFT", "- INTERVAL '3 days'");
        int activated = activator.runOnce();
        assertThat(activated).isEqualTo(2);

        List<Map<String, Object>> actives = jdbc.queryForList(
                "SELECT user_id FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND status = 'ACTIVE'",
                tenantId);
        assertThat(actives).hasSize(2);
    }
}
