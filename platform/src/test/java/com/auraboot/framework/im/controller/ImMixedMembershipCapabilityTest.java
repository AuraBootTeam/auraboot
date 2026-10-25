package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.im.service.impl.ImConversationServiceImpl;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A mixed request is one user operation even though the existing services retain separate transactions. */
class ImMixedMembershipCapabilityTest {
    @BeforeEach void identity() { MetaContext.setContext(42L, 7L, "user", "user"); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test
    void missingAgentDirectoryRejectsBeforeTheHumanMembershipMutation() throws Exception {
        var conversations = mock(ImConversationMapper.class);
        var members = mock(ImConversationMemberMapper.class);
        var messages = mock(ImMessageMapper.class);
        var messageService = mock(ImMessageService.class);
        var websocket = mock(ImWebSocketHandler.class);
        when(members.findMember(100L, "human", 7L, 42L)).thenReturn(new ImConversationMember());
        var service = new ImConversationServiceImpl(conversations, members, messages, messageService, null);
        var controller = new ImConversationController(service, websocket, messageService);
        var payload = new ObjectMapper().readTree("{\"memberIds\":[8],\"agentIds\":[9]}");
        assertThatThrownBy(() -> controller.addMembers(100L, payload))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class).hasMessageContaining("not installed");
        verify(members).findMember(100L, "human", 7L, 42L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(conversations, messages, messageService, websocket);
    }

    @Test
    void missingAgentDirectoryRejectsAnExplicitBotConversationBeforeAnyInsert() {
        var conversations = mock(ImConversationMapper.class);
        var members = mock(ImConversationMemberMapper.class);
        var messages = mock(ImMessageMapper.class);
        var service = new ImConversationServiceImpl(conversations, members, messages, mock(ImMessageService.class), null);
        var request = new com.auraboot.framework.im.dto.ConversationCreateRequest();
        request.setType("bot");
        assertThatThrownBy(() -> service.create(request, 7L, 42L))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class).hasMessageContaining("not installed");
        verifyNoInteractions(conversations, members, messages);
    }

    @Test
    void fullCompositionKeepsTheExistingHumanThenAgentWriteOrder() throws Exception {
        var service = mock(ImConversationService.class);
        var messageService = mock(ImMessageService.class);
        var websocket = mock(ImWebSocketHandler.class);
        when(service.isMember(100L, 7L, 42L)).thenReturn(true);
        var human = new ConversationMemberInfo();
        human.setMemberId(8L);
        human.setMemberType("human");
        human.setName("Member");
        when(service.getMembers(100L, 42L)).thenReturn(List.of(human));
        var controller = new ImConversationController(service, websocket, messageService);
        var payload = new ObjectMapper().readTree("{\"memberIds\":[8],\"agentIds\":[9]}");
        assertThat(controller.addMembers(100L, payload).getCode()).isEqualTo("0");
        var order = inOrder(service);
        order.verify(service).isMember(100L, 7L, 42L);
        order.verify(service).assertAgentMembersAvailable(List.of(9L));
        order.verify(service).addMembers(100L, List.of(8L), 42L);
        order.verify(service).addAgentMembers(100L, List.of(9L), 42L);
        order.verify(service).getMembers(100L, 42L);
    }
}
