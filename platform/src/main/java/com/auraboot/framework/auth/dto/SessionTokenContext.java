package com.auraboot.framework.auth.dto;

import com.auraboot.framework.auth.constant.ExecutionScope;
import com.auraboot.framework.auth.constant.SessionStage;

/** Immutable claims used to mint one effective execution context. */
public record SessionTokenContext(
        Long tenantId,
        Long memberId,
        Long applicationId,
        Long loginChannelId,
        ExecutionScope executionScope,
        Long actorPartyId,
        Long partyMembershipId,
        SessionStage sessionStage,
        long contextVersion,
        int securityVersion) {

    public static SessionTokenContext tenant(Long tenantId, Long memberId, int securityVersion) {
        return new SessionTokenContext(
                tenantId,
                memberId,
                null,
                null,
                tenantId == null ? null : ExecutionScope.TENANT,
                null,
                null,
                tenantId == null ? SessionStage.ONBOARDING : SessionStage.READY,
                1,
                securityVersion);
    }
}
