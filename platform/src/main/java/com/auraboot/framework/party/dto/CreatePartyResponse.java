package com.auraboot.framework.party.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record CreatePartyResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long partyId,
        String partyPid,
        String lifecycleStatus,
        @JsonSerialize(using = ToStringSerializer.class) Long partyMembershipId,
        String membershipStatus) {}
