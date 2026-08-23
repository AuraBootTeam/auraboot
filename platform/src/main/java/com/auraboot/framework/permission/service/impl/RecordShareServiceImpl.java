package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.mapper.RecordShareMapper;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.rbac.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

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
        validateFutureExpiry(expiresAt);
        RecordShare share = new RecordShare();
        share.setPid(UniqueIdGenerator.generate());
        share.setTenantId(tenantId);
        share.setResourceCode(resourceCode);
        share.setRecordId(recordId);
        share.setSubjectType(subjectType);
        share.setSubjectId(subjectId);
        share.setPermissionMask(normalizePermissionMask(permissionMask));
        share.setExpiresAt(expiresAt);
        share.setCreatedAt(Instant.now());
        share.setCreatedBy(MetaContext.getCurrentUserId());

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
        validateFutureExpiry(expiresAt);

        RecordShare share = new RecordShare();
        share.setPid(UniqueIdGenerator.generate());
        share.setTenantId(tenantId);
        share.setResourceCode(resourceCode);
        share.setRecordPid(recordPid.trim());
        share.setSubjectType(subjectType);
        share.setSubjectId(subjectId);
        share.setSubjectPid(normalizePid(subjectPid));
        share.setPermissionMask(normalizePermissionMask(permissionMask));
        share.setExpiresAt(expiresAt);
        share.setCreatedAt(Instant.now());
        share.setCreatedBy(MetaContext.getCurrentUserId());

        recordShareMapper.upsertByPublicPids(share);
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
    public boolean isShared(
            Long tenantId, String resourceCode, Long recordId, Long memberId, String action) {
        Instant now = Instant.now();
        String normalizedAction = normalizeAction(action);

        // Check direct member share
        int directCount = recordShareMapper.countByRecordAndUser(
                tenantId, resourceCode, recordId, memberId, normalizedAction, now);
        if (directCount > 0) {
            return true;
        }

        // Check role-based share
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }

        int roleCount = recordShareMapper.countByRecordAndRoles(
                tenantId, resourceCode, recordId, roleIds, normalizedAction, now);
        return roleCount > 0;
    }

    @Override
    public boolean isSharedByPid(
            Long tenantId,
            String resourceCode,
            String recordPid,
            Long memberId,
            String memberPid,
            String action) {
        if (!StringUtils.hasText(recordPid)) {
            return false;
        }
        Instant now = Instant.now();
        String normalizedRecordPid = recordPid.trim();
        String normalizedMemberPid = normalizePid(memberPid);
        String normalizedAction = normalizeAction(action);

        if (StringUtils.hasText(normalizedMemberPid)) {
            int directPidCount = recordShareMapper.countByRecordPidAndSubjectPid(
                    tenantId, resourceCode, normalizedRecordPid, "member", normalizedMemberPid,
                    normalizedAction, now);
            if (directPidCount > 0) {
                return true;
            }
        }

        if (memberId == null) {
            return false;
        }

        int directLegacyCount = recordShareMapper.countByRecordPidAndUser(
                tenantId, resourceCode, normalizedRecordPid, memberId, normalizedAction, now);
        if (directLegacyCount > 0) {
            return true;
        }

        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }

        int roleCount = recordShareMapper.countByRecordPidAndRoles(
                tenantId, resourceCode, normalizedRecordPid, roleIds, normalizedAction, now);
        return roleCount > 0;
    }

    @Override
    public List<Long> getSharedRecordIds(Long tenantId, String resourceCode, Long memberId, String action) {
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        return recordShareMapper.findSharedRecordIds(
                tenantId, resourceCode, memberId,
                roleIds != null ? roleIds : List.of(),
                normalizeAction(action),
                Instant.now());
    }

    @Override
    public List<String> getSharedRecordPids(
            Long tenantId,
            String resourceCode,
            Long memberId,
            String memberPid,
            String action) {
        List<Long> roleIds = userRoleService.getRoleIdsByMemberIdAndTenantId(memberId, tenantId);
        return recordShareMapper.findSharedRecordPids(
                tenantId,
                resourceCode,
                memberId,
                normalizePid(memberPid),
                roleIds != null ? roleIds : List.of(),
                normalizeAction(action),
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
    public List<RecordShare> listByRecordPidForManagement(
            Long tenantId, String resourceCode, String recordPid) {
        if (!StringUtils.hasText(recordPid)) {
            throw new RootUnCheckedException(BadParam, "recordPid is required");
        }
        return recordShareMapper.findByRecordPidForManagement(
                tenantId, resourceCode, recordPid.trim(), Instant.now());
    }

    @Override
    public void updateByPid(
            Long tenantId,
            String sharePid,
            String permissionMask,
            Instant expiresAt) {
        if (tenantId == null || !StringUtils.hasText(sharePid)) {
            throw new RootUnCheckedException(BadParam, "sharePid is required");
        }
        validateFutureExpiry(expiresAt);
        int changed = recordShareMapper.updatePolicyByPidInTenant(
                tenantId,
                sharePid.trim(),
                normalizePermissionMask(permissionMask),
                expiresAt);
        if (changed != 1) {
            throw new RootUnCheckedException(BadParam, "Share not found");
        }
        log.info("Updated record share pid={} (mask={}, expires={})",
                sharePid, permissionMask, expiresAt);
    }

    @Override
    public void removeByPid(Long tenantId, String sharePid) {
        RecordShare share = getByPidInTenant(tenantId, sharePid);
        if (share == null) {
            throw new RootUnCheckedException(BadParam, "Share not found");
        }
        recordShareMapper.deleteByPidInTenant(tenantId, sharePid.trim());
        log.info("Removed share pid={} for resource={} record={}",
                sharePid, share.getResourceCode(), share.getRecordPid());
    }

    @Override
    @Transactional
    public void removeByPids(Long tenantId, List<String> sharePids) {
        if (tenantId == null || sharePids == null || sharePids.isEmpty()) {
            throw new RootUnCheckedException(BadParam, "sharePids are required");
        }
        List<String> normalized = sharePids.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty() || normalized.size() != sharePids.size()) {
            throw new RootUnCheckedException(BadParam, "sharePids must be unique and non-blank");
        }
        for (String sharePid : normalized) {
            int deleted = recordShareMapper.deleteByPidInTenant(tenantId, sharePid);
            if (deleted != 1) {
                throw new RootUnCheckedException(BadParam, "Share not found");
            }
        }
        log.info("Removed {} record shares for tenant={}", normalized.size(), tenantId);
    }

    @Override
    public RecordShare getByPidInTenant(Long tenantId, String sharePid) {
        if (tenantId == null || !StringUtils.hasText(sharePid)) {
            return null;
        }
        return recordShareMapper.findByPidInTenant(tenantId, sharePid.trim());
    }

    private String normalizePid(String pid) {
        return StringUtils.hasText(pid) ? pid.trim() : null;
    }

    private String normalizeAction(String action) {
        return StringUtils.hasText(action) ? action.trim().toLowerCase(Locale.ROOT) : "read";
    }

    private String normalizePermissionMask(String permissionMask) {
        if (!StringUtils.hasText(permissionMask)) {
            return "read";
        }
        return permissionMask.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private void validateFutureExpiry(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new RootUnCheckedException(BadParam, "expiresAt must be in the future");
        }
    }
}
