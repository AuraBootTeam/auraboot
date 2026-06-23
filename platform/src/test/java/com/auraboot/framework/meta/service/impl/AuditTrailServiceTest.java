package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.mapper.AuditTrailMapper;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditTrailServiceTest {

    @Mock
    private AuditTrailMapper auditTrailMapper;

    @Mock
    private UserMapper userMapper;

    @Test
    void getAuditByActorPid_resolvesTenantScopedUserPidBeforeAuditLookup() {
        AuditTrailService service = new AuditTrailService(auditTrailMapper, userMapper);
        Instant start = Instant.parse("2026-06-22T00:00:00Z");
        Instant end = Instant.parse("2026-06-23T00:00:00Z");
        AuditTrail trail = new AuditTrail();
        when(userMapper.findUserIdInTenantByPid(100L, "actor_pid")).thenReturn(700L);
        when(auditTrailMapper.getByActor(100L, 700L, start, end)).thenReturn(List.of(trail));

        List<AuditTrail> result = service.getAuditByActorPid(100L, "actor_pid", start, end);

        assertEquals(List.of(trail), result);
        verify(userMapper).findUserIdInTenantByPid(100L, "actor_pid");
        verify(auditTrailMapper).getByActor(100L, 700L, start, end);
    }

    @Test
    void getAuditByActorPid_returnsEmptyListWhenPidDoesNotResolve() {
        AuditTrailService service = new AuditTrailService(auditTrailMapper, userMapper);
        Instant start = Instant.parse("2026-06-22T00:00:00Z");
        Instant end = Instant.parse("2026-06-23T00:00:00Z");
        when(userMapper.findUserIdInTenantByPid(100L, "missing_pid")).thenReturn(null);

        List<AuditTrail> result = service.getAuditByActorPid(100L, "missing_pid", start, end);

        assertTrue(result.isEmpty());
        verify(userMapper).findUserIdInTenantByPid(100L, "missing_pid");
        verify(auditTrailMapper, never()).getByActor(100L, 700L, start, end);
    }
}
