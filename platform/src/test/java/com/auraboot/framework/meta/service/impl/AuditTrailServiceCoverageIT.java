package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.entity.AuditTrail;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link AuditTrailService} — recordAudit (hash-chained), the
 * entity/pid/actor/command query surface, getLatestRecord, verifyChainIntegrity and
 * generateComplianceReport. Dedicated synthetic tenant; raw teardown.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AuditTrailService Coverage IT — record + query + chain verify")
class AuditTrailServiceCoverageIT {

    private static final long TENANT_ID = 991_300_001L;
    private static final long ACTOR_ID = 991_300_002L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private AuditTrailService auditTrailService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, ACTOR_ID, "audit-test-pid", "audit-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_audit_trail WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private AuditTrailEvent event(String entityPid, long entityId, String command) {
        return AuditTrailEvent.builder()
                .tenantId(TENANT_ID)
                .eventType("command")
                .entityType("audit_order")
                .entityId(entityId)
                .entityPid(entityPid)
                .commandCode(command)
                .operationType("create")
                .actorId(ACTOR_ID)
                .actorName("audit-test-user")
                .build();
    }

    @Test
    @DisplayName("recordAudit chains records; entity/pid/actor/command queries + latest return them")
    void recordAndQuery() {
        long e1 = seq.incrementAndGet();
        AuditTrail a1 = auditTrailService.recordAudit(event("apid_" + e1, e1, "audit:create"));
        assertNotNull(a1);
        long e2 = seq.incrementAndGet();
        AuditTrail a2 = auditTrailService.recordAudit(event("apid_" + e2, e2, "audit:create"));
        assertNotNull(a2);

        assertNotNull(auditTrailService.getLatestRecord(TENANT_ID));
        assertTrue(auditTrailService.getAuditTrail(TENANT_ID, "audit_order", e1).stream()
                .anyMatch(r -> r.getEntityId() != null && r.getEntityId() == e1));
        assertTrue(auditTrailService.getAuditTrailByPid(TENANT_ID, "audit_order", "apid_" + e1).size() >= 1);
        assertTrue(auditTrailService.getAuditByCommand(TENANT_ID, "audit:create").size() >= 2);
        assertNotNull(auditTrailService.getAuditByActor(
                TENANT_ID, ACTOR_ID, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("verifyChainIntegrity + generateComplianceReport over the tenant's records")
    void chainAndCompliance() {
        long e = seq.incrementAndGet();
        auditTrailService.recordAudit(event("apid_" + e, e, "audit:verify"));

        assertNotNull(auditTrailService.verifyChainIntegrity(TENANT_ID, 0L, Long.MAX_VALUE));
        assertNotNull(auditTrailService.generateComplianceReport(
                TENANT_ID, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60)));
    }
}
