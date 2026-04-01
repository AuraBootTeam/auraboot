package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.mapper.RecordShareMapper;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.rbac.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Record Share Service implementation.
 *
 * <p>Manages record-level sharing (ReBAC) — allows sharing individual records
 * with users or roles, bypassing data scope restrictions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordShareServiceImpl implements RecordShareService {

    private final RecordShareMapper recordShareMapper;
    private final UserRoleService userRoleService;

    @Override
    public void shareRecord(Long tenantId, String resourceCode, Long recordId,
                            String subjectType, Long subjectId,
                            String permissionMask, Instant expiresAt) {
        RecordShare share = new RecordShare();
        share.setPid(UniqueIdGenerator.generate());
        share.setTenantId(tenantId);
        share.setResourceCode(resourceCode);
        share.setRecordId(recordId);
        share.setSubjectType(subjectType);
        share.setSubjectId(subjectId);
        share.setPermissionMask(permissionMask);
        share.setExpiresAt(expiresAt);
        share.setCreatedAt(Instant.now());

        recordShareMapper.insert(share);
        log.info("Shared record {}/{} with {}:{} (mask={}, expires={})",
                resourceCode, recordId, subjectType, subjectId, permissionMask, expiresAt);
    }

    @Override
    public void unshareRecord(Long tenantId, String resourceCode, Long recordId,
                              String subjectType, Long subjectId) {
        int deleted = recordShareMapper.deleteShare(tenantId, resourceCode, recordId, subjectType, subjectId);
        log.info("Unshared record {}/{} from {}:{} (deleted={} rows)",
                resourceCode, recordId, subjectType, subjectId, deleted);
    }

    @Override
    public boolean isShared(Long tenantId, String resourceCode, Long recordId, Long memberId) {
        Instant now = Instant.now();

        // Check direct member share
        int directCount = recordShareMapper.countByRecordAndUser(
                tenantId, resourceCode, recordId, memberId, now);
        if (directCount > 0) {
            return true;
        }

        // Check role-based share
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }

        int roleCount = recordShareMapper.countByRecordAndRoles(
                tenantId, resourceCode, recordId, roleIds, now);
        return roleCount > 0;
    }

    @Override
    public List<Long> getSharedRecordIds(Long tenantId, String resourceCode, Long memberId, String action) {
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        return recordShareMapper.findSharedRecordIds(
                tenantId, resourceCode, memberId,
                roleIds != null ? roleIds : List.of(),
                Instant.now());
    }
}
