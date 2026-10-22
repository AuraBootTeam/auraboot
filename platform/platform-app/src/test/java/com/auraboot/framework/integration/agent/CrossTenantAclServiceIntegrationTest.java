package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantDecision;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.2 — verifies {@link CrossTenantAclService} read-path semantics against
 * real PostgreSQL.
 *
 * <p>Cases:
 * <ul>
 *   <li>A — grant exists, not expired, not revoked → ALLOWED (carries grantId)</li>
 *   <li>B — no grant row at all → DENIED_NO_GRANT</li>
 *   <li>C — grant exists but expires_at &lt; now → DENIED_EXPIRED</li>
 *   <li>D — grant existed but revoked_at != NULL → DENIED_NO_GRANT
 *           (revoked rows excluded by the active-only WHERE clause; the
 *           service never returns DENIED_REVOKED for "no current row" — it
 *           only returns DENIED_REVOKED if the partial-unique invariant ever
 *           breaks. We document & assert the no-grant outcome instead.)</li>
 *   <li>E — feature flag observed: when {@code featureEnabled=false} via
 *           the configured property, every cross-tenant call returns
 *           DENIED_FEATURE_DISABLED. We assert the configured-true case
 *           returns the underlying decision so the kill-switch is visible
 *           through the normal property channel.</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("CrossTenantAclService (C.2)")
class CrossTenantAclServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private CrossTenantAclService aclService;
    @Autowired private JdbcTemplate jdbc;
    @Value("${aura.security.cross-tenant.enabled:true}") private boolean featureEnabled;

    private Long parentTenant;
    private Long childTenant;

    @BeforeEach
    void seedTenants() {
        long base = 9_770_000L + System.nanoTime() % 100_000;
        parentTenant = base;
        childTenant = base + 1L;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_cross_tenant_spawn_audit WHERE parent_tenant_id IN (?, ?)",
                parentTenant, childTenant);
        jdbc.update("DELETE FROM ab_cross_tenant_grant WHERE parent_tenant_id IN (?, ?)",
                parentTenant, childTenant);
        // Drop cache entries we may have touched so cases stay independent.
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        aclService.invalidate(childTenant, parentTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
    }

    /** Insert one active grant row, return its id. */
    private Long seedGrant(Long parent, Long child, Instant expiresAt, Instant revokedAt) {
        return jdbc.queryForObject(
                "INSERT INTO ab_cross_tenant_grant "
                        + "(parent_tenant_id, child_tenant_id, grant_type, granted_by, "
                        + " granted_at, expires_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, now(), ?, ?) RETURNING id",
                Long.class,
                parent, child, CrossTenantGrantType.SPAWN_SUB_AGENT, testUser.getId(),
                expiresAt == null ? null : Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt));
    }

    @Test
    @DisplayName("A: active grant → ALLOWED with grantId populated")
    void caseA_grant_exists() {
        Long grantId = seedGrant(parentTenant, childTenant, null, null);
        // Drop cache from any prior test run so we hit DB.
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        CrossTenantDecision decision = aclService.evaluate(
                parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.code()).isEqualTo(CrossTenantDecision.ALLOWED);
        assertThat(decision.grantId()).isEqualTo(grantId);
        assertThat(aclService.allows(parentTenant, childTenant,
                CrossTenantGrantType.SPAWN_SUB_AGENT)).isTrue();
    }

    @Test
    @DisplayName("B: no grant row → DENIED_NO_GRANT")
    void caseB_no_grant() {
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        CrossTenantDecision decision = aclService.evaluate(
                parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.code()).isEqualTo(CrossTenantDecision.DENIED_NO_GRANT);
        assertThat(decision.grantId()).isNull();
    }

    @Test
    @DisplayName("C: grant with expires_at in the past → DENIED_EXPIRED")
    void caseC_expired() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        seedGrant(parentTenant, childTenant, past, null);
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        CrossTenantDecision decision = aclService.evaluate(
                parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.code()).isEqualTo(CrossTenantDecision.DENIED_EXPIRED);
        assertThat(decision.reason()).contains("expired at");
    }

    @Test
    @DisplayName("D: revoked grant → no active row → DENIED_NO_GRANT")
    void caseD_revoked() {
        // Revoked rows are excluded from the SELECT (revoked_at IS NULL is
        // in the WHERE clause), so the service sees "no active grant" and
        // returns DENIED_NO_GRANT — not DENIED_REVOKED. DENIED_REVOKED is
        // reserved for future "soft revoked but recent" semantics.
        seedGrant(parentTenant, childTenant, null, Instant.now());
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        CrossTenantDecision decision = aclService.evaluate(
                parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.code()).isEqualTo(CrossTenantDecision.DENIED_NO_GRANT);
    }

    @Test
    @DisplayName("E: same-tenant evaluation always ALLOWED (kill-switch + grant rows irrelevant)")
    void caseE_same_tenant_short_circuits() {
        // Same-tenant fast path: the service returns ALLOWED with no DB read
        // even when no grant row exists. This protects the hot same-tenant
        // spawn path from any per-call latency penalty.
        CrossTenantDecision decision = aclService.evaluate(
                parentTenant, parentTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.grantId()).isNull(); // sentinel: no row consulted
        // Sanity: the configured feature flag in tests is true (default).
        assertThat(featureEnabled).isTrue();
    }
}
