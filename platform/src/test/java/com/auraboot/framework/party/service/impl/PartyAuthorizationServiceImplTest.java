package com.auraboot.framework.party.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.mapper.PartyCapabilityMapper;
import com.auraboot.framework.party.mapper.PartyMemberRoleMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyAuthorizationServiceImplTest {
    @Mock
    private PartyMembershipMapper partyMembershipMapper;
    @Mock
    private PartyMemberRoleMapper partyMemberRoleMapper;
    @Mock
    private PartyCapabilityMapper partyCapabilityMapper;
    @InjectMocks
    private PartyAuthorizationServiceImpl service;

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void resolveActivePartyRoleIds_requiresExactActiveMembership() {
        PartyActorOption actor = new PartyActorOption();
        actor.setPartyMembershipId(31L);
        when(partyMembershipMapper.findActiveActor(10L, 20L, 30L)).thenReturn(actor);
        when(partyMemberRoleMapper.findActiveRoleIds(10L, 31L)).thenReturn(List.of(41L, 42L));

        Set<Long> roleIds = service.resolveActivePartyRoleIds(10L, 20L, 30L, 31L);

        assertThat(roleIds).containsExactlyInAnyOrder(41L, 42L);
    }

    @Test
    void resolveActivePartyRoleIds_rejectsStaleMembershipClaim() {
        PartyActorOption actor = new PartyActorOption();
        actor.setPartyMembershipId(32L);
        when(partyMembershipMapper.findActiveActor(10L, 20L, 30L)).thenReturn(actor);

        assertThatThrownBy(() -> service.resolveActivePartyRoleIds(10L, 20L, 30L, 31L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer active");
        verify(partyMemberRoleMapper, never()).findActiveRoleIds(10L, 31L);
    }

    @Test
    void requireCurrentActorCapability_checksBusinessQualificationNotRole() {
        setPartyContext();
        when(partyCapabilityMapper.findActiveCodes(10L, 30L))
                .thenReturn(List.of("supplier", "manufacturer"));

        service.requireCurrentActorCapability("supplier");

        assertThatThrownBy(() -> service.requireCurrentActorCapability("logistics_provider"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("lacks required capability");
    }

    @Test
    void requireCurrentActorOwnsResource_rejectsCrossPartyResource() {
        setPartyContext();

        service.requireCurrentActorOwnsResource(30L);
        assertThatThrownBy(() -> service.requireCurrentActorOwnsResource(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void capabilityCheck_requiresPartyExecutionScope() {
        MetaContext.setContext(10L, 11L, "u-11", "user");
        MetaContext.setSessionContext(1L, 2L, "tenant", null, null, "ready", 1);

        assertThatThrownBy(() -> service.requireCurrentActorCapability("supplier"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party execution scope is required");
        verify(partyCapabilityMapper, never()).findActiveCodes(10L, 30L);
    }

    private void setPartyContext() {
        MetaContext.setContext(10L, 11L, "u-11", "user");
        MetaContext.setSessionContext(1L, 2L, "party", 30L, 31L, "ready", 1);
    }
}
