package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImConversationControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private ImConversationService conversationService;

    @Mock
    private ImWebSocketHandler webSocketHandler;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void addMembersAcceptsLegacyHumanArrayBody() throws Exception {
        MetaContext.setContext(7L, 11L, "user-pid", "tester");
        ImConversationController controller = new ImConversationController(conversationService, webSocketHandler);
        JsonNode body = OBJECT_MAPPER.readTree("[101,102]");

        controller.addMembers(88L, body);

        verify(conversationService).addMembers(88L, List.of(101L, 102L), 7L);
        verify(conversationService).addAgentMembers(88L, List.of(), 7L);
    }

    @Test
    void addMembersAcceptsHumanAndAgentObjectBody() throws Exception {
        MetaContext.setContext(7L, 11L, "user-pid", "tester");
        ImConversationController controller = new ImConversationController(conversationService, webSocketHandler);
        JsonNode body = OBJECT_MAPPER.readTree("{\"memberIds\":[101],\"agentIds\":[201,202]}");

        controller.addMembers(88L, body);

        verify(conversationService).addMembers(88L, List.of(101L), 7L);
        verify(conversationService).addAgentMembers(88L, List.of(201L, 202L), 7L);
    }

    @Test
    void addMembersBroadcastsMemberAddedToAllHumanMembers() throws Exception {
        MetaContext.setContext(7L, 11L, "user-pid", "tester");
        ImConversationController controller = new ImConversationController(conversationService, webSocketHandler);
        JsonNode body = OBJECT_MAPPER.readTree("{\"memberIds\":[101,102],\"agentIds\":[201]}");

        com.auraboot.framework.im.dto.ConversationMemberInfo m1 = new com.auraboot.framework.im.dto.ConversationMemberInfo();
        m1.setMemberId(11L); m1.setMemberType(ImConstants.MEMBER_TYPE_HUMAN);
        com.auraboot.framework.im.dto.ConversationMemberInfo m2 = new com.auraboot.framework.im.dto.ConversationMemberInfo();
        m2.setMemberId(101L); m2.setMemberType(ImConstants.MEMBER_TYPE_HUMAN);
        com.auraboot.framework.im.dto.ConversationMemberInfo m3 = new com.auraboot.framework.im.dto.ConversationMemberInfo();
        m3.setMemberId(102L); m3.setMemberType(ImConstants.MEMBER_TYPE_HUMAN);
        com.auraboot.framework.im.dto.ConversationMemberInfo a1 = new com.auraboot.framework.im.dto.ConversationMemberInfo();
        a1.setMemberId(201L); a1.setMemberType(ImConstants.MEMBER_TYPE_AGENT);
        org.mockito.Mockito.when(conversationService.getMembers(88L, 7L))
            .thenReturn(List.of(m1, m2, m3, a1));

        controller.addMembers(88L, body);

        verify(conversationService).addMembers(88L, List.of(101L, 102L), 7L);
        verify(conversationService).addAgentMembers(88L, List.of(201L), 7L);
        verify(webSocketHandler).broadcastEvent(
            org.mockito.ArgumentMatchers.eq(List.of(11L, 101L, 102L)),
            org.mockito.ArgumentMatchers.eq(ImConstants.WS_MEMBER_ADDED),
            org.mockito.ArgumentMatchers.argThat(payload ->
                payload.get("conversationId").equals(88L) &&
                ((List<?>)payload.get("memberIds")).equals(List.of(101L, 102L)) &&
                ((List<?>)payload.get("agentIds")).equals(List.of(201L)) &&
                payload.get("byUserId").equals(11L) &&
                "tester".equals(payload.get("byUserName"))
            )
        );
    }
}
