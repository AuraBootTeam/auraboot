package com.auraboot.framework.application.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MetaContextRolesTest {

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    @Test
    void getCurrentRoleIdsReturnsEmptyWhenNoContext() {
        MetaContext.clear();
        assertEquals(Set.of(), MetaContext.getCurrentRoleIds());
    }

    @Test
    void setContextWithRolesExposesSnapshot() {
        Set<Long> source = new HashSet<>(Set.of(1L, 2L, 3L));
        MetaContext.setContext(7L, 100L, "user-pid", "alice", source);
        Set<Long> snapshot = MetaContext.getCurrentRoleIds();
        assertEquals(Set.of(1L, 2L, 3L), snapshot);
    }

    @Test
    void setContextSnapshotIsImmutableAgainstCallerMutation() {
        Set<Long> source = new HashSet<>(Set.of(1L, 2L));
        MetaContext.setContext(7L, 100L, "user-pid", "alice", source);
        source.add(99L); // caller mutates after binding
        assertEquals(Set.of(1L, 2L), MetaContext.getCurrentRoleIds(),
                "MetaContext must take an immutable copy of role ids");
    }

    @Test
    void legacyFourArgSetContextStillWorksWithEmptyRoleIds() {
        MetaContext.setContext(7L, 100L, "user-pid", "alice");
        assertEquals(Set.of(), MetaContext.getCurrentRoleIds(),
                "Legacy 4-arg setContext must default roleIds to empty set");
    }
}
