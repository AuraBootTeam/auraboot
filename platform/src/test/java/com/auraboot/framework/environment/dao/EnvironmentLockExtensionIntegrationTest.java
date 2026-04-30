package com.auraboot.framework.environment.dao;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Environment entity extensions added by env-layering PoC:
 * parentPid, isLocked, lockedBy, lockedAt, lockedReason.
 *
 * Companion baseline EnvironmentServiceTest (Mockito unit) MUST stay green; this test
 * exercises the same entity persisted through real PostgreSQL.
 */
class EnvironmentLockExtensionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EnvironmentMapper environmentMapper;

    @Test
    void newlyCreatedEnvironment_hasUnlockedDefaults() {
        Environment env = newBareEnvironment("dev_" + shortId());
        environmentMapper.insert(env);

        Environment loaded = environmentMapper.selectById(env.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getIsLocked()).isFalse();
        assertThat(loaded.getLockedBy()).isNull();
        assertThat(loaded.getLockedAt()).isNull();
        assertThat(loaded.getLockedReason()).isNull();
        assertThat(loaded.getParentPid()).isNull();
    }

    @Test
    void lockedFields_persistAcrossReload() {
        Environment env = newBareEnvironment("staging_" + shortId());
        environmentMapper.insert(env);

        Instant lockTime = Instant.now();
        env.setIsLocked(true);
        env.setLockedBy(testUser.getId());
        env.setLockedAt(Date.from(lockTime));
        env.setLockedReason("regulatory freeze");
        environmentMapper.updateById(env);

        Environment loaded = environmentMapper.selectById(env.getId());
        assertThat(loaded.getIsLocked()).isTrue();
        assertThat(loaded.getLockedBy()).isEqualTo(testUser.getId());
        assertThat(loaded.getLockedAt()).isCloseTo(Date.from(lockTime), 1000L);
        assertThat(loaded.getLockedReason()).isEqualTo("regulatory freeze");
    }

    @Test
    void parentPid_linksChildEnvironmentToParent() {
        Environment parent = newBareEnvironment("parent_" + shortId());
        environmentMapper.insert(parent);

        Environment child = newBareEnvironment("child_" + shortId());
        child.setParentPid(parent.getPid());
        environmentMapper.insert(child);

        Environment loadedChild = environmentMapper.selectById(child.getId());
        assertThat(loadedChild.getParentPid()).isEqualTo(parent.getPid());
    }

    @Test
    void parentPid_isNullForRootEnvironment() {
        Environment root = newBareEnvironment("root_" + shortId());
        environmentMapper.insert(root);

        Environment loaded = environmentMapper.selectById(root.getId());
        assertThat(loaded.getParentPid()).isNull();
    }

    private Environment newBareEnvironment(String code) {
        Environment e = new Environment();
        e.setPid(UniqueIdGenerator.generate());
        e.setTenantId(testTenant.getId());
        e.setCode(code);
        e.setName(code);
        e.setStatus("active");
        e.setIsDefault(false);
        e.setSortOrder(0);
        e.setDeletedFlag(false);
        e.setCreatedAt(new Date());
        e.setUpdatedAt(new Date());
        return e;
    }

    private static String shortId() {
        return UniqueIdGenerator.generate().substring(0, 8).toLowerCase();
    }
}
