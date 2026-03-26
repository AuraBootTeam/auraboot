package com.auraboot.framework.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.governance.dao.entity.MasterDataChangeRequest;
import com.auraboot.framework.governance.dao.entity.MasterDataVersion;
import com.auraboot.framework.governance.dao.mapper.MasterDataChangeRequestMapper;
import com.auraboot.framework.governance.dao.mapper.MasterDataVersionMapper;
import com.auraboot.framework.governance.dto.*;
import com.auraboot.framework.governance.service.MasterDataGovernanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Implementation of master data governance service.
 * Handles change request lifecycle (DRAFT -> PENDING_REVIEW -> APPROVED -> APPLIED)
 * and version snapshots.
 */
@Slf4j
@Service
public class MasterDataGovernanceServiceImpl implements MasterDataGovernanceService {

    @Autowired
    private MasterDataChangeRequestMapper changeRequestMapper;

    @Autowired
    private MasterDataVersionMapper versionMapper;

    // ===================== Change Requests =====================

    @Override
    @Transactional
    public ChangeRequestResponse submitChangeRequest(ChangeRequestCreateDTO dto, Long tenantId, String submitterPid) {
        validateChangeType(dto.getChangeType());

        MasterDataChangeRequest entity = new MasterDataChangeRequest();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setRequestNumber(generateRequestNumber(tenantId));
        entity.setEntityType(dto.getEntityType());
        entity.setEntityPid(dto.getEntityPid());
        entity.setChangeType(dto.getChangeType().toLowerCase(Locale.ROOT));
        entity.setProposedData(dto.getProposedData());
        entity.setStatus(StatusConstants.DRAFT);
        entity.setSubmittedByPid(submitterPid);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());

        // For UPDATE/DELETE, capture the current latest version as originalData
        if (!"create".equals(entity.getChangeType()) && dto.getEntityPid() != null) {
            MasterDataVersion latestVersion = findLatestVersion(dto.getEntityType(), dto.getEntityPid(), tenantId);
            if (latestVersion != null) {
                entity.setOriginalData(latestVersion.getSnapshotData());
            }
        }

        changeRequestMapper.insert(entity);
        log.info("Change request created: pid={}, number={}, entityType={}, changeType={}",
                entity.getPid(), entity.getRequestNumber(), entity.getEntityType(), entity.getChangeType());

        return toChangeRequestResponse(entity);
    }

    @Override
    @Transactional
    public ChangeRequestResponse submitForReview(String pid, Long tenantId, String submitterPid) {
        MasterDataChangeRequest entity = findChangeRequestByPid(pid, tenantId);
        if (entity == null) {
            throw new IllegalArgumentException("Change request not found: " + pid);
        }
        if (!StatusConstants.DRAFT.equals(entity.getStatus())) {
            throw new IllegalStateException("only draft requests can be submitted for review, current: " + entity.getStatus());
        }

        entity.setStatus(StatusConstants.PENDING);
        entity.setUpdatedAt(new Date());
        changeRequestMapper.updateById(entity);

        log.info("Change request submitted for review: pid={}, number={}", pid, entity.getRequestNumber());
        return toChangeRequestResponse(entity);
    }

    @Override
    public Page<ChangeRequestResponse> listChangeRequests(Long tenantId, String status, int pageNum, int pageSize) {
        Page<MasterDataChangeRequest> page = new Page<>(pageNum, pageSize);
        QueryWrapper<MasterDataChangeRequest> qw = new QueryWrapper<>();
        qw.lambda().eq(MasterDataChangeRequest::getTenantId, tenantId);
        if (status != null && !status.isBlank()) {
            qw.lambda().eq(MasterDataChangeRequest::getStatus, status.toLowerCase(Locale.ROOT));
        }
        qw.lambda().orderByDesc(MasterDataChangeRequest::getCreatedAt);

        Page<MasterDataChangeRequest> result = changeRequestMapper.selectPage(page, qw);

        Page<ChangeRequestResponse> responsePage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        responsePage.setRecords(result.getRecords().stream()
                .map(this::toChangeRequestResponse)
                .collect(Collectors.toList()));
        return responsePage;
    }

    @Override
    public ChangeRequestResponse getChangeRequest(String pid, Long tenantId) {
        MasterDataChangeRequest entity = findChangeRequestByPid(pid, tenantId);
        if (entity == null) {
            throw new IllegalArgumentException("Change request not found: " + pid);
        }
        return toChangeRequestResponse(entity);
    }

    @Override
    @Transactional
    public ChangeRequestResponse reviewChangeRequest(String pid, ChangeRequestReviewDTO dto, Long tenantId, String reviewerPid) {
        MasterDataChangeRequest entity = findChangeRequestByPid(pid, tenantId);
        if (entity == null) {
            throw new IllegalArgumentException("Change request not found: " + pid);
        }
        if (!StatusConstants.PENDING.equals(entity.getStatus())) {
            throw new IllegalStateException("change request is not in pending status, current: " + entity.getStatus());
        }

        String action = dto.getAction().toLowerCase(Locale.ROOT);
        if (!StatusConstants.APPROVED.equals(action) && !StatusConstants.REJECTED.equals(action)) {
            throw new IllegalArgumentException("Invalid review action: " + action + ". Must be approved or rejected");
        }

        entity.setStatus(action);
        entity.setReviewedByPid(reviewerPid);
        entity.setReviewComment(dto.getComment());
        entity.setReviewedAt(new Date());
        entity.setUpdatedAt(new Date());
        changeRequestMapper.updateById(entity);

        // On approval, create a new version snapshot
        if (StatusConstants.APPROVED.equals(action)) {
            createVersionFromApproval(entity);
            log.info("Change request approved and version created: pid={}, entityType={}, entityPid={}",
                    entity.getPid(), entity.getEntityType(), entity.getEntityPid());
        } else {
            log.info("Change request rejected: pid={}", entity.getPid());
        }

        return toChangeRequestResponse(entity);
    }

    @Override
    @Transactional
    public ChangeRequestResponse applyChange(String pid, Long tenantId, String applierPid) {
        MasterDataChangeRequest entity = findChangeRequestByPid(pid, tenantId);
        if (entity == null) {
            throw new IllegalArgumentException("Change request not found: " + pid);
        }
        if (!StatusConstants.APPROVED.equals(entity.getStatus())) {
            throw new IllegalStateException("only approved change requests can be applied, current: " + entity.getStatus());
        }

        // Create version snapshot from the approved change
        entity.setAppliedByPid(applierPid);
        createVersionFromApproval(entity);

        entity.setStatus(StatusConstants.APPLIED);
        entity.setAppliedAt(new Date());
        entity.setUpdatedAt(new Date());
        changeRequestMapper.updateById(entity);

        log.info("Change request applied: pid={}, entityType={}, entityPid={}", pid, entity.getEntityType(), entity.getEntityPid());
        return toChangeRequestResponse(entity);
    }

    @Override
    @Transactional
    public ChangeRequestResponse cancelChangeRequest(String pid, Long tenantId, String requesterPid) {
        MasterDataChangeRequest entity = findChangeRequestByPid(pid, tenantId);
        if (entity == null) {
            throw new IllegalArgumentException("Change request not found: " + pid);
        }
        String status = entity.getStatus();
        if (!StatusConstants.DRAFT.equals(status) && !StatusConstants.PENDING.equals(status)) {
            throw new IllegalStateException("only draft or pending requests can be cancelled, current: " + status);
        }
        if (!entity.getSubmittedByPid().equals(requesterPid)) {
            throw new IllegalStateException("Only the submitter can cancel a change request");
        }

        entity.setStatus(StatusConstants.CANCELLED);
        entity.setUpdatedAt(new Date());
        changeRequestMapper.updateById(entity);

        log.info("Change request cancelled: pid={}", entity.getPid());
        return toChangeRequestResponse(entity);
    }

    // ===================== Version History =====================

    @Override
    public List<VersionResponse> listVersions(String entityType, String entityPid, Long tenantId) {
        QueryWrapper<MasterDataVersion> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataVersion::getTenantId, tenantId)
                .eq(MasterDataVersion::getEntityType, entityType)
                .eq(MasterDataVersion::getEntityPid, entityPid)
                .orderByDesc(MasterDataVersion::getVersionNumber);

        return versionMapper.selectList(qw).stream()
                .map(this::toVersionResponse)
                .collect(Collectors.toList());
    }

    @Override
    public VersionResponse getVersion(String versionPid, Long tenantId) {
        QueryWrapper<MasterDataVersion> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataVersion::getPid, versionPid)
                .eq(MasterDataVersion::getTenantId, tenantId);

        MasterDataVersion entity = versionMapper.selectOne(qw);
        if (entity == null) {
            throw new IllegalArgumentException("Version not found: " + versionPid);
        }
        return toVersionResponse(entity);
    }

    @Override
    public VersionDiffResponse diffVersions(String entityType, String entityPid, int fromVersion, int toVersion, Long tenantId) {
        MasterDataVersion fromEntity = findVersionByNumber(entityType, entityPid, fromVersion, tenantId);
        MasterDataVersion toEntity = findVersionByNumber(entityType, entityPid, toVersion, tenantId);

        if (fromEntity == null) {
            throw new IllegalArgumentException("Version " + fromVersion + " not found");
        }
        if (toEntity == null) {
            throw new IllegalArgumentException("Version " + toVersion + " not found");
        }

        Map<String, Object> fromData = fromEntity.getSnapshotData() != null ? fromEntity.getSnapshotData() : Collections.emptyMap();
        Map<String, Object> toData = toEntity.getSnapshotData() != null ? toEntity.getSnapshotData() : Collections.emptyMap();

        // Compute field-level diff
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(fromData.keySet());
        allKeys.addAll(toData.keySet());

        List<VersionDiffResponse.FieldDiff> diffs = new ArrayList<>();
        for (String key : allKeys) {
            Object oldVal = fromData.get(key);
            Object newVal = toData.get(key);
            if (!Objects.equals(oldVal, newVal)) {
                diffs.add(new VersionDiffResponse.FieldDiff(key, oldVal, newVal));
            }
        }

        VersionDiffResponse response = new VersionDiffResponse();
        response.setFromVersion(fromVersion);
        response.setToVersion(toVersion);
        response.setChanges(diffs);
        return response;
    }

    @Override
    @Transactional
    public VersionResponse createInitialVersion(String entityType, String entityPid, Long tenantId, String creatorPid) {
        // Check if a version already exists
        MasterDataVersion existing = findLatestVersion(entityType, entityPid, tenantId);
        if (existing != null) {
            throw new IllegalStateException("Entity already has version history. Use change requests for updates.");
        }

        MasterDataVersion version = new MasterDataVersion();
        version.setPid(UniqueIdGenerator.generate());
        version.setTenantId(tenantId);
        version.setEntityType(entityType);
        version.setEntityPid(entityPid);
        version.setVersionNumber(1);
        version.setSnapshotData(Collections.emptyMap()); // Initial empty snapshot
        version.setCreatedByPid(creatorPid);
        version.setComment("Initial version");
        version.setCreatedAt(new Date());

        versionMapper.insert(version);
        log.info("Initial version created: entityType={}, entityPid={}", entityType, entityPid);

        return toVersionResponse(version);
    }

    // ===================== Statistics =====================

    @Override
    public GovernanceStatsResponse getStats(Long tenantId) {
        GovernanceStatsResponse stats = new GovernanceStatsResponse();

        QueryWrapper<MasterDataChangeRequest> crQw = new QueryWrapper<>();
        crQw.lambda().eq(MasterDataChangeRequest::getTenantId, tenantId);
        stats.setTotalChangeRequests(changeRequestMapper.selectCount(crQw));

        QueryWrapper<MasterDataChangeRequest> pendingQw = new QueryWrapper<>();
        pendingQw.lambda().eq(MasterDataChangeRequest::getTenantId, tenantId)
                .in(MasterDataChangeRequest::getStatus, "draft", "pending");
        stats.setPendingRequests(changeRequestMapper.selectCount(pendingQw));

        QueryWrapper<MasterDataChangeRequest> approvedQw = new QueryWrapper<>();
        approvedQw.lambda().eq(MasterDataChangeRequest::getTenantId, tenantId)
                .in(MasterDataChangeRequest::getStatus, "approved", "applied");
        stats.setApprovedRequests(changeRequestMapper.selectCount(approvedQw));

        QueryWrapper<MasterDataChangeRequest> rejectedQw = new QueryWrapper<>();
        rejectedQw.lambda().eq(MasterDataChangeRequest::getTenantId, tenantId)
                .eq(MasterDataChangeRequest::getStatus, "rejected");
        stats.setRejectedRequests(changeRequestMapper.selectCount(rejectedQw));

        // Count distinct entities that have version history
        // Use selectList + distinct to count unique entity combinations
        QueryWrapper<MasterDataVersion> distinctQw = new QueryWrapper<>();
        distinctQw.eq("tenant_id", tenantId);
        distinctQw.select("DISTINCT entity_type, entity_pid");
        long distinctCount = versionMapper.selectMaps(distinctQw).size();
        stats.setTotalVersionedEntities(distinctCount);

        QueryWrapper<MasterDataVersion> totalVQw = new QueryWrapper<>();
        totalVQw.lambda().eq(MasterDataVersion::getTenantId, tenantId);
        stats.setTotalVersionSnapshots(versionMapper.selectCount(totalVQw));

        return stats;
    }

    // ===================== Private Helpers =====================

    private void createVersionFromApproval(MasterDataChangeRequest request) {
        int nextVersion = getNextVersionNumber(request.getEntityType(), request.getEntityPid(), request.getTenantId());

        Map<String, Object> snapshotData;
        if ("delete".equals(request.getChangeType())) {
            // For DELETE, snapshot the original data as the final record
            snapshotData = request.getOriginalData() != null ? request.getOriginalData() : Collections.emptyMap();
        } else if ("update".equals(request.getChangeType()) || "bulk_update".equals(request.getChangeType())) {
            // For UPDATE, merge proposed changes into original data
            snapshotData = new HashMap<>();
            if (request.getOriginalData() != null) {
                snapshotData.putAll(request.getOriginalData());
            }
            if (request.getProposedData() != null) {
                snapshotData.putAll(request.getProposedData());
            }
        } else {
            // CREATE - use proposed data as-is
            snapshotData = request.getProposedData() != null ? request.getProposedData() : Collections.emptyMap();
        }

        MasterDataVersion version = new MasterDataVersion();
        version.setPid(UniqueIdGenerator.generate());
        version.setTenantId(request.getTenantId());
        version.setEntityType(request.getEntityType());
        version.setEntityPid(request.getEntityPid() != null ? request.getEntityPid() : request.getPid());
        version.setVersionNumber(nextVersion);
        version.setSnapshotData(snapshotData);
        version.setChangeRequestPid(request.getPid());
        version.setCreatedByPid(request.getAppliedByPid() != null ? request.getAppliedByPid() : request.getReviewedByPid());
        String crRef = request.getRequestNumber() != null ? request.getRequestNumber() : request.getPid();
        version.setComment("Applied change request " + crRef + ": " + request.getChangeType());
        version.setCreatedAt(new Date());

        versionMapper.insert(version);
    }

    private int getNextVersionNumber(String entityType, String entityPid, Long tenantId) {
        MasterDataVersion latest = findLatestVersion(entityType, entityPid, tenantId);
        return latest != null ? latest.getVersionNumber() + 1 : 1;
    }

    private MasterDataVersion findLatestVersion(String entityType, String entityPid, Long tenantId) {
        if (entityPid == null) {
            return null;
        }
        QueryWrapper<MasterDataVersion> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataVersion::getTenantId, tenantId)
                .eq(MasterDataVersion::getEntityType, entityType)
                .eq(MasterDataVersion::getEntityPid, entityPid)
                .orderByDesc(MasterDataVersion::getVersionNumber)
                .last("LIMIT 1");
        return versionMapper.selectOne(qw);
    }

    private MasterDataVersion findVersionByNumber(String entityType, String entityPid, int versionNumber, Long tenantId) {
        QueryWrapper<MasterDataVersion> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataVersion::getTenantId, tenantId)
                .eq(MasterDataVersion::getEntityType, entityType)
                .eq(MasterDataVersion::getEntityPid, entityPid)
                .eq(MasterDataVersion::getVersionNumber, versionNumber);
        return versionMapper.selectOne(qw);
    }

    private MasterDataChangeRequest findChangeRequestByPid(String pid, Long tenantId) {
        QueryWrapper<MasterDataChangeRequest> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataChangeRequest::getPid, pid)
                .eq(MasterDataChangeRequest::getTenantId, tenantId);
        return changeRequestMapper.selectOne(qw);
    }

    private void validateChangeType(String changeType) {
        if (changeType == null || changeType.isBlank()) {
            throw new IllegalArgumentException("changeType is required");
        }
        String normalized = changeType.toLowerCase(Locale.ROOT);
        if (!"create".equals(normalized) && !"update".equals(normalized) && !"delete".equals(normalized) && !"bulk_update".equals(normalized)) {
            throw new IllegalArgumentException("Invalid changeType: " + changeType + ". Must be create, update, delete, or bulk_update");
        }
    }

    private ChangeRequestResponse toChangeRequestResponse(MasterDataChangeRequest entity) {
        ChangeRequestResponse resp = new ChangeRequestResponse();
        resp.setPid(entity.getPid());
        resp.setRequestNumber(entity.getRequestNumber());
        resp.setEntityType(entity.getEntityType());
        resp.setEntityPid(entity.getEntityPid());
        resp.setChangeType(entity.getChangeType());
        resp.setProposedData(entity.getProposedData());
        resp.setOriginalData(entity.getOriginalData());
        resp.setStatus(entity.getStatus());
        resp.setSubmittedByPid(entity.getSubmittedByPid());
        resp.setReviewedByPid(entity.getReviewedByPid());
        resp.setReviewComment(entity.getReviewComment());
        resp.setAppliedByPid(entity.getAppliedByPid());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        resp.setReviewedAt(entity.getReviewedAt());
        resp.setAppliedAt(entity.getAppliedAt());
        return resp;
    }

    private VersionResponse toVersionResponse(MasterDataVersion entity) {
        VersionResponse resp = new VersionResponse();
        resp.setPid(entity.getPid());
        resp.setEntityType(entity.getEntityType());
        resp.setEntityPid(entity.getEntityPid());
        resp.setVersionNumber(entity.getVersionNumber());
        resp.setSnapshotData(entity.getSnapshotData());
        resp.setChangeRequestPid(entity.getChangeRequestPid());
        resp.setCreatedByPid(entity.getCreatedByPid());
        resp.setComment(entity.getComment());
        resp.setCreatedAt(entity.getCreatedAt());
        return resp;
    }

    /**
     * Generate a human-readable request number: CR-YYYY-NNNN
     */
    private String generateRequestNumber(Long tenantId) {
        int year = LocalDate.now().getYear();
        String prefix = "CR-" + year + "-";

        QueryWrapper<MasterDataChangeRequest> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataChangeRequest::getTenantId, tenantId)
                .likeRight(MasterDataChangeRequest::getRequestNumber, prefix)
                .orderByDesc(MasterDataChangeRequest::getRequestNumber)
                .last("LIMIT 1");
        MasterDataChangeRequest last = changeRequestMapper.selectOne(qw);

        int seq = 1;
        if (last != null && last.getRequestNumber() != null) {
            try {
                String numPart = last.getRequestNumber().substring(prefix.length());
                seq = Integer.parseInt(numPart) + 1;
            } catch (Exception e) {
                // ignore parse errors, start from 1
            }
        }
        return prefix + String.format("%04d", seq);
    }
}
