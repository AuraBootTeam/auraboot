package com.auraboot.framework.governance.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.governance.dao.entity.MasterDataVersion;
import com.auraboot.framework.governance.dao.mapper.MasterDataVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;

/**
 * Service for capturing version snapshots of entity records.
 * Used by the command pipeline to auto-snapshot governed models.
 */
@Slf4j
@Service
public class GovernanceSnapshotService {

    @Autowired
    private MasterDataVersionMapper versionMapper;

    @Autowired
    private MasterDataPolicyService policyService;

    /**
     * Capture a version snapshot for a record after a successful command execution.
     * Only captures if the model has autoSnapshot=true in its governance policy.
     *
     * @param modelCode the model code
     * @param recordPid the record PID
     * @param snapshotData the current record data
     * @param tenantId the tenant ID
     * @param userPid the user PID who triggered the change
     * @param changeDescription brief description of what changed
     */
    @Transactional
    public void captureSnapshotIfGoverned(String modelCode, String recordPid, Map<String, Object> snapshotData,
                                           Long tenantId, String userPid, String changeDescription) {
        if (!policyService.requiresAutoSnapshot(modelCode, tenantId)) {
            return;
        }

        int nextVersion = getNextVersionNumber(modelCode, recordPid, tenantId);

        MasterDataVersion version = new MasterDataVersion();
        version.setPid(UniqueIdGenerator.generate());
        version.setTenantId(tenantId);
        version.setEntityType(modelCode);
        version.setEntityPid(recordPid);
        version.setVersionNumber(nextVersion);
        version.setSnapshotData(snapshotData);
        version.setCreatedByPid(userPid);
        version.setComment(changeDescription);
        version.setCreatedAt(new Date());

        versionMapper.insert(version);
        log.debug("Auto-snapshot captured: model={}, record={}, version={}", modelCode, recordPid, nextVersion);
    }

    /**
     * Check if a model requires approval for changes.
     * Used by command pipeline to intercept direct edits.
     */
    public boolean requiresApproval(String modelCode, Long tenantId) {
        return policyService.requiresApproval(modelCode, tenantId);
    }

    private int getNextVersionNumber(String modelCode, String recordPid, Long tenantId) {
        QueryWrapper<MasterDataVersion> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(MasterDataVersion::getTenantId, tenantId)
                .eq(MasterDataVersion::getEntityType, modelCode)
                .eq(MasterDataVersion::getEntityPid, recordPid)
                .orderByDesc(MasterDataVersion::getVersionNumber)
                .last("LIMIT 1");
        MasterDataVersion latest = versionMapper.selectOne(qw);
        return latest != null ? latest.getVersionNumber() + 1 : 1;
    }
}
