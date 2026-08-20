package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Tenant-safe host implementation of the plugin record-share bridge. */
@Service
@RequiredArgsConstructor
public class RecordShareAccessorImpl implements RecordShareAccessor {

    private final RecordShareService recordShareService;
    private final TenantMemberMapper tenantMemberMapper;

    @Override
    public void replaceReadSharesForUsers(
            long tenantId,
            String resourceCode,
            String recordPid,
            Collection<String> userPids) {
        replaceSharesForUsers(tenantId, resourceCode, recordPid, userPids, "read");
    }

    @Override
    public void replaceReadUpdateSharesForUsers(
            long tenantId,
            String resourceCode,
            String recordPid,
            Collection<String> userPids) {
        replaceSharesForUsers(tenantId, resourceCode, recordPid, userPids, "read,update");
    }

    private void replaceSharesForUsers(
            long tenantId,
            String resourceCode,
            String recordPid,
            Collection<String> userPids,
            String permissionMask) {
        if (!StringUtils.hasText(resourceCode) || !StringUtils.hasText(recordPid)) {
            throw new BusinessException(ResponseCode.BadParam, "resourceCode and recordPid are required");
        }

        Set<String> desiredMemberPids = new LinkedHashSet<>();
        java.util.Map<String, Long> memberIdsByPid = new java.util.LinkedHashMap<>();
        if (userPids != null) {
            for (String userPid : userPids) {
                if (!StringUtils.hasText(userPid)) continue;
                TenantMember member = tenantMemberMapper.findActiveByTenantIdAndUserPid(
                        tenantId, userPid.trim());
                if (member == null || !StringUtils.hasText(member.getPid())) {
                    throw new BusinessException(ResponseCode.BadParam,
                            "Active tenant member not found for user PID: " + userPid.trim());
                }
                desiredMemberPids.add(member.getPid());
                memberIdsByPid.put(member.getPid(), member.getId());
            }
        }

        List<RecordShare> existing = recordShareService.listByRecordPidForManagement(
                tenantId, resourceCode.trim(), recordPid.trim());
        for (RecordShare share : existing) {
            if ("member".equalsIgnoreCase(share.getSubjectType())
                    && StringUtils.hasText(share.getSubjectPid())
                    && !desiredMemberPids.contains(share.getSubjectPid())) {
                recordShareService.removeByPid(tenantId, share.getPid());
            }
        }
        for (String memberPid : desiredMemberPids) {
            recordShareService.shareRecordByPid(
                    tenantId, resourceCode.trim(), recordPid.trim(),
                    "member", memberIdsByPid.get(memberPid), memberPid, permissionMask, null);
        }
    }
}
