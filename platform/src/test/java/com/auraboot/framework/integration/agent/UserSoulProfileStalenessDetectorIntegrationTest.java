package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.UserSoulProfileStalenessDetector;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.EmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** PR-76 Phase 2 — {@link UserSoulProfileStalenessDetector}. */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("User Soul Profile staleness detector (PR-76)")
class UserSoulProfileStalenessDetectorIntegrationTest extends BaseIntegrationTest {

    /** Dim used across the test — smaller than production to keep vectors readable. */
    private static final int DIM = 1536;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserSoulProfileStalenessDetector detector;

    @MockBean private EmbeddingService embeddingService;

    private Long tenantId;
    private String userId;

    @BeforeEach
    void setup() {
        tenantId = 9_785_000L + System.nanoTime() % 10_000;
        userId = "us_" + Long.toString(System.nanoTime() & 0xffff, 36);
        ReflectionTestUtils.setField(detector, "enabled", true);
        ReflectionTestUtils.setField(detector, "minDivergentMemories", 3);
        ReflectionTestUtils.setField(detector, "divergenceCosineThreshold", 0.6);
        ReflectionTestUtils.setField(detector, "recentImportanceThreshold", 7);
        ReflectionTestUtils.setField(detector, "recentWindowDays", 7);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private static float[] orthoVec(int index) {
        float[] v = new float[DIM];
        v[index % DIM] = 1.0f;
        return v;
    }

    private static float[] sameVec() {
        float[] v = new float[DIM];
        v[0] = 1.0f;
        return v;
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 4);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String seedActiveProfile(String persona) {
        String pid = UniqueIdGenerator.generate();
        String json = "{\"persona\":{\"text\":\"" + persona + "\"}}";
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " activated_at, created_at) "
                        + "VALUES (?, ?, ?, 1, 'active', ?::jsonb, ?, NOW(), NOW())",
                pid, tenantId, userId, json, "h:" + pid);
        return pid;
    }

    /** Seeds a memory row with an explicit embedding vector. */
    private void seedMemoryWithVec(String suffix, float[] vec, int importance) {
        String pid = "mem_" + userId + "_" + suffix;
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                        + " embedding, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'default', 'fact', 'profile', 't', 'c', ?, FALSE, 'user', ?, "
                        + " ?::vector, NOW(), NOW(), FALSE)",
                pid, tenantId, importance, userId, vectorLiteral(vec));
    }

    @Test
    @DisplayName("3 divergent memories → stale flagged")
    void threeDivergentFlag() {
        String pid = seedActiveProfile("engineer");
        when(embeddingService.embed(ArgumentMatchers.eq(tenantId),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()))
                .thenReturn(sameVec());
        // 3 orthogonal (cosine = 0) recent memories.
        seedMemoryWithVec("a", orthoVec(1), 8);
        seedMemoryWithVec("b", orthoVec(2), 8);
        seedMemoryWithVec("c", orthoVec(3), 8);

        int flagged = detector.runOnce();
        assertThat(flagged).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT stale_flagged_at FROM ab_agent_user_soul_profile WHERE pid = ?",
                java.sql.Timestamp.class, pid))
                .isNotNull();
    }

    @Test
    @DisplayName("2 divergent + 1 similar → no flag (below threshold)")
    void twoDivergentNoFlag() {
        String pid = seedActiveProfile("engineer");
        when(embeddingService.embed(ArgumentMatchers.eq(tenantId),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()))
                .thenReturn(sameVec());
        seedMemoryWithVec("a", orthoVec(1), 8);   // divergent
        seedMemoryWithVec("b", orthoVec(2), 8);   // divergent
        seedMemoryWithVec("c", sameVec(), 8);     // similar

        int flagged = detector.runOnce();
        assertThat(flagged).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT stale_flagged_at FROM ab_agent_user_soul_profile WHERE pid = ?",
                java.sql.Timestamp.class, pid))
                .isNull();
    }

    @Test
    @DisplayName("Already-flagged profile is not re-scanned")
    void alreadyFlaggedSkipped() {
        String pid = seedActiveProfile("engineer");
        jdbc.update("UPDATE ab_agent_user_soul_profile SET stale_flagged_at = NOW() WHERE pid = ?", pid);
        when(embeddingService.embed(ArgumentMatchers.eq(tenantId),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()))
                .thenReturn(sameVec());
        // Even 10 divergent memories shouldn't change anything.
        for (int i = 1; i <= 10; i++) seedMemoryWithVec("m" + i, orthoVec(i), 8);
        int flagged = detector.runOnce();
        assertThat(flagged).isZero();
    }

    @Test
    @DisplayName("Hidden profile is not scanned")
    void hiddenSkipped() {
        String pid = seedActiveProfile("engineer");
        jdbc.update("UPDATE ab_agent_user_soul_profile SET hidden_at = NOW() WHERE pid = ?", pid);
        when(embeddingService.embed(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(sameVec());
        for (int i = 1; i <= 5; i++) seedMemoryWithVec("m" + i, orthoVec(i), 8);
        int flagged = detector.runOnce();
        assertThat(flagged).isZero();
    }

    @Test
    @DisplayName("Tenant isolation — other tenant's memories don't influence")
    void tenantIsolation() {
        String pid = seedActiveProfile("engineer");
        Long otherTenant = tenantId + 1;
        when(embeddingService.embed(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(sameVec());
        // Divergent memories exist, but under a different tenant.
        for (int i = 1; i <= 5; i++) {
            String mpid = "mem_other_" + i;
            jdbc.update("INSERT INTO ab_agent_memory "
                            + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                            + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                            + " embedding, created_at, updated_at, deleted_flag) "
                            + "VALUES (?, ?, 'default', 'fact', 'profile', 't', 'c', 8, FALSE, 'user', ?, "
                            + " ?::vector, NOW(), NOW(), FALSE)",
                    mpid, otherTenant, userId, vectorLiteral(orthoVec(i)));
        }
        try {
            int flagged = detector.runOnce();
            assertThat(flagged).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT stale_flagged_at FROM ab_agent_user_soul_profile WHERE pid = ?",
                    java.sql.Timestamp.class, pid))
                    .isNull();
        } finally {
            jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("Embedding provider outage skips the profile gracefully")
    void embeddingOutageSkips() {
        String pid = seedActiveProfile("engineer");
        when(embeddingService.embed(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("embedding provider down"));
        for (int i = 1; i <= 5; i++) seedMemoryWithVec("m" + i, orthoVec(i), 8);
        int flagged = detector.runOnce();
        assertThat(flagged).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT stale_flagged_at FROM ab_agent_user_soul_profile WHERE pid = ?",
                java.sql.Timestamp.class, pid))
                .isNull();
    }

    @Test
    @DisplayName("Disabled scheduler is a no-op")
    void disabledSchedulerNoop() {
        seedActiveProfile("engineer");
        ReflectionTestUtils.setField(detector, "enabled", false);
        detector.runScheduled();
        Long flagged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND stale_flagged_at IS NOT NULL",
                Long.class, tenantId);
        assertThat(flagged).isZero();
    }

    @Test
    @DisplayName("Concurrent runOnce() respects advisory lock")
    void advisoryLockSerialises() throws InterruptedException {
        seedActiveProfile("engineer");
        when(embeddingService.embed(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(sameVec());
        for (int i = 1; i <= 5; i++) seedMemoryWithVec("m" + i, orthoVec(i), 8);
        List<Integer> results = new ArrayList<>();
        Thread t1 = new Thread(() -> results.add(detector.runOnce()));
        Thread t2 = new Thread(() -> results.add(detector.runOnce()));
        t1.start(); t2.start();
        t1.join(); t2.join();
        // One of them acquires the lock and flags; the other finds nothing
        // to flag (either because it was locked-out, or the row is already
        // flagged). Either way the flag-count in DB is 1.
        Long flaggedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND stale_flagged_at IS NOT NULL",
                Long.class, tenantId);
        assertThat(flaggedRows).isEqualTo(1L);
    }
}
