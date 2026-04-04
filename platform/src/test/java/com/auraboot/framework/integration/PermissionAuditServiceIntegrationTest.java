package com.auraboot.framework.integration;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.auraboot.framework.permission.service.PermissionAuditService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for PermissionAuditService.
 *
 * <p>Uses real PostgreSQL — not H2. MetaContext is pre-populated by BaseIntegrationTest.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PermissionAuditServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PermissionAuditService permissionAuditService;

    private static final String RUN_ID = String.valueOf(System.currentTimeMillis() % 100000);
    private static final long TEST_MEMBER_ID = 9999000L + Long.parseLong(String.valueOf(System.currentTimeMillis() % 1000));
    private static final String TEST_RESOURCE = "test_audit_resource_" + System.currentTimeMillis() % 100000;

    // ======================================================================
    // Helpers
    // ======================================================================

    private PermissionExplanation denyExplanation(Long memberId, String resource, String action) {
        EvaluationStep rbacDeny = new EvaluationStep("RolePermission", EvaluationVerdict.DENY,
                "No role grants " + action + " on " + resource);
        return new PermissionExplanation(memberId, resource, action, null, false, List.of(rbacDeny));
    }

    // ======================================================================
    // logEvaluation — only DENY decisions should be persisted
    // ======================================================================

    @Test
    @Order(1)
    void logEvaluation_denyDecision_isPersisted() throws InterruptedException {
        Long tenantId = getTenantId();
        PermissionExplanation explanation = denyExplanation(TEST_MEMBER_ID, TEST_RESOURCE, "view");

        permissionAuditService.logEvaluation(tenantId, explanation);

        // @Async — wait briefly for the write to complete
        Thread.sleep(300);

        List<PermissionAuditLog> logs = permissionAuditService.getRecentLogs(tenantId, 50);

        PermissionAuditLog entry = logs.stream()
                .filter(l -> TEST_RESOURCE.equals(l.getResourceCode()) && TEST_MEMBER_ID == l.getMemberId())
                .findFirst()
                .orElse(null);

        assertThat(entry).as("Audit log entry should be persisted for DENY").isNotNull();
        assertThat(entry.getTenantId()).isEqualTo(tenantId);
        assertThat(entry.getMemberId()).isEqualTo(TEST_MEMBER_ID);
        assertThat(entry.getResourceCode()).isEqualTo(TEST_RESOURCE);
        assertThat(entry.getActionCode()).isEqualTo("view");
        assertThat(entry.getResult()).isFalse();
        assertThat(entry.getReason()).isNotBlank();
        assertThat(entry.getEvaluationTrace()).isNotEmpty();
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    @Order(2)
    void logEvaluation_allowDecision_isNotPersisted() throws InterruptedException {
        Long tenantId = getTenantId();
        EvaluationStep allowStep = new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "granted");
        PermissionExplanation allowExplanation = new PermissionExplanation(
                TEST_MEMBER_ID, TEST_RESOURCE + "_allow", "edit", null, true, List.of(allowStep));

        permissionAuditService.logEvaluation(tenantId, allowExplanation);
        Thread.sleep(300);

        List<PermissionAuditLog> logs = permissionAuditService.getLogsByResource(
                tenantId, TEST_RESOURCE + "_allow", 50);

        assertThat(logs).as("ALLOW decisions must NOT be logged").isEmpty();
    }

    // ======================================================================
    // getLogsByMember
    // ======================================================================

    @Test
    @Order(3)
    void getLogsByMember_returnsOnlyThatMembersEntries() throws InterruptedException {
        Long tenantId = getTenantId();
        long otherMember = TEST_MEMBER_ID + 1;

        permissionAuditService.logEvaluation(tenantId,
                denyExplanation(TEST_MEMBER_ID, TEST_RESOURCE + "_m1", "delete"));
        permissionAuditService.logEvaluation(tenantId,
                denyExplanation(otherMember, TEST_RESOURCE + "_m2", "delete"));
        Thread.sleep(300);

        List<PermissionAuditLog> memberLogs =
                permissionAuditService.getLogsByMember(tenantId, TEST_MEMBER_ID, 100);

        assertThat(memberLogs).isNotEmpty();
        memberLogs.forEach(l ->
                assertThat(l.getMemberId())
                        .as("All entries must belong to TEST_MEMBER_ID")
                        .isEqualTo(TEST_MEMBER_ID));
    }

    // ======================================================================
    // getLogsByResource
    // ======================================================================

    @Test
    @Order(4)
    void getLogsByResource_returnsOnlyThatResource() throws InterruptedException {
        Long tenantId = getTenantId();
        String specificResource = TEST_RESOURCE + "_specific_" + RUN_ID;

        permissionAuditService.logEvaluation(tenantId,
                denyExplanation(TEST_MEMBER_ID, specificResource, "create"));
        Thread.sleep(300);

        List<PermissionAuditLog> resourceLogs =
                permissionAuditService.getLogsByResource(tenantId, specificResource, 100);

        assertThat(resourceLogs).isNotEmpty();
        resourceLogs.forEach(l ->
                assertThat(l.getResourceCode())
                        .as("All entries must match specificResource")
                        .isEqualTo(specificResource));
    }

    // ======================================================================
    // limit enforcement
    // ======================================================================

    @Test
    @Order(5)
    void getRecentLogs_respectsLimit() throws InterruptedException {
        Long tenantId = getTenantId();
        // Seed a few extra entries
        for (int i = 0; i < 3; i++) {
            permissionAuditService.logEvaluation(tenantId,
                    denyExplanation(TEST_MEMBER_ID, TEST_RESOURCE + "_lim" + i, "view"));
        }
        Thread.sleep(300);

        List<PermissionAuditLog> logs = permissionAuditService.getRecentLogs(tenantId, 2);
        assertThat(logs).hasSizeLessThanOrEqualTo(2);
    }

    // ======================================================================
    // Helper
    // ======================================================================

    private Long getTenantId() {
        return testTenant != null ? testTenant.getId() : 1L;
    }
}
