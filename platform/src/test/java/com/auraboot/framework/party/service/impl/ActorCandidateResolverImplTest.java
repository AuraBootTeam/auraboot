package com.auraboot.framework.party.service.impl;

import com.auraboot.framework.auth.mapper.LoginApplicationChannelMapper;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.mapper.PartyCapabilityMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActorCandidateResolverImplTest {
    @Mock PartyMembershipMapper partyMembershipMapper;
    @Mock PartyCapabilityMapper partyCapabilityMapper;
    @Mock LoginApplicationChannelMapper loginApplicationChannelMapper;

    @InjectMocks ActorCandidateResolverImpl resolver;

    @Test
    void applicationChannelFiltersCandidatesByActivePartyCapability() {
        PartyActorOption buyer = actor(10L, 110L);
        PartyActorOption supplier = actor(20L, 120L);
        when(loginApplicationChannelMapper.isActiveLoginContext(100L, 1L, 2L)).thenReturn(true);
        when(loginApplicationChannelMapper.findAllowedPartyCapabilities(100L, 1L, 2L))
                .thenReturn(List.of("supplier"));
        when(partyMembershipMapper.findActorOptions(100L, 200L))
                .thenReturn(List.of(buyer, supplier));
        when(partyCapabilityMapper.findActiveCodes(100L, 10L)).thenReturn(List.of("buyer"));
        when(partyCapabilityMapper.findActiveCodes(100L, 20L))
                .thenReturn(List.of("supplier", "manufacturer"));

        List<PartyActorOption> candidates = resolver.resolveCandidates(100L, 200L, 1L, 2L);

        assertThat(candidates).extracting(PartyActorOption::getPartyId).containsExactly(20L);
        assertThat(candidates.get(0).getCapabilityCodes())
                .containsExactly("supplier", "manufacturer");
    }

    @Test
    void unrestrictedChannelKeepsAllCandidates() {
        PartyActorOption actor = actor(10L, 110L);
        when(loginApplicationChannelMapper.isActiveLoginContext(100L, 1L, 2L)).thenReturn(true);
        when(loginApplicationChannelMapper.findAllowedPartyCapabilities(100L, 1L, 2L))
                .thenReturn(List.of());
        when(partyMembershipMapper.findActorOptions(100L, 200L)).thenReturn(List.of(actor));
        when(partyCapabilityMapper.findActiveCodes(100L, 10L)).thenReturn(List.of());

        assertThat(resolver.resolveCandidates(100L, 200L, 1L, 2L))
                .extracting(PartyActorOption::getPartyId)
                .containsExactly(10L);
    }

    @Test
    void halfBoundOrDisabledLoginContextFailsClosed() {
        assertThat(resolver.resolveCandidates(100L, 200L, 1L, null)).isEmpty();
        when(loginApplicationChannelMapper.isActiveLoginContext(100L, 1L, 2L)).thenReturn(false);
        assertThat(resolver.resolveActiveCandidate(100L, 200L, 10L, 1L, 2L)).isNull();
    }

    @Test
    void legacyTokenWithoutApplicationAndChannelRemainsCompatible() {
        PartyActorOption actor = actor(10L, 110L);
        when(partyMembershipMapper.findActiveActor(100L, 200L, 10L)).thenReturn(actor);
        when(partyCapabilityMapper.findActiveCodes(100L, 10L)).thenReturn(List.of("buyer"));

        assertThat(resolver.resolveActiveCandidate(100L, 200L, 10L, null, null))
                .isSameAs(actor);
        assertThat(actor.getCapabilityCodes()).containsExactly("buyer");
    }

    private PartyActorOption actor(Long partyId, Long membershipId) {
        PartyActorOption actor = new PartyActorOption();
        actor.setPartyId(partyId);
        actor.setPartyMembershipId(membershipId);
        actor.setLifecycleStatus("active");
        actor.setMembershipStatus("active");
        return actor;
    }
}
