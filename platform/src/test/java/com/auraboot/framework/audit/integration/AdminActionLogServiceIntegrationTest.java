package com.auraboot.framework.audit.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminActionLog;
import com.auraboot.framework.audit.service.AdminActionLogService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for env-layering backlog #3 — lock/unlock audit log persistence.
 *
 * <p>Asserts:
 * <ol>
 *   <li>Direct {@link AdminActionLogService#record} call writes a row with all
 *       fields populated, defaults filled from {@link MetaContext}, and JSONB
 *       payload round-tripping.</li>
 *   <li>Lock + unlock through {@code EnvironmentService} produces the
 *       expected {@code environment.lock} / {@code environment.unlock}
 *       audit trail.</li>
 *   <li>Audit-write failure must not propagate to the caller (fire-and-forget
 *       contract). Smoke-tested by passing a deliberately invalid record
 *       (blank actionType) — record() returns silently.</li>
 *   <li>{@link AdminActionLogService#byResource} filters by tenant + resource.</li>
 * </ol>
 */
class AdminActionLogServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdminActionLogService adminActionLogService;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @AfterEach
    void clearEnv() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void record_persistsFieldsAndFillsDefaults() throws Exception {
        Long tenantId = 999_001L;
        Long actorId = 12345L;
        MetaContext.setCurrentTenantId(tenantId);
        MetaContext.setCurrentUserId(actorId);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("before", "draft");
        payload.put("after", "applied");

        String resourcePid = "rsrc-" + UniqueIdGenerator.generate();
        adminActionLogService.record(AdminActionLog.builder()
                .actionType("test.action")
                .resourceType("test-resource")
                .resourcePid(resourcePid)
                .success(true)
                .reason("integration-test")
                .payload(payload)
                .build());

        List<AdminActionLog> rows = adminActionLogService.byResource(
                tenantId, "test-resource", resourcePid, 10);
        assertThat(rows).hasSize(1);
        AdminActionLog row = rows.get(0);
        assertThat(row.getActionType()).isEqualTo("test.action");
        assertThat(row.getResourceType()).isEqualTo("test-resource");
        assertThat(row.getResourcePid()).isEqualTo(resourcePid);
        assertThat(row.getSuccess()).isTrue();
        assertThat(row.getReason()).isEqualTo("integration-test");
        assertThat(row.getActorUserId()).isEqualTo(actorId);
        assertThat(row.getActorType()).isEqualTo("user");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getPid()).isNotBlank();
        // payload round-trip
        assertThat(row.getPayload()).isNotNull();
        assertThat(row.getPayload().get("before").asText()).isEqualTo("draft");
        assertThat(row.getPayload().get("after").asText()).isEqualTo("applied");
    }

    @Test
    void record_blankActionType_silentlyDrops() {
        Long tenantId = 999_002L;
        MetaContext.setCurrentTenantId(tenantId);
        MetaContext.setCurrentUserId(67890L);

        // Should not throw — fire-and-forget contract.
        adminActionLogService.record(AdminActionLog.builder()
                .actionType("")
                .resourceType("test-resource")
                .resourcePid("anything")
                .success(true)
                .build());

        // No rows should be persisted for this tenant.
        assertThat(adminActionLogService.recentByTenant(tenantId, 10)).isEmpty();
    }

    @Test
    void environmentLockUnlock_writesAuditTrail() {
        Long tenantId = 999_003L;
        Long actorId = 13579L;
        MetaContext.setCurrentTenantId(tenantId);
        MetaContext.setCurrentUserId(actorId);

        // Create an environment to lock.
        EnvironmentRequest req = new EnvironmentRequest();
        req.setCode("audit-test-" + UniqueIdGenerator.generate().substring(0, 8));
        req.setName("Audit Test Env");
        req.setDescription("created by AdminActionLogServiceIntegrationTest");
        environmentService.create(req, tenantId, actorId);

        Environment env = environmentMapper.findByTenantAndCode(tenantId, req.getCode());
        assertThat(env).isNotNull();
        String envPid = env.getPid();

        // Lock with reason.
        environmentService.lock(envPid, tenantId, actorId, "freezing for prod cut");

        List<AdminActionLog> afterLock = adminActionLogService.byResource(
                tenantId, "environment", envPid, 10);
        assertThat(afterLock).hasSize(1);
        AdminActionLog lockEntry = afterLock.get(0);
        assertThat(lockEntry.getActionType()).isEqualTo("environment.lock");
        assertThat(lockEntry.getActorUserId()).isEqualTo(actorId);
        assertThat(lockEntry.getReason()).isEqualTo("freezing for prod cut");
        assertThat(lockEntry.getSuccess()).isTrue();

        // Unlock.
        environmentService.unlock(envPid, tenantId, actorId, "release shipped");

        List<AdminActionLog> afterUnlock = adminActionLogService.byResource(
                tenantId, "environment", envPid, 10);
        assertThat(afterUnlock).hasSize(2);
        // Newest first.
        AdminActionLog unlockEntry = afterUnlock.get(0);
        assertThat(unlockEntry.getActionType()).isEqualTo("environment.unlock");
        assertThat(unlockEntry.getReason()).isEqualTo("release shipped");
        assertThat(unlockEntry.getSuccess()).isTrue();
    }
}
