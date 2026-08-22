package com.auraboot.framework.party.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.party.dto.ActorSwitchResponse;
import com.auraboot.framework.party.dto.CreatePartyRequest;
import com.auraboot.framework.party.dto.CreatePartyResponse;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.entity.Party;
import com.auraboot.framework.party.entity.PartyMembership;
import com.auraboot.framework.party.entity.PartyLifecycleTransition;
import com.auraboot.framework.party.mapper.ActorPreferenceMapper;
import com.auraboot.framework.party.mapper.PartyLifecycleTransitionMapper;
import com.auraboot.framework.party.mapper.PartyMapper;
import com.auraboot.framework.party.mapper.PartyMemberRoleMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import com.auraboot.framework.party.service.ActorCandidateResolver;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.saas.constant.PartyCreationPolicy;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyActorServiceImplTest {
    @Mock PartyMapper partyMapper;
    @Mock PartyMembershipMapper partyMembershipMapper;
    @Mock PartyMemberRoleMapper partyMemberRoleMapper;
    @Mock PartyLifecycleTransitionMapper lifecycleTransitionMapper;
    @Mock ActorPreferenceMapper actorPreferenceMapper;
    @Mock TenantMemberService tenantMemberService;
    @Mock SystemModeService systemModeService;
    @Mock UserService userService;
    @Mock JwtUtil jwtUtil;
    @Mock SessionManagementService sessionManagementService;
    @Mock ActorCandidateResolver actorCandidateResolver;

    @InjectMocks PartyActorServiceImpl service;

    @BeforeEach
    void setUpContext() {
        MetaContext.setContext(101L, 7L, "user-pid", "user@example.com");
        MetaContext.setMemberId(202L);
        TenantMember member = new TenantMember();
        member.setId(202L);
        member.setTenantId(101L);
        member.setUserId(7L);
        member.setStatus("active");
        member.setDeletedFlag(false);
        when(tenantMemberService.findByTenantIdAndUserId(101L, 7L)).thenReturn(member);
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void autoApproveCreatesActivePartyAndActiveMembership() {
        when(systemModeService.getPartyCreationPolicy()).thenReturn(PartyCreationPolicy.AUTO_APPROVE);
        doAnswer(invocation -> {
            ((Party) invocation.getArgument(0)).setId(303L);
            return 1;
        }).when(partyMapper).insert(any(Party.class));
        doAnswer(invocation -> {
            ((PartyMembership) invocation.getArgument(0)).setId(404L);
            return 1;
        }).when(partyMembershipMapper).insert(any(PartyMembership.class));
        CreatePartyRequest request = new CreatePartyRequest();
        request.setCode("Factory-A");
        request.setDisplayName("Factory A");

        CreatePartyResponse response = service.createParty(request);

        assertThat(response.partyId()).isEqualTo(303L);
        assertThat(response.lifecycleStatus()).isEqualTo("active");
        assertThat(response.partyMembershipId()).isEqualTo(404L);
        assertThat(response.membershipStatus()).isEqualTo("active");
        ArgumentCaptor<Party> party = ArgumentCaptor.forClass(Party.class);
        verify(partyMapper).insert(party.capture());
        assertThat(party.getValue().getTenantId()).isEqualTo(101L);
        assertThat(party.getValue().getCode()).isEqualTo("factory-a");
        verify(lifecycleTransitionMapper).insert(any(PartyLifecycleTransition.class));
    }

    @Test
    void approvalPolicyCreatesPendingRecords() {
        when(systemModeService.getPartyCreationPolicy()).thenReturn(PartyCreationPolicy.APPROVAL_REQUIRED);
        doAnswer(invocation -> {
            ((Party) invocation.getArgument(0)).setId(303L);
            return 1;
        }).when(partyMapper).insert(any(Party.class));
        CreatePartyRequest request = new CreatePartyRequest();
        request.setCode("supplier-a");
        request.setDisplayName("Supplier A");

        CreatePartyResponse response = service.createParty(request);

        assertThat(response.lifecycleStatus()).isEqualTo("pending");
        assertThat(response.membershipStatus()).isEqualTo("pending");
    }

    @Test
    void actorSwitchMintsReplacementBeforeRevokingOldSession() {
        when(systemModeService.isActorSwitchEnabled()).thenReturn(true);
        PartyActorOption actor = new PartyActorOption();
        actor.setPartyId(303L);
        actor.setPartyMembershipId(404L);
        actor.setLifecycleStatus("active");
        actor.setMembershipStatus("active");
        when(actorCandidateResolver.resolveActiveCandidate(101L, 202L, 303L, 11L, 12L))
                .thenReturn(actor);
        User user = new User();
        user.setId(7L);
        user.setPid("user-pid");
        user.setEmail("user@example.com");
        user.setSecurityVersion(5);
        when(userService.findByUserId(7L)).thenReturn(user);
        when(jwtUtil.extractContextVersion("old-token")).thenReturn(9L);
        when(jwtUtil.extractApplicationId("old-token")).thenReturn(11L);
        when(jwtUtil.extractLoginChannelId("old-token")).thenReturn(12L);
        when(actorPreferenceMapper.advanceContextVersion(any(), eq(101L), eq(202L), eq(303L), eq(10L)))
                .thenReturn(10L);
        when(jwtUtil.generateTokenWithContext(any(), eq("user-pid"), any(SessionTokenContext.class)))
                .thenReturn("new-token");

        ActorSwitchResponse response = service.switchActor(
                303L, "old-token", "127.0.0.1", "test-agent");

        assertThat(response.executionScope()).isEqualTo("party");
        assertThat(response.contextVersion()).isEqualTo(10L);
        ArgumentCaptor<SessionTokenContext> tokenContext = ArgumentCaptor.forClass(SessionTokenContext.class);
        verify(jwtUtil).generateTokenWithContext(any(), eq("user-pid"), tokenContext.capture());
        assertThat(tokenContext.getValue().actorPartyId()).isEqualTo(303L);
        assertThat(tokenContext.getValue().partyMembershipId()).isEqualTo(404L);
        assertThat(tokenContext.getValue().securityVersion()).isEqualTo(5);

        InOrder order = inOrder(sessionManagementService);
        order.verify(sessionManagementService).createSession(7L, "new-token", "127.0.0.1", "test-agent");
        order.verify(sessionManagementService).revokeSessionByToken("old-token");
    }

    @Test
    void actorSwitchRejectsInactiveMembershipWithoutMintingToken() {
        when(systemModeService.isActorSwitchEnabled()).thenReturn(true);
        when(jwtUtil.extractApplicationId("old-token")).thenReturn(11L);
        when(jwtUtil.extractLoginChannelId("old-token")).thenReturn(12L);
        when(actorCandidateResolver.resolveActiveCandidate(101L, 202L, 303L, 11L, 12L))
                .thenReturn(null);

        assertThatThrownBy(() -> service.switchActor(303L, "old-token", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active Party membership");

        verify(jwtUtil, never()).generateTokenWithContext(any(), any(), any());
        verify(sessionManagementService, never()).revokeSessionByToken(any());
    }
}
