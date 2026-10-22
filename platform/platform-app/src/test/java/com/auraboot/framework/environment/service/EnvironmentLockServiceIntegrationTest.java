package com.auraboot.framework.environment.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.dto.EnvironmentResponse;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for EnvironmentService.lock / unlock methods added by env-layering PoC.
 * Verifies real-DB persistence of audit fields, idempotency rejection, reason validation.
 *
 * Companion baseline EnvironmentServiceTest (16 Mockito unit cases) MUST stay green.
 */
class EnvironmentLockServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @Test
    void lock_freshEnv_setsLockedFieldsAndStores() {
        EnvironmentResponse env = createEnvironment("dev_" + shortId());

        EnvironmentResponse locked = environmentService.lock(
                env.getPid(), testTenant.getId(), testUser.getId(), "regulatory freeze");

        assertThat(locked.getIsLocked()).isTrue();
        assertThat(locked.getLockedBy()).isEqualTo(testUser.getId());
        assertThat(locked.getLockedAt()).isNotNull();
        assertThat(locked.getLockedReason()).isEqualTo("regulatory freeze");

        Environment reloaded = environmentMapper.selectById(findIdByPid(env.getPid()));
        assertThat(reloaded.getIsLocked()).isTrue();
        assertThat(reloaded.getLockedReason()).isEqualTo("regulatory freeze");
    }

    @Test
    void lock_alreadyLockedEnv_throws() {
        EnvironmentResponse env = createEnvironment("staging_" + shortId());
        environmentService.lock(env.getPid(), testTenant.getId(), testUser.getId(), "first lock");

        assertThatThrownBy(() ->
                environmentService.lock(env.getPid(), testTenant.getId(), testUser.getId(), "second lock"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already locked");
    }

    @Test
    void lock_blankReason_throws() {
        EnvironmentResponse env = createEnvironment("prod_" + shortId());

        assertThatThrownBy(() ->
                environmentService.lock(env.getPid(), testTenant.getId(), testUser.getId(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void lock_envNotFound_throws() {
        assertThatThrownBy(() ->
                environmentService.lock("no-such-pid", testTenant.getId(), testUser.getId(), "any"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void unlock_lockedEnv_clearsAuditFields() {
        EnvironmentResponse env = createEnvironment("dev_" + shortId());
        environmentService.lock(env.getPid(), testTenant.getId(), testUser.getId(), "freeze");

        EnvironmentResponse unlocked = environmentService.unlock(
                env.getPid(), testTenant.getId(), testUser.getId(), "regulatory cleared");

        assertThat(unlocked.getIsLocked()).isFalse();
        assertThat(unlocked.getLockedBy()).isNull();
        assertThat(unlocked.getLockedAt()).isNull();
        assertThat(unlocked.getLockedReason()).isNull();

        Environment reloaded = environmentMapper.selectById(findIdByPid(env.getPid()));
        assertThat(reloaded.getIsLocked()).isFalse();
        assertThat(reloaded.getLockedBy()).isNull();
        assertThat(reloaded.getLockedReason()).isNull();
    }

    @Test
    void unlock_alreadyUnlockedEnv_throws() {
        EnvironmentResponse env = createEnvironment("dev_" + shortId());

        assertThatThrownBy(() ->
                environmentService.unlock(env.getPid(), testTenant.getId(), testUser.getId(), "no-op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not locked");
    }

    @Test
    void unlock_blankReason_throws() {
        EnvironmentResponse env = createEnvironment("staging_" + shortId());
        environmentService.lock(env.getPid(), testTenant.getId(), testUser.getId(), "freeze");

        assertThatThrownBy(() ->
                environmentService.unlock(env.getPid(), testTenant.getId(), testUser.getId(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    private EnvironmentResponse createEnvironment(String code) {
        EnvironmentRequest req = new EnvironmentRequest();
        req.setCode(code);
        req.setName(code);
        req.setIsDefault(false);
        req.setSortOrder(0);
        return environmentService.create(req, testTenant.getId(), testUser.getId());
    }

    private Long findIdByPid(String pid) {
        Environment probe = environmentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Environment>()
                        .eq("pid", pid)
                        .eq("tenant_id", testTenant.getId()));
        return probe != null ? probe.getId() : null;
    }

    private static String shortId() {
        return UniqueIdGenerator.generate().substring(0, 8).toLowerCase();
    }
}
