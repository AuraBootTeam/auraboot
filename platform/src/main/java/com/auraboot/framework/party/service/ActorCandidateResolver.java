package com.auraboot.framework.party.service;

import com.auraboot.framework.party.dto.PartyActorOption;

import java.util.List;

/** Resolves Party Actors that are valid for the current login application and channel. */
public interface ActorCandidateResolver {
    List<PartyActorOption> resolveCandidates(
            Long tenantId,
            Long tenantMemberId,
            Long applicationId,
            Long loginChannelId);

    PartyActorOption resolveActiveCandidate(
            Long tenantId,
            Long tenantMemberId,
            Long partyId,
            Long applicationId,
            Long loginChannelId);
}
