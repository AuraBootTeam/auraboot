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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void replaceSharesForRecords(long tenantId, List<Replacement> replacements) {
        withTenantContext(tenantId, () -> {
            var inputs = replacements.stream()
                    .map(replacement -> new Replacement(replacement.resourceCode(), replacement.recordPid(),
                            normalizeUserPids(replacement.userPids()), replacement.allowUpdate()))
                    .toList();
            Set<String> audience = new LinkedHashSet<>();
            for (var replacement : inputs) {
                validateTarget(replacement.resourceCode(), replacement.recordPid());
                audience.addAll(replacement.userPids());
            }
            var recipients = resolveRecipients(tenantId, List.copyOf(audience));
            for (var replacement : inputs) {
                var members = recipientMembers(replacement.userPids(), recipients);
                replaceRecordShares(tenantId, replacement.resourceCode(), replacement.recordPid(),
                        members, replacement.allowUpdate() ? "read,update" : "read");
            }
            return null;
        });
    }

    private void replaceSharesForUsers(
            long tenantId,
            String resourceCode,
            String recordPid,
            Collection<String> userPids,
            String permissionMask) {
        validateTarget(resourceCode, recordPid);
        List<String> requested = normalizeUserPids(userPids);
        replaceRecordShares(tenantId, resourceCode, recordPid,
                recipientMembers(requested, resolveRecipients(tenantId, requested)), permissionMask);
    }

    private void validateTarget(String resourceCode, String recordPid) {
        if (!StringUtils.hasText(resourceCode) || !StringUtils.hasText(recordPid)) {
            throw new BusinessException(ResponseCode.BadParam, "resourceCode and recordPid are required");
        }
    }

    private void replaceRecordShares(long tenantId, String resourceCode, String recordPid,
                                     Map<String, Long> memberIdsByPid, String permissionMask) {
        Set<String> desiredMemberPids = memberIdsByPid.keySet();
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

    private List<String> normalizeUserPids(Collection<String> userPids) {
        if (userPids == null) return List.of();
        return userPids.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    private Map<String, Long> recipientMembers(Collection<String> requested,
            Map<String, TenantMemberMapper.ActiveMemberRecipient> recipients) {
        Map<String, Long> members = new LinkedHashMap<>();
        for (String userPid : requested) {
            var recipient = recipients.get(userPid);
            members.put(recipient.memberPid(), recipient.memberId());
        }
        return members;
    }

    private Map<String, TenantMemberMapper.ActiveMemberRecipient> resolveRecipients(
            long tenantId, List<String> requested) {
        if (requested.isEmpty()) return Map.of();
        if (requested.size() == 1) {
            String userPid = requested.getFirst();
            TenantMember member = tenantMemberMapper.findActiveByTenantIdAndUserPid(tenantId, userPid);
            if (member == null || !StringUtils.hasText(member.getPid()) || member.getId() == null) {
                throw missingRecipient(userPid);
            }
            return Map.of(userPid, new TenantMemberMapper.ActiveMemberRecipient(
                    userPid, member.getPid(), member.getId()));
        }

        Map<String, TenantMemberMapper.ActiveMemberRecipient> resolved = new LinkedHashMap<>();
        // Keep the bound parameter list below the driver's limit for large audiences.
        for (int from = 0; from < requested.size(); from += 1000) {
            for (var recipient : tenantMemberMapper.findActiveRecipients(
                    tenantId, requested.subList(from, Math.min(from + 1000, requested.size())))) {
                resolved.put(recipient.userPid(), recipient);
            }
        }
        for (String userPid : requested) {
            var recipient = resolved.get(userPid);
            if (recipient == null || !StringUtils.hasText(recipient.memberPid()) || recipient.memberId() == null) {
                throw missingRecipient(userPid);
            }
        }
        return resolved;
    }

    private BusinessException missingRecipient(String userPid) {
        return new BusinessException(ResponseCode.BadParam,
                "Active tenant member not found for user PID: " + userPid);
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
