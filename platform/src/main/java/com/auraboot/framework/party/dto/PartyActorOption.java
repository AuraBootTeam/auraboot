package com.auraboot.framework.party.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.List;

@Data
public class PartyActorOption {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long partyId;
    private String partyPid;
    private String partyCode;
    private String displayName;
    private String partyType;
    private String lifecycleStatus;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long partyMembershipId;
    private String membershipStatus;
    private boolean current;
    private List<String> capabilityCodes = List.of();
    private List<String> partyRoleCodes = List.of();
}
