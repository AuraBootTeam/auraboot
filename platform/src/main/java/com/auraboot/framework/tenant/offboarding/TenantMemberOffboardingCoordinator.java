package com.auraboot.framework.tenant.offboarding;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.tenant.dto.TenantMemberOffboardingImpactResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** Coordinates cross-module ownership transfer and tenant-admin continuity checks. */
@Service
@RequiredArgsConstructor
public class TenantMemberOffboardingCoordinator {

    private final List<TenantMemberOffboardingHandler> handlers;
    private final TenantMemberMapper tenantMemberMapper;
    private final TenantAdminContinuityGuard adminContinuityGuard;

    public TenantMemberOffboardingImpactResponse inspect(
            TenantMember source, String targetMemberPid, Long operatorUserId,
            TenantMemberOffboardingAction action) {
        TenantMember target = resolveTarget(source, targetMemberPid);
        TenantMemberOffboardingContext context = context(source, target, operatorUserId, action);
        List<TenantMemberOffboardingImpact> impacts = handlers.stream()
                .map(handler -> handler.inspect(context))
                .filter(impact -> impact != null && impact.ownedCount() > 0)
                .toList();
        long ownedCount = impacts.stream().mapToLong(TenantMemberOffboardingImpact::ownedCount).sum();
        return TenantMemberOffboardingImpactResponse.builder()
                .memberPid(source.getPid())
                .targetMemberPid(target == null ? null : target.getPid())
                .ownedResourceCount(ownedCount)
                .transferRequired(ownedCount > 0)
                .resources(impacts.stream().map(impact -> TenantMemberOffboardingImpactResponse.ResourceImpact.builder()
                        .resourceType(impact.resourceType())
                        .displayName(impact.displayName())
                        .ownedCount(impact.ownedCount())
                        .transferable(impact.transferable())
                        .build()).toList())
                .build();
    }

    @Transactional
    public void prepare(
            TenantMember source, String targetMemberPid, Long operatorUserId,
            TenantMemberOffboardingAction action) {
        adminContinuityGuard.assertMemberCanBeOffboarded(source);
        TenantMember target = resolveTarget(source, targetMemberPid);
        TenantMemberOffboardingContext context = context(source, target, operatorUserId, action);
        List<TenantMemberOffboardingImpact> impacts = handlers.stream()
                .map(handler -> handler.inspect(context))
                .filter(impact -> impact != null && impact.ownedCount() > 0)
                .toList();
        if (impacts.isEmpty()) {
            return;
        }
        if (target == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Member owns tenant resources; targetMemberPid is required before offboarding");
        }
        if (impacts.stream().anyMatch(impact -> !impact.transferable())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Member owns resources that cannot be transferred");
        }
        handlers.forEach(handler -> handler.transfer(context));
        List<TenantMemberOffboardingImpact> remaining = handlers.stream()
                .map(handler -> handler.inspect(context))
                .filter(impact -> impact != null && impact.ownedCount() > 0)
                .toList();
        if (!remaining.isEmpty()) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR,
                    "Member offboarding transfer left owned resources behind");
        }
    }

    private TenantMember resolveTarget(TenantMember source, String targetMemberPid) {
        if (!StringUtils.hasText(targetMemberPid)) {
            return null;
        }
        TenantMember target = tenantMemberMapper.findByTenantIdAndPid(source.getTenantId(), targetMemberPid.trim());
        if (target == null || !"active".equalsIgnoreCase(target.getStatus())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Target member must be active in the same tenant");
        }
        if (source.getId().equals(target.getId())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Target member must differ from the member being offboarded");
        }
        return target;
    }

    private TenantMemberOffboardingContext context(
            TenantMember source, TenantMember target, Long operatorUserId,
            TenantMemberOffboardingAction action) {
        return new TenantMemberOffboardingContext(
                source.getTenantId(), source.getPid(), source.getUserId(),
                target == null ? null : target.getPid(), target == null ? null : target.getUserId(),
                operatorUserId, action);
    }

}
