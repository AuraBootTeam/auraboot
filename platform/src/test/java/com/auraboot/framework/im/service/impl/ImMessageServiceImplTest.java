package com.auraboot.framework.im.service.impl;

import com.auraboot.framework.im.dto.MessageSearchResult;
import com.auraboot.framework.im.dto.ReadReceiptInfo;
import com.auraboot.framework.im.dto.ReadReceiptSummary;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImMessageServiceImpl}. All collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class ImMessageServiceImplTest {

    @Mock private ImConversationMapper conversationMapper;
    @Mock private ImConversationMemberMapper memberMapper;
    @Mock private ImMessageMapper messageMapper;
    @Mock private ImConversationService conversationService;

    private ImMessageServiceImpl service;

    private static final Long TENANT_ID = 1L;
    private static final Long CONV_ID = 100L;
    private static final Long USER_ID = 200L;

    @BeforeEach
    void setUp() {
        service = new ImMessageServiceImpl(conversationMapper, memberMapper, messageMapper,
                conversationService, new ObjectMapper());
    }

    private ImConversation conversationWithSeq(long seq) {
        ImConversation c = new ImConversation();
        c.setId(CONV_ID);
        c.setTenantId(TENANT_ID);
        c.setMaxSeq(seq);
        return c;
    }

    private SendMessageRequest sendRequest(String content, String clientMsgId) {
        SendMessageRequest r = new SendMessageRequest();
        r.setConversationId(CONV_ID);
        r.setContent(content);
        r.setClientMsgId(clientMsgId);
        return r;
    }

    // ============== sendMessage ==============

    @Test
    void sendMessage_nonMember_throws() {
        when(conversationService.isMember(CONV_ID, USER_ID, TENANT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.sendMessage(sendRequest("hi", null), USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a member");
        verify(messageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    void sendMessage_dedupReturnsExisting() {
        when(conversationService.isMember(CONV_ID, USER_ID, TENANT_ID)).thenReturn(true);
        ImMessage existing = new ImMessage();
        existing.setId(999L);
        when(messageMapper.findByClientMsgId(CONV_ID, TENANT_ID, "cmid-1")).thenReturn(existing);

        ImMessage result = service.sendMessage(sendRequest("hi", "cmid-1"), USER_ID, TENANT_ID);

        assertThat(result).isSameAs(existing);
        verify(conversationMapper, never()).incrementSeq(anyLong(), anyLong());
        verify(messageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    void sendMessage_persistsAndReadsMaxSeq() {
        when(conversationService.isMember(CONV_ID, USER_ID, TENANT_ID)).thenReturn(true);
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversationWithSeq(42L));

        SendMessageRequest req = sendRequest("hello", null);
        req.setMessageType("text");
        req.setReplyToId(7L);
        req.setMentions(List.of("ai"));
        req.setAttachments(List.of(Map.of("url", "u")));
        req.setCardPayload(Map.of("type", "card"));
        req.setTriageBucket("light_chat");
        req.setTriageReasonCodes("[\"x\"]");

        ImMessage result = service.sendMessage(req, USER_ID, TENANT_ID);

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insert(captor.capture());
        ImMessage saved = captor.getValue();
        assertThat(saved.getSeq()).isEqualTo(42L);
        assertThat(saved.getContent()).isEqualTo("hello");
        assertThat(saved.getMessageType()).isEqualTo("text");
        assertThat(saved.getSenderType()).isEqualTo(ImConstants.SENDER_TYPE_HUMAN);
        assertThat(saved.getRecalled()).isFalse();
        assertThat(saved.getReplyToId()).isEqualTo(7L);
        assertThat(saved.getCardPayload()).contains("card");
        assertThat(saved.getMentions()).contains("ai");
        assertThat(saved.getTriageBucket()).isEqualTo("light_chat");
        assertThat(result).isSameAs(saved);

        verify(conversationMapper).incrementSeq(CONV_ID, TENANT_ID);
        verify(memberMapper).unhideForAllMembers(CONV_ID, TENANT_ID);
        verify(memberMapper).updateLastReadSeq(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID, 42L);
    }

    @Test
    void sendMessage_defaultsMessageTypeToText() {
        when(conversationService.isMember(CONV_ID, USER_ID, TENANT_ID)).thenReturn(true);
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversationWithSeq(1L));

        service.sendMessage(sendRequest("c", null), USER_ID, TENANT_ID);

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo("text");
    }

    // ============== getMessagesAfter/BeforeSeq ==============

    @Test
    void getMessagesAfterSeq_capsLimitAt200() {
        service.getMessagesAfterSeq(CONV_ID, 5L, 5000, TENANT_ID);
        verify(messageMapper).findAfterSeq(CONV_ID, TENANT_ID, 5L, 200);
    }

    @Test
    void getMessagesBeforeSeq_capsLimitAt200() {
        service.getMessagesBeforeSeq(CONV_ID, 100L, 999, TENANT_ID);
        verify(messageMapper).findBeforeSeq(CONV_ID, TENANT_ID, 100L, 200);
    }

    @Test
    void getMessagesAfterSeq_smallLimitPreserved() {
        service.getMessagesAfterSeq(CONV_ID, 0L, 10, TENANT_ID);
        verify(messageMapper).findAfterSeq(CONV_ID, TENANT_ID, 0L, 10);
    }

    @Test
    void markRead_delegatesToMemberMapper() {
        service.markRead(CONV_ID, USER_ID, 50L, TENANT_ID);
        verify(memberMapper).updateLastReadSeq(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN,
                USER_ID, TENANT_ID, 50L);
    }

    // ============== recallMessage ==============

    @Test
    void recallMessage_notFound_throws() {
        when(messageMapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.recallMessage(5L, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void recallMessage_tooOld_throws() {
        ImMessage m = new ImMessage();
        m.setId(5L);
        m.setCreatedAt(Instant.now().minusSeconds(300));
        when(messageMapper.selectById(5L)).thenReturn(m);

        assertThatThrownBy(() -> service.recallMessage(5L, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 minutes");
        verify(messageMapper, never()).recallMessage(anyLong(), anyLong(), anyLong());
    }

    @Test
    void recallMessage_unauthorized_throws() {
        ImMessage m = new ImMessage();
        m.setId(5L);
        m.setCreatedAt(Instant.now());
        when(messageMapper.selectById(5L)).thenReturn(m);
        when(messageMapper.recallMessage(5L, TENANT_ID, USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.recallMessage(5L, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recallMessage_success_returnsRefetched() {
        ImMessage original = new ImMessage();
        original.setId(5L);
        original.setCreatedAt(Instant.now());
        ImMessage refetched = new ImMessage();
        refetched.setId(5L);
        refetched.setRecalled(true);
        when(messageMapper.selectById(5L)).thenReturn(original).thenReturn(refetched);
        when(messageMapper.recallMessage(5L, TENANT_ID, USER_ID)).thenReturn(1);

        ImMessage result = service.recallMessage(5L, USER_ID, TENANT_ID);

        assertThat(result.getRecalled()).isTrue();
    }

    // ============== sendSystemMessage ==============

    @Test
    void sendSystemMessage_dedupReturnsExisting() {
        ImMessage existing = new ImMessage();
        existing.setId(1L);
        when(messageMapper.findByClientMsgId(CONV_ID, TENANT_ID, "sys-1")).thenReturn(existing);

        ImMessage result = service.sendSystemMessage(CONV_ID, TENANT_ID, "system", "hi", null, "sys-1");

        assertThat(result).isSameAs(existing);
        verify(conversationMapper, never()).incrementSeq(anyLong(), anyLong());
    }

    @Test
    void sendSystemMessage_persistsWithSystemSenderType() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversationWithSeq(8L));

        service.sendSystemMessage(CONV_ID, TENANT_ID, null, "joined", "{\"k\":1}", null);

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insert(captor.capture());
        ImMessage saved = captor.getValue();
        assertThat(saved.getSenderType()).isEqualTo(ImConstants.SENDER_TYPE_SYSTEM);
        assertThat(saved.getSenderId()).isEqualTo(0L);
        assertThat(saved.getMessageType()).isEqualTo("system");
        assertThat(saved.getSeq()).isEqualTo(8L);
        assertThat(saved.getCardPayload()).isEqualTo("{\"k\":1}");
    }

    // ============== sendAgentMessage ==============

    @Test
    void sendAgentMessage_dedupReturnsExisting() {
        ImMessage existing = new ImMessage();
        when(messageMapper.findByClientMsgId(CONV_ID, TENANT_ID, "ai-1")).thenReturn(existing);

        ImMessage result = service.sendAgentMessage(CONV_ID, TENANT_ID, 9L, null, "x", null, "ai-1");
        assertThat(result).isSameAs(existing);
    }

    @Test
    void sendAgentMessage_persistsAgentRow() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversationWithSeq(11L));

        service.sendAgentMessage(CONV_ID, TENANT_ID, 33L, "ai_response", "text", null, null,
                "thinking..", "sig");

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insert(captor.capture());
        ImMessage saved = captor.getValue();
        assertThat(saved.getSenderId()).isEqualTo(33L);
        assertThat(saved.getSenderType()).isEqualTo(ImConstants.SENDER_TYPE_AGENT);
        assertThat(saved.getSeq()).isEqualTo(11L);
        assertThat(saved.getThinkingContent()).isEqualTo("thinking..");
        assertThat(saved.getThinkingSignature()).isEqualTo("sig");
    }

    @Test
    void sendAgentMessage_nullAgentIdFallsBackToZero() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversationWithSeq(11L));

        service.sendAgentMessage(CONV_ID, TENANT_ID, null, null, "text", null, null);

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSenderId()).isEqualTo(0L);
        assertThat(captor.getValue().getMessageType()).isEqualTo("ai_response");
    }

    @Test
    void sendAgentMessage_emptyThinkingFieldsCoerceToNull() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(conversationWithSeq(2L));

        service.sendAgentMessage(CONV_ID, TENANT_ID, 1L, null, "x", null, null, "", "");

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getThinkingContent()).isNull();
        assertThat(captor.getValue().getThinkingSignature()).isNull();
    }

    // ============== searchMessages ==============

    @Test
    void searchMessages_specificConv_nonMember_returnsEmpty() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(null);

        List<MessageSearchResult> results = service.searchMessages("hi", CONV_ID, USER_ID, TENANT_ID, 20);
        assertThat(results).isEmpty();
        verify(messageMapper, never()).searchMessages(anyLong(), any(), anyString(), anyInt());
    }

    @Test
    void searchMessages_specificConv_member_searchesOnlyThatConv() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());

        service.searchMessages("hi", CONV_ID, USER_ID, TENANT_ID, 1000);
        verify(messageMapper).searchMessages(TENANT_ID, List.of(CONV_ID), "%hi%", 50);
    }

    @Test
    void searchMessages_global_noConvs_returnsEmpty() {
        when(memberMapper.findConversationIdsByMember(TENANT_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID))
                .thenReturn(List.of());

        assertThat(service.searchMessages("x", null, USER_ID, TENANT_ID, 10)).isEmpty();
    }

    @Test
    void searchMessages_global_passesAllConvs() {
        when(memberMapper.findConversationIdsByMember(TENANT_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID))
                .thenReturn(List.of(1L, 2L));

        service.searchMessages("k", null, USER_ID, TENANT_ID, 10);
        verify(messageMapper).searchMessages(TENANT_ID, List.of(1L, 2L), "%k%", 10);
    }

    // ============== forwardMessage ==============

    @Test
    void forwardMessage_notFound_throws() {
        when(messageMapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.forwardMessage(5L, 222L, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forwardMessage_tenantMismatch_throws() {
        ImMessage other = new ImMessage();
        other.setTenantId(999L);
        when(messageMapper.selectById(5L)).thenReturn(other);

        assertThatThrownBy(() -> service.forwardMessage(5L, 222L, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forwardMessage_persistsCopyWithForwardedFromId() {
        ImMessage orig = new ImMessage();
        orig.setId(5L);
        orig.setTenantId(TENANT_ID);
        orig.setMessageType("text");
        orig.setContent("orig");
        orig.setCardPayload("cp");
        orig.setAttachments("[]");
        Long target = 777L;
        when(messageMapper.selectById(5L)).thenReturn(orig);
        when(conversationMapper.selectById(target)).thenReturn(conversationWithSeq(99L));

        ImMessage forwarded = service.forwardMessage(5L, target, USER_ID, TENANT_ID);

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insert(captor.capture());
        ImMessage saved = captor.getValue();
        assertThat(saved.getForwardedFromId()).isEqualTo(5L);
        assertThat(saved.getConversationId()).isEqualTo(target);
        assertThat(saved.getSenderId()).isEqualTo(USER_ID);
        assertThat(saved.getContent()).isEqualTo("orig");
        assertThat(saved.getSeq()).isEqualTo(99L);
        verify(memberMapper).unhideForAllMembers(target, TENANT_ID);
        verify(memberMapper).updateLastReadSeq(target, ImConstants.MEMBER_TYPE_HUMAN, USER_ID,
                TENANT_ID, 99L);
        assertThat(forwarded).isSameAs(saved);
    }

    // ============== getReadReceipts ==============

    @Test
    void getReadReceipts_messageNotFound_throws() {
        when(messageMapper.selectById(7L)).thenReturn(null);
        assertThatThrownBy(() -> service.getReadReceipts(7L, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getReadReceipts_tenantMismatch_throws() {
        ImMessage m = new ImMessage();
        m.setTenantId(999L);
        when(messageMapper.selectById(7L)).thenReturn(m);
        assertThatThrownBy(() -> service.getReadReceipts(7L, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getReadReceipts_aggregatesCounts() {
        ImMessage m = new ImMessage();
        m.setId(7L);
        m.setTenantId(TENANT_ID);
        m.setConversationId(CONV_ID);
        m.setSenderId(USER_ID);
        m.setSenderType(ImConstants.SENDER_TYPE_HUMAN);
        m.setSeq(5L);
        when(messageMapper.selectById(7L)).thenReturn(m);
        when(memberMapper.countReadersForSeq(CONV_ID, TENANT_ID, 5L,
                ImConstants.SENDER_TYPE_HUMAN, USER_ID)).thenReturn(2);
        when(memberMapper.countMembersExcluding(CONV_ID, TENANT_ID,
                ImConstants.SENDER_TYPE_HUMAN, USER_ID)).thenReturn(3);
        ReadReceiptInfo info = ReadReceiptInfo.builder().userId(USER_ID).displayName("u").build();
        when(memberMapper.findReadersForSeq(CONV_ID, TENANT_ID, 5L,
                ImConstants.SENDER_TYPE_HUMAN, USER_ID)).thenReturn(List.of(info));

        ReadReceiptSummary summary = service.getReadReceipts(7L, TENANT_ID);
        assertThat(summary.getReadCount()).isEqualTo(2);
        assertThat(summary.getTotalMembers()).isEqualTo(3);
        assertThat(summary.getReaders()).hasSize(1);
        assertThat(summary.getMessageId()).isEqualTo(7L);
    }

    @Test
    void getReadReceipts_nullSenderTypeDefaultsToHuman() {
        ImMessage m = new ImMessage();
        m.setId(7L);
        m.setTenantId(TENANT_ID);
        m.setConversationId(CONV_ID);
        m.setSenderId(USER_ID);
        m.setSenderType(null);
        m.setSeq(5L);
        when(messageMapper.selectById(7L)).thenReturn(m);
        when(memberMapper.findReadersForSeq(anyLong(), anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(List.of());

        service.getReadReceipts(7L, TENANT_ID);
        verify(memberMapper).countReadersForSeq(eq(CONV_ID), eq(TENANT_ID), eq(5L),
                eq(ImConstants.SENDER_TYPE_HUMAN), eq(USER_ID));
    }
}
