package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.chatbi.v2.entity.ChatBiConversation;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiConversationMapper;
import com.auraboot.framework.chatbi.v2.provider.ConversationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins down the multi-turn sliding-window semantics from PRD 17 §15 + §7.4 and
 * the lifecycle invariants from §5 table 2.
 */
class ConversationServiceTest {

    private ChatBiConversationMapper mapper;
    private ConversationService service;

    @BeforeEach
    void setup() {
        mapper = mock(ChatBiConversationMapper.class);
        service = new ConversationService(mapper);
    }

    private ChatBiConversation activeRow(String json) {
        ChatBiConversation r = new ChatBiConversation();
        r.setPid("PID");
        r.setTenantId(1L);
        r.setUserId(100L);
        r.setStatus(ConversationService.STATUS_ACTIVE);
        r.setMessagesJson(json);
        r.setTokenBudgetUsed(0);
        return r;
    }

    // -- create -----------------------------------------------------------

    @Test
    void createPersistsActiveRowWithEmptyMessages() {
        String pid = service.create(1L, 100L, "MODEL_PID");
        assertThat(pid).hasSize(26); // ULID

        ArgumentCaptor<ChatBiConversation> captor =
                ArgumentCaptor.forClass(ChatBiConversation.class);
        verify(mapper).insert(captor.capture());
        ChatBiConversation saved = captor.getValue();
        assertThat(saved.getPid()).isEqualTo(pid);
        assertThat(saved.getTenantId()).isEqualTo(1L);
        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getSemanticModelPid()).isEqualTo("MODEL_PID");
        assertThat(saved.getMessagesJson()).isEqualTo("[]");
        assertThat(saved.getStatus()).isEqualTo(ConversationService.STATUS_ACTIVE);
        assertThat(saved.getTokenBudgetUsed()).isZero();
    }

    @Test
    void createAcceptsNullSemanticModelForCrossModelConversation() {
        String pid = service.create(1L, 100L, null);
        ArgumentCaptor<ChatBiConversation> captor =
                ArgumentCaptor.forClass(ChatBiConversation.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSemanticModelPid()).isNull();
        assertThat(pid).isNotBlank();
    }

    @Test
    void createRejectsNullTenantOrUser() {
        assertThatThrownBy(() -> service.create(null, 100L, "X"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.create(1L, null, "X"))
                .isInstanceOf(NullPointerException.class);
    }

    // -- append -----------------------------------------------------------

    @Test
    void appendUserMessagePersistsIntoMessagesJson() {
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow("[]"));
        service.append(1L, "PID", "user", "今年华东销售额按月趋势");

        ArgumentCaptor<ChatBiConversation> captor =
                ArgumentCaptor.forClass(ChatBiConversation.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getMessagesJson())
                .contains("\"role\":\"user\"")
                .contains("\"content\":\"今年华东销售额按月趋势\"");
    }

    @Test
    void appendAssistantMessagePersists() {
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow("[]"));
        service.append(1L, "PID", "assistant", "已为您聚合。");
        ArgumentCaptor<ChatBiConversation> captor =
                ArgumentCaptor.forClass(ChatBiConversation.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getMessagesJson())
                .contains("\"role\":\"assistant\"");
    }

    @Test
    void appendRejectsUnknownRole() {
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow("[]"));
        assertThatThrownBy(() -> service.append(1L, "PID", "system", "ignored"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role must be");
    }

    @Test
    void appendRejectsMissingConversation() {
        when(mapper.findByPid(1L, "MISSING")).thenReturn(null);
        assertThatThrownBy(() -> service.append(1L, "MISSING", "user", "?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void appendRejectsClosedConversation() {
        ChatBiConversation closed = activeRow("[]");
        closed.setStatus(ConversationService.STATUS_CLOSED);
        when(mapper.findByPid(1L, "PID")).thenReturn(closed);
        assertThatThrownBy(() -> service.append(1L, "PID", "user", "?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
        verify(mapper, never()).updateById(any(ChatBiConversation.class));
    }

    // -- sliding window ---------------------------------------------------

    @Test
    void slidingWindowTrimsBeyondFivePairs() {
        // Pre-existing 6 pairs = 12 messages
        String fullHistory = buildHistoryJson(6);
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow(fullHistory));

        // Append one more — should bring messages to 13, then trim to 10
        service.append(1L, "PID", "user", "Q7");

        ArgumentCaptor<ChatBiConversation> captor =
                ArgumentCaptor.forClass(ChatBiConversation.class);
        verify(mapper).updateById(captor.capture());
        String trimmed = captor.getValue().getMessagesJson();

        // 5 pairs = 10 entries. Verify only the latest are kept.
        assertThat(countOccurrences(trimmed, "\"role\":")).isEqualTo(10);
        // 13 → 10 means the FIRST 3 messages (Q0, A0, Q1) are dropped.
        assertThat(trimmed)
                .doesNotContain("\"content\":\"Q0\"")
                .doesNotContain("\"content\":\"A0\"")
                .doesNotContain("\"content\":\"Q1\"");
        // A1 (4th overall) onward survives, including the newest user turn.
        assertThat(trimmed)
                .contains("\"content\":\"A1\"")
                .contains("\"content\":\"Q2\"")
                .contains("\"content\":\"A5\"")
                .contains("\"content\":\"Q7\"");
    }

    @Test
    void slidingWindowDoesNotTrimBelowFivePairs() {
        // 4 pairs = 8 messages — append should bring to 9 but no trim
        String history = buildHistoryJson(4);
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow(history));
        service.append(1L, "PID", "user", "Q5");

        ArgumentCaptor<ChatBiConversation> captor =
                ArgumentCaptor.forClass(ChatBiConversation.class);
        verify(mapper).updateById(captor.capture());
        String result = captor.getValue().getMessagesJson();
        assertThat(countOccurrences(result, "\"role\":")).isEqualTo(9);
        assertThat(result).contains("Q0"); // earliest still present
    }

    // -- close ------------------------------------------------------------

    @Test
    void closeReturnsTrueOnFirstCall() {
        when(mapper.close(1L, "PID")).thenReturn(1);
        assertThat(service.close(1L, "PID")).isTrue();
    }

    @Test
    void closeReturnsFalseOnSecondCallIdempotent() {
        when(mapper.close(1L, "PID")).thenReturn(0);
        assertThat(service.close(1L, "PID")).isFalse();
    }

    // -- resetContext -----------------------------------------------------

    @Test
    void resetContextOnActiveConversationClearsAndStamps() {
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow(buildHistoryJson(3)));
        when(mapper.clearContext(1L, "PID")).thenReturn(1);
        assertThat(service.resetContext(1L, "PID")).isTrue();
        verify(mapper).clearContext(1L, "PID");
    }

    @Test
    void resetContextOnClosedConversationRefusesNoop() {
        ChatBiConversation closed = activeRow("[]");
        closed.setStatus(ConversationService.STATUS_CLOSED);
        when(mapper.findByPid(1L, "PID")).thenReturn(closed);
        assertThat(service.resetContext(1L, "PID")).isFalse();
        verify(mapper, never()).clearContext(anyLong(), anyString());
    }

    @Test
    void resetContextOnMissingConversationReturnsFalse() {
        when(mapper.findByPid(1L, "X")).thenReturn(null);
        assertThat(service.resetContext(1L, "X")).isFalse();
        verify(mapper, never()).clearContext(anyLong(), anyString());
    }

    // -- loadContext ------------------------------------------------------

    @Test
    void loadContextReturnsAllMessagesForActiveConversation() {
        String history = buildHistoryJson(3); // 6 messages
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow(history));
        ConversationContext ctx = service.loadContext(1L, "PID");
        assertThat(ctx.getMessageHistory()).hasSize(6);
        assertThat(ctx.getMessageHistory().get(0).role()).isEqualTo("user");
        assertThat(ctx.getMessageHistory().get(0).content()).isEqualTo("Q0");
        assertThat(ctx.getMessageHistory().get(5).role()).isEqualTo("assistant");
        assertThat(ctx.getMessageHistory().get(5).content()).isEqualTo("A2");
    }

    @Test
    void loadContextOnClosedConversationReturnsEmpty() {
        ChatBiConversation closed = activeRow(buildHistoryJson(3));
        closed.setStatus(ConversationService.STATUS_CLOSED);
        when(mapper.findByPid(1L, "PID")).thenReturn(closed);
        ConversationContext ctx = service.loadContext(1L, "PID");
        assertThat(ctx.getMessageHistory()).isEmpty();
    }

    @Test
    void loadContextOnMissingReturnsEmpty() {
        when(mapper.findByPid(1L, "X")).thenReturn(null);
        ConversationContext ctx = service.loadContext(1L, "X");
        assertThat(ctx.getMessageHistory()).isEmpty();
        assertThat(ctx.getLastMetrics()).isEmpty();
    }

    @Test
    void loadContextTolaratesMalformedJson() {
        ChatBiConversation row = activeRow("{ not array malformed");
        when(mapper.findByPid(1L, "PID")).thenReturn(row);
        ConversationContext ctx = service.loadContext(1L, "PID");
        assertThat(ctx.getMessageHistory()).isEmpty(); // best-effort, no throw
    }

    // -- findByPid passthrough --------------------------------------------

    @Test
    void findByPidWrapsMapper() {
        when(mapper.findByPid(1L, "PID")).thenReturn(activeRow("[]"));
        assertThat(service.findByPid(1L, "PID")).isPresent();
        assertThat(service.findByPid(1L, "X")).isEmpty();
    }

    // -- helpers ----------------------------------------------------------

    /** Build a JSON array of {@code n} (user, assistant) pairs labelled Q0..Q(n-1) / A0..A(n-1). */
    private static String buildHistoryJson(int pairs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < pairs; i++) {
            if (i > 0) b.append(",");
            b.append("{\"role\":\"user\",\"content\":\"Q").append(i).append("\"},");
            b.append("{\"role\":\"assistant\",\"content\":\"A").append(i).append("\"}");
        }
        b.append("]");
        return b.toString();
    }

    private static int countOccurrences(String s, String token) {
        if (s == null || token == null || token.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(token, idx)) != -1) {
            count++;
            idx += token.length();
        }
        return count;
    }

    @SuppressWarnings("unused")
    private void usedVerifyHelpers() {
        verify(mapper, never()).insert(any(ChatBiConversation.class));
        verify(mapper, times(1)).updateById(any(ChatBiConversation.class));
    }
}
