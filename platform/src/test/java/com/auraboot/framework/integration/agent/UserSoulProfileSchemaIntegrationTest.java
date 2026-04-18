package com.auraboot.framework.integration.agent;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-75 Phase 1 — schema invariants for {@code ab_agent_user_soul_profile}. */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("User Soul Profile schema (PR-75)")
class UserSoulProfileSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String tag;

    @BeforeEach
    void setup() {
        tenantId = 9_750_000L + System.nanoTime() % 10_000;
        tag = "usp" + Long.toString(System.nanoTime() & 0xfffff, 36) + "_";
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
    }

    private String insert(String pidSuffix, String userId, String status, int version) {
        String pid = tag + pidSuffix;
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, '{}'::jsonb, 'hash_" + pidSuffix + "', NOW())",
                pid, tenantId, userId, version, status);
        return pid;
    }

    @Test
    @DisplayName("Happy path: DRAFT row inserts and reads back")
    void happyPath() {
        String p = insert("a1", "u1", "DRAFT", 1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, version FROM ab_agent_user_soul_profile WHERE pid = ?", p);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("CHECK status: invalid status rejected")
    void invalidStatusRejected() {
        assertThatThrownBy(() -> insert("a2", "u2", "BOGUS", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK confidence: values outside [0,1] rejected")
    void invalidConfidenceRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " derivation_confidence, created_at) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT', '{}'::jsonb, 'h', 1.5, NOW())",
                tag + "a3", tenantId, "u3", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("UNIQUE partial index: two ACTIVE rows per (tenant,user) rejected")
    void uniqueActivePerUser() {
        insert("b1", "u1", "ACTIVE", 1);
        assertThatThrownBy(() -> insert("b2", "u1", "ACTIVE", 2))
                .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Multiple DRAFT rows allowed per (tenant,user); ACTIVE+DRAFT coexist")
    void draftsAndOneActiveCoexist() {
        insert("c1", "u1", "ACTIVE", 1);
        insert("c2", "u1", "DRAFT", 2);
        insert("c3", "u1", "DRAFT", 3);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = 'u1'",
                Long.class, tenantId);
        assertThat(count).isEqualTo(3L);
    }

    @Test
    @DisplayName("Duplicate pid rejected by UNIQUE constraint")
    void duplicatePidRejected() {
        insert("d1", "u1", "DRAFT", 1);
        assertThatThrownBy(() -> insert("d1", "u1", "DRAFT", 2))
                .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Required indexes exist on the table")
    void indexesPresent() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'ab_agent_user_soul_profile'",
                String.class);
        assertThat(indexes).contains(
                "uq_user_soul_profile_active",
                "idx_user_soul_profile_tenant_user",
                "idx_user_soul_profile_stale",
                "idx_user_soul_profile_created"
        );
    }
}
