package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.mapper.RecordShareMapper;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.rbac.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

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
    public void shareRecordByPid(Long tenantId, String resourceCode, String recordPid,
                                 String subjectType, Long subjectId, String subjectPid,
                                 String permissionMask, Instant expiresAt) {
        if (!StringUtils.hasText(recordPid)) {
            throw new RootUnCheckedException(BadParam, "recordPid is required");
        }
        if (subjectId == null && !StringUtils.hasText(subjectPid)) {
            throw new RootUnCheckedException(BadParam, "subjectId or subjectPid is required");
        }

        RecordShare share = new RecordShare();
        share.setPid(UniqueIdGenerator.generate());
        share.setTenantId(tenantId);
        share.setResourceCode(resourceCode);
        share.setRecordPid(recordPid.trim());
        share.setSubjectType(subjectType);
        share.setSubjectId(subjectId);
        share.setSubjectPid(normalizePid(subjectPid));
        share.setPermissionMask(permissionMask);
        share.setExpiresAt(expiresAt);
        share.setCreatedAt(Instant.now());

        recordShareMapper.insert(share);
        log.info("Shared record {}/{} with {}:{} (mask={}, expires={})",
                resourceCode, recordPid, subjectType,
                StringUtils.hasText(subjectPid) ? subjectPid : subjectId,
                permissionMask, expiresAt);
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
    public boolean isSharedByPid(Long tenantId, String resourceCode, String recordPid, Long memberId, String memberPid) {
        if (!StringUtils.hasText(recordPid)) {
            return false;
        }
        Instant now = Instant.now();
        String normalizedRecordPid = recordPid.trim();
        String normalizedMemberPid = normalizePid(memberPid);

        if (StringUtils.hasText(normalizedMemberPid)) {
            int directPidCount = recordShareMapper.countByRecordPidAndSubjectPid(
                    tenantId, resourceCode, normalizedRecordPid, "member", normalizedMemberPid, now);
            if (directPidCount > 0) {
                return true;
            }
        }

        if (memberId == null) {
            return false;
        }

        int directLegacyCount = recordShareMapper.countByRecordPidAndUser(
                tenantId, resourceCode, normalizedRecordPid, memberId, now);
        if (directLegacyCount > 0) {
            return true;
        }

        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }

        int roleCount = recordShareMapper.countByRecordPidAndRoles(
                tenantId, resourceCode, normalizedRecordPid, roleIds, now);
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

    @Override
    public List<RecordShare> listByRecord(Long tenantId, String resourceCode, Long recordId) {
        return recordShareMapper.findByRecord(tenantId, resourceCode, recordId, Instant.now());
    }

    @Override
    public List<RecordShare> listByRecordPid(Long tenantId, String resourceCode, String recordPid) {
        if (!StringUtils.hasText(recordPid)) {
            throw new RootUnCheckedException(BadParam, "recordPid is required");
        }
        return recordShareMapper.findByRecordPid(tenantId, resourceCode, recordPid.trim(), Instant.now());
    }

    @Override
    public void removeById(Long tenantId, Long shareId) {
        RecordShare share = recordShareMapper.selectById(shareId);
        if (share == null) {
            throw new RootUnCheckedException(BadParam, "Share not found: " + shareId);
        }
        if (!tenantId.equals(share.getTenantId())) {
            throw new RootUnCheckedException(BadParam, "Share not found: " + shareId);
        }
        recordShareMapper.deleteById(shareId);
        log.info("Removed share id={} for resource={} record={}", shareId, share.getResourceCode(), share.getRecordId());
    }

    private String normalizePid(String pid) {
        return StringUtils.hasText(pid) ? pid.trim() : null;
    }
}
