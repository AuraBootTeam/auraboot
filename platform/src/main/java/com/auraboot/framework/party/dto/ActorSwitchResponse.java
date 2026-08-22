package com.auraboot.framework.party.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record ActorSwitchResponse(
        String jwt,
        String executionScope,
        @JsonSerialize(using = ToStringSerializer.class) Long actorPartyId,
        @JsonSerialize(using = ToStringSerializer.class) Long partyMembershipId,
        String sessionStage,
        long contextVersion) {}
