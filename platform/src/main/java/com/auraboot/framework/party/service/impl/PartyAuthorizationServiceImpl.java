package com.auraboot.framework.party.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.mapper.PartyCapabilityMapper;
import com.auraboot.framework.party.mapper.PartyMemberRoleMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import com.auraboot.framework.party.service.PartyAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PartyAuthorizationServiceImpl implements PartyAuthorizationService {
    private final PartyMembershipMapper partyMembershipMapper;
    private final PartyMemberRoleMapper partyMemberRoleMapper;
    private final PartyCapabilityMapper partyCapabilityMapper;

    @Override
    public Set<Long> resolveActivePartyRoleIds(
            Long tenantId,
            Long tenantMemberId,
            Long actorPartyId,
            Long partyMembershipId) {
        if (tenantId == null || tenantMemberId == null || actorPartyId == null || partyMembershipId == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Party execution context is incomplete");
        }
        PartyActorOption actor = partyMembershipMapper.findActiveActor(
                tenantId, tenantMemberId, actorPartyId);
        if (actor == null || !partyMembershipId.equals(actor.getPartyMembershipId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Party execution context is no longer active");
        }
        List<Long> roleIds = partyMemberRoleMapper.findActiveRoleIds(tenantId, partyMembershipId);
        return roleIds == null || roleIds.isEmpty() ? Set.of() : Set.copyOf(roleIds);
    }

    @Override
    public void requireCurrentActorCapability(String capabilityCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long partyId = requirePartyScope();
        List<String> capabilities = partyCapabilityMapper.findActiveCodes(tenantId, partyId);
        if (capabilities == null || !capabilities.contains(capabilityCode)) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Current Party lacks required capability: " + capabilityCode);
        }
    }

    @Override
    public void requireCurrentActorOwnsResource(Long resourcePartyId) {
        Long partyId = requirePartyScope();
        if (!Objects.equals(partyId, resourcePartyId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Resource does not belong to the current Party Actor");
        }
    }

    private Long requirePartyScope() {
        if (!"party".equals(MetaContext.getCurrentExecutionScope())
                || MetaContext.getCurrentActorPartyId() == null
                || MetaContext.getCurrentPartyMembershipId() == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Party execution scope is required");
        }
        return MetaContext.getCurrentActorPartyId();
    }
}
