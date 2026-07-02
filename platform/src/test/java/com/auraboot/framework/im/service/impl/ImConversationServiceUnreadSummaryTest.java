package com.auraboot.framework.im.service.impl;

import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.im.dto.ConversationUnreadRow;
import com.auraboot.framework.im.dto.UnreadSummary;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.service.ImMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the batched {@code getUnreadSummary} (deep-review DR-20260701 W2-perf-003):
 * a single join query (member ⋈ conversation) instead of the previous N+1 loop of
 * per-conversation {@code selectById} + {@code findMember}.
 */
@ExtendWith(MockitoExtension.class)
class ImConversationServiceUnreadSummaryTest {

    @Mock private ImConversationMapper conversationMapper;
    @Mock private ImConversationMemberMapper memberMapper;
    @Mock private ImMessageMapper messageMapper;
    @Mock private ImMessageService imMessageService;
    @Mock private AgentDefinitionMapper agentDefinitionMapper;

    @InjectMocks private ImConversationServiceImpl service;

    @Test
    @DisplayName("getUnreadSummary uses a single batched join, computes unread correctly, and does no N+1 lookups")
    void getUnreadSummary_batchedNoNPlusOne() {
        when(memberMapper.findUnreadRowsByMember(eq(1L), anyString(), eq(42L)))
                .thenReturn(List.of(
                        ConversationUnreadRow.builder().conversationId(100L).maxSeq(10L).lastReadSeq(7L).build(), // 3 unread
                        ConversationUnreadRow.builder().conversationId(200L).maxSeq(5L).lastReadSeq(5L).build(),  // 0 unread -> skipped
                        ConversationUnreadRow.builder().conversationId(300L).maxSeq(8L).lastReadSeq(2L).build()   // 6 unread
                ));

        UnreadSummary summary = service.getUnreadSummary(42L, 1L);

        assertThat(summary.getTotalUnread()).isEqualTo(9L);
        assertThat(summary.getConversations()).hasSize(2);
        assertThat(summary.getConversations())
                .extracting(UnreadSummary.ConversationUnread::getConversationId)
                .containsExactly(100L, 300L);

        // No N+1: the old per-conversation lookups must not be invoked.
        verify(conversationMapper, never()).selectById(any());
        verify(memberMapper, never()).findMember(anyLong(), anyString(), anyLong(), anyLong());
    }
}
