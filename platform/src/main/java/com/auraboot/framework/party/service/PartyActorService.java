package com.auraboot.framework.party.service;

import com.auraboot.framework.party.dto.ActorSwitchResponse;
import com.auraboot.framework.party.dto.CreatePartyRequest;
import com.auraboot.framework.party.dto.CreatePartyResponse;
import com.auraboot.framework.party.dto.PartyActorOption;

import java.util.List;

public interface PartyActorService {
    List<PartyActorOption> listActors();

    CreatePartyResponse createParty(CreatePartyRequest request);

    void approveParty(Long partyId);

    ActorSwitchResponse switchActor(Long partyId, String currentToken, String ipAddress, String userAgent);
}
