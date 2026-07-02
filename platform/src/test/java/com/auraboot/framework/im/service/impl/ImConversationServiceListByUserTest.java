package com.auraboot.framework.im.service.impl;

import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.im.dto.ConversationLastMessageRow;
import com.auraboot.framework.im.dto.ConversationListItem;
import com.auraboot.framework.im.dto.ConversationMemberCountRow;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.service.ImMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the batched {@code listByUser} (deep-review DR-20260701 W2-perf-002):
 * four batched queries (selectBatchIds + findMembersByConversationIds + findLastMessagesByConversationIds
 * + countHumanMembersByConversationIds) instead of the previous 1+4N per-conversation loop.
 */
@ExtendWith(MockitoExtension.class)
class ImConversationServiceListByUserTest {

    @Mock private ImConversationMapper conversationMapper;
    @Mock private ImConversationMemberMapper memberMapper;
    @Mock private ImMessageMapper messageMapper;
    @Mock private ImMessageService imMessageService;
    @Mock private AgentDefinitionMapper agentDefinitionMapper;

    @InjectMocks private ImConversationServiceImpl service;

    private ImConversation conv(long id, String type, String name, long maxSeq) {
        ImConversation c = new ImConversation();
        c.setId(id);
        c.setType(type);
        c.setName(name);
        c.setMaxSeq(maxSeq);
        return c;
    }

    private ImConversationMember member(long convId, long lastReadSeq, boolean pinned, boolean muted) {
        ImConversationMember m = new ImConversationMember();
        m.setConversationId(convId);
        m.setLastReadSeq(lastReadSeq);
        m.setPinned(pinned);
        m.setMuted(muted);
        return m;
    }

    @Test
    @DisplayName("listByUser assembles from batched queries, computes fields, and does no N+1 lookups")
    void listByUser_batchedNoNPlusOne() {
        List<Long> convIds = List.of(100L, 200L);
        when(memberMapper.findVisibleConversationIdsByMember(eq(1L), anyString(), eq(42L))).thenReturn(convIds);
        when(conversationMapper.selectBatchIds(convIds))
                .thenReturn(List.of(conv(100L, "GROUP", "A", 10L), conv(200L, "PRIVATE", "B", 3L)));
        when(memberMapper.findMembersByConversationIds(eq(1L), anyString(), eq(42L), eq(convIds)))
                .thenReturn(List.of(member(100L, 7L, true, false), member(200L, 3L, false, true)));
        when(messageMapper.findLastMessagesByConversationIds(eq(1L), eq(convIds)))
                .thenReturn(List.of(ConversationLastMessageRow.builder()
                        .conversationId(100L).content("hi").messageType("TEXT")
                        .createdAt(Instant.parse("2026-01-01T00:00:00Z")).build())); // 200 has no message
        when(memberMapper.countHumanMembersByConversationIds(eq(1L), eq(convIds)))
                .thenReturn(List.of(
                        ConversationMemberCountRow.builder().conversationId(100L).memberCount(3L).build(),
                        ConversationMemberCountRow.builder().conversationId(200L).memberCount(2L).build()));

        List<ConversationListItem> items = service.listByUser(42L, 1L);

        assertThat(items).hasSize(2);
        Map<Long, ConversationListItem> byId = items.stream()
                .collect(Collectors.toMap(ConversationListItem::getConversationId, i -> i));

        ConversationListItem i1 = byId.get(100L);
        assertThat(i1.getUnreadCount()).isEqualTo(3L); // 10 - 7
        assertThat(i1.getPinned()).isTrue();
        assertThat(i1.getMemberCount()).isEqualTo(3);
        assertThat(i1.getLastMessage()).isNotNull();
        assertThat(i1.getLastMessage().getContent()).isEqualTo("hi");

        ConversationListItem i2 = byId.get(200L);
        assertThat(i2.getUnreadCount()).isEqualTo(0L); // 3 - 3
        assertThat(i2.getMuted()).isTrue();
        assertThat(i2.getMemberCount()).isEqualTo(2);
        assertThat(i2.getLastMessage()).isNull();

        // No N+1: the old per-conversation lookups must never be invoked.
        verify(conversationMapper, never()).selectById(any());
        verify(memberMapper, never()).findMember(anyLong(), anyString(), anyLong(), anyLong());
        verify(messageMapper, never()).findBeforeSeq(anyLong(), anyLong(), anyLong(), anyInt());
        verify(memberMapper, never()).findHumanMemberIds(anyLong(), anyLong());
    }
}
