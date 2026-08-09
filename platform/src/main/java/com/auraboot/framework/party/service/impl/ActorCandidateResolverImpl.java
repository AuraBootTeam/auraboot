package com.auraboot.framework.party.service.impl;

import com.auraboot.framework.auth.mapper.LoginApplicationChannelMapper;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.mapper.PartyCapabilityMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import com.auraboot.framework.party.service.ActorCandidateResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ActorCandidateResolverImpl implements ActorCandidateResolver {
    private final PartyMembershipMapper partyMembershipMapper;
    private final PartyCapabilityMapper partyCapabilityMapper;
    private final LoginApplicationChannelMapper loginApplicationChannelMapper;

    @Override
    public List<PartyActorOption> resolveCandidates(
            Long tenantId,
            Long tenantMemberId,
            Long applicationId,
            Long loginChannelId) {
        ChannelPolicy policy = resolveChannelPolicy(tenantId, applicationId, loginChannelId);
        if (!policy.valid()) {
            return List.of();
        }
        return partyMembershipMapper.findActorOptions(tenantId, tenantMemberId).stream()
                .filter(option -> populateCapabilitiesAndCheck(option, tenantId, policy.allowedCapabilities()))
                .toList();
    }

    @Override
    public PartyActorOption resolveActiveCandidate(
            Long tenantId,
            Long tenantMemberId,
            Long partyId,
            Long applicationId,
            Long loginChannelId) {
        ChannelPolicy policy = resolveChannelPolicy(tenantId, applicationId, loginChannelId);
        if (!policy.valid()) {
            return null;
        }
        PartyActorOption candidate = partyMembershipMapper.findActiveActor(
                tenantId, tenantMemberId, partyId);
        if (candidate == null
                || !populateCapabilitiesAndCheck(candidate, tenantId, policy.allowedCapabilities())) {
            return null;
        }
        return candidate;
    }

    private ChannelPolicy resolveChannelPolicy(
            Long tenantId,
            Long applicationId,
            Long loginChannelId) {
        // Tokens issued before the application/channel registry remain compatible. A half-bound
        // context is invalid and fails closed because it cannot identify an authoritative channel.
        if (applicationId == null && loginChannelId == null) {
            return new ChannelPolicy(true, Set.of());
        }
        if (applicationId == null || loginChannelId == null
                || !loginApplicationChannelMapper.isActiveLoginContext(
                        tenantId, applicationId, loginChannelId)) {
            return new ChannelPolicy(false, Set.of());
        }
        List<String> configured = loginApplicationChannelMapper.findAllowedPartyCapabilities(
                tenantId, applicationId, loginChannelId);
        return new ChannelPolicy(true,
                configured == null ? Set.of() : Set.copyOf(configured));
    }

    private boolean populateCapabilitiesAndCheck(
            PartyActorOption candidate,
            Long tenantId,
            Set<String> allowedCapabilities) {
        List<String> activeCapabilities = partyCapabilityMapper.findActiveCodes(
                tenantId, candidate.getPartyId());
        List<String> snapshot = activeCapabilities == null ? List.of() : List.copyOf(activeCapabilities);
        candidate.setCapabilityCodes(snapshot);
        if (allowedCapabilities.isEmpty()) {
            return true;
        }
        Set<String> active = new HashSet<>(snapshot);
        return allowedCapabilities.stream().anyMatch(active::contains);
    }

    private record ChannelPolicy(boolean valid, Set<String> allowedCapabilities) {}
}
