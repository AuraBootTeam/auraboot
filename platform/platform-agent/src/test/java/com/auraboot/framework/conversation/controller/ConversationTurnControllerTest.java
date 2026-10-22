package com.auraboot.framework.conversation.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.conversation.dto.ActiveTurnDTO;
import com.auraboot.framework.conversation.turn.TurnHandle;
import com.auraboot.framework.conversation.turn.TurnRegistry;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.im.service.ImConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationTurnController")
class ConversationTurnControllerTest {

    @Mock
    private TurnRegistry turnRegistry;

    @Mock
    private ImConversationService conversationService;

    private MockedStatic<MetaContext> metaContextMock;

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) {
            metaContextMock.close();
        }
    }

    private ConversationTurnController controller() {
        return new ConversationTurnController(turnRegistry, conversationService);
    }

    // ── cancel tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelByInitiator_marksRegistryAndReturnsOk")
    void cancelByInitiator_marksRegistryAndReturnsOk() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(42L);

        TurnHandle handle = new TurnHandle("turn-1", 100L, 7L, "AgentX", 42L, null);
        when(turnRegistry.get("turn-1")).thenReturn(Optional.of(handle));

        ApiResponse<Void> response = controller().cancelTurn(100L, "turn-1");

        assertThat(response).isNotNull();
        verify(turnRegistry).markCancelled("turn-1");
    }

    @Test
    @DisplayName("cancelByNonInitiator_returnsForbidden")
    void cancelByNonInitiator_returnsForbidden() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(99L);

        TurnHandle handle = new TurnHandle("turn-2", 100L, 7L, "AgentX", 42L, null);
        when(turnRegistry.get("turn-2")).thenReturn(Optional.of(handle));

        assertThatThrownBy(() -> controller().cancelTurn(100L, "turn-2"))
                .isInstanceOf(RootUnCheckedException.class)
                .satisfies(ex -> {
                    RootUnCheckedException rce = (RootUnCheckedException) ex;
                    assertThat(rce.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN);
                });

        verify(turnRegistry, never()).markCancelled(any());
    }

    @Test
    @DisplayName("cancelUnknownTurn_isIdempotentNoOp")
    void cancelUnknownTurn_isIdempotentNoOp() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(42L);

        when(turnRegistry.get("turn-unknown")).thenReturn(Optional.empty());

        ApiResponse<Void> response = controller().cancelTurn(100L, "turn-unknown");

        assertThat(response).isNotNull();
        verify(turnRegistry, never()).markCancelled(any());
    }

    // ── getActive tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getActiveAsMember_returnsList")
    void getActiveAsMember_returnsList() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(42L);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(1L);

        when(conversationService.isMember(100L, 42L, 1L)).thenReturn(true);

        TurnHandle h1 = new TurnHandle("turn-A", 100L, 7L, "AgentX", 42L, 500L);
        TurnHandle h2 = new TurnHandle("turn-B", 100L, 8L, "AgentY", 42L, 501L);
        when(turnRegistry.getActiveByConversation(100L)).thenReturn(List.of(h1, h2));

        ApiResponse<List<ActiveTurnDTO>> response = controller().getActiveTurns(100L);

        assertThat(response).isNotNull();
        assertThat(response.getData()).hasSize(2);

        ActiveTurnDTO dto1 = response.getData().get(0);
        assertThat(dto1.turnId()).isEqualTo("turn-A");
        assertThat(dto1.conversationId()).isEqualTo(100L);
        assertThat(dto1.agentId()).isEqualTo(7L);
        assertThat(dto1.agentName()).isEqualTo("AgentX");
        assertThat(dto1.initiatorUserId()).isEqualTo(42L);
        assertThat(dto1.replyToMessageId()).isEqualTo(500L);
        assertThat(dto1.status()).isEqualTo("ACTIVE");

        ActiveTurnDTO dto2 = response.getData().get(1);
        assertThat(dto2.turnId()).isEqualTo("turn-B");
    }

    @Test
    @DisplayName("getActiveAsNonMember_returnsForbidden")
    void getActiveAsNonMember_returnsForbidden() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(99L);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(1L);

        when(conversationService.isMember(100L, 99L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> controller().getActiveTurns(100L))
                .isInstanceOf(RootUnCheckedException.class)
                .satisfies(ex -> {
                    RootUnCheckedException rce = (RootUnCheckedException) ex;
                    assertThat(rce.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN);
                });

        verify(turnRegistry, never()).getActiveByConversation(any());
    }
}
