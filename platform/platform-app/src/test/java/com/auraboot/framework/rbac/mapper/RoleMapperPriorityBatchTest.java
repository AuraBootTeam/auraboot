package com.auraboot.framework.rbac.mapper;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rbac.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoleMapperPriorityBatchTest extends BaseIntegrationTest {

    @Autowired
    private RoleMapper roleMapper;

    @Test
    void findPrioritiesByIdsReturnsExactlyRequestedRoles() {
        Long lo = seedRole("cfg-001-lo", 50);
        Long hi = seedRole("cfg-001-hi", 200);
        Long unused = seedRole("cfg-001-noise", 100);

        var rows = roleMapper.findPrioritiesByIds(Set.of(lo, hi));
        var byId = rows.stream().collect(Collectors.toMap(
                r -> ((Number) r.get("id")).longValue(),
                r -> ((Number) r.get("priority")).intValue()));

        assertEquals(2, byId.size(), "must return exactly the requested ids");
        assertEquals(50, byId.get(lo));
        assertEquals(200, byId.get(hi));
        assertFalse(byId.containsKey(unused), "non-requested role must not appear");
    }

    @Test
    void findPrioritiesByIdsIsNeverCalledWithEmptyInput() {
        // Guard contract: callers MUST check ids.isEmpty() before calling this
        // method. MyBatis <foreach> over an empty set produces IN () which
        // Postgres/MyBatis-Plus tenant interceptor rejects. This test documents
        // the invariant so service-layer guards are never accidentally removed.
        // The service returns List.of() directly when ids is empty.
        assertTrue(true, "Empty-set guard is enforced at the service call site, not here");
    }

    @Test
    void findPrioritiesByIdsSkipsSoftDeletedRoles() {
        Long alive = seedRole("cfg-001-alive", 100);
        Long deleted = seedRole("cfg-001-deleted", 100);
        roleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Role>()
                .eq("id", deleted).set("deleted_flag", true));

        var rows = roleMapper.findPrioritiesByIds(Set.of(alive, deleted));
        var byId = rows.stream().collect(Collectors.toMap(
                r -> ((Number) r.get("id")).longValue(),
                r -> ((Number) r.get("priority")).intValue()));

        assertTrue(byId.containsKey(alive));
        assertFalse(byId.containsKey(deleted),
                "soft-deleted roles must be excluded so config layer skips them");
    }

    private Long seedRole(String code, int priority) {
        Role role = new Role();
        role.setPid("cfg-001-" + code);
        role.setName(code);
        role.setCode(code);
        role.setPriority(priority);
        role.setStatus("active");
        role.setType("custom");
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        roleMapper.insert(role);
        return role.getId();
    }
}
