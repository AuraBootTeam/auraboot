package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
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
import java.util.function.Supplier;

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
        withTenantContext(tenantId, () -> {
            replaceSharesForUsers(tenantId, resourceCode, recordPid, userPids, "read");
            return null;
        });
    }

    @Override
    public void replaceReadUpdateSharesForUsers(
            long tenantId,
            String resourceCode,
            String recordPid,
            Collection<String> userPids) {
        withTenantContext(tenantId, () -> {
            replaceSharesForUsers(tenantId, resourceCode, recordPid, userPids, "read,update");
            return null;
        });
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
        List<String> staleSharePids = existing.stream()
                .filter(share -> "member".equalsIgnoreCase(share.getSubjectType())
                        && StringUtils.hasText(share.getSubjectPid())
                        && !desiredMemberPids.contains(share.getSubjectPid()))
                .map(RecordShare::getPid)
                .toList();
        if (!staleSharePids.isEmpty()) {
            // Batch removal: each removeByPid would re-select the share it
            // already has in memory.
            recordShareService.removeByPids(tenantId, staleSharePids);
        }
        for (String memberPid : desiredMemberPids) {
            recordShareService.shareRecordByPid(
                    tenantId, resourceCode.trim(), recordPid.trim(),
                    "member", memberIdsByPid.get(memberPid), memberPid, permissionMask, null);
        }
    }

    private <T> T withTenantContext(long tenantId, Supplier<T> work) {
        if (MetaContext.exists()) {
            if (!java.util.Objects.equals(MetaContext.getCurrentTenantId(), tenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "Record-share tenant does not match current context");
            }
            return work.get();
        }

        Long priorEnvironment = MetaContext.getCurrentEnvironmentId();
        MetaContext.setContext(tenantId, 0L, null, "system");
        MetaContext.setMemberId(null);
        MetaContext.setEnvironmentId(priorEnvironment);
        try {
            return work.get();
        } finally {
            MetaContext.clear();
        }
    }
}
