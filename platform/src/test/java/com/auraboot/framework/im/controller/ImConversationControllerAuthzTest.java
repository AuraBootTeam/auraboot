package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Object-level authorization tests for IM conversation membership mutators.
 *
 * <p>Security regression: addMembers/removeMember/getMembers were @AuthenticatedAccess
 * (login-only) with no caller-membership check, so any authenticated tenant user could
 * add themselves to an arbitrary (enumerable) conversation id and then read its private
 * messages (getMessages gates on isMember). The controller now rejects non-members.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImConversationController membership authorization")
class ImConversationControllerAuthzTest {

    @Mock
    private ImConversationService conversationService;
    @Mock
    private ImWebSocketHandler webSocketHandler;
    @Mock
    private ImMessageService messageService;

    private ImConversationController controller;

    private static final Long TENANT = 1L;
    private static final Long CALLER = 100L;
    private static final Long CONV = 9L;

    @BeforeEach
    void setUp() {
        controller = new ImConversationController(conversationService, webSocketHandler, messageService);
        MetaContext.setContext(TENANT, CALLER, "u-100", "user100");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("non-member cannot read a conversation's member roster")
    void getMembers_nonMember_denied() {
        when(conversationService.isMember(CONV, CALLER, TENANT)).thenReturn(false);
        ApiResponse<?> r = controller.getMembers(CONV);
        assertFalse(r.isSuccess());
        verify(conversationService, never()).getMembers(anyLong(), anyLong());
    }

    @Test
    @DisplayName("non-member cannot add members (self-join escalation blocked)")
    void addMembers_nonMember_denied() {
        when(conversationService.isMember(CONV, CALLER, TENANT)).thenReturn(false);
        ApiResponse<Void> r = controller.addMembers(CONV, new ObjectMapper().createObjectNode());
        assertFalse(r.isSuccess());
        verify(conversationService, never()).addMembers(anyLong(), anyList(), anyLong());
        verify(conversationService, never()).addAgentMembers(anyLong(), anyList(), anyLong());
    }

    @Test
    @DisplayName("non-member cannot remove members")
    void removeMember_nonMember_denied() {
        when(conversationService.isMember(CONV, CALLER, TENANT)).thenReturn(false);
        ApiResponse<Void> r = controller.removeMember(CONV, "human", 5L);
        assertFalse(r.isSuccess());
        verify(conversationService, never()).removeMember(anyLong(), anyString(), anyLong(), anyLong());
    }
}
