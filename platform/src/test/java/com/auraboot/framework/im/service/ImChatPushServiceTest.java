package com.auraboot.framework.im.service;

import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.websocket.ImSessionRegistry;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.PushNotificationChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImChatPushService}.
 */
@ExtendWith(MockitoExtension.class)
class ImChatPushServiceTest {

    @Mock private PushNotificationChannel pushNotificationChannel;
    @Mock private ImSessionRegistry sessionRegistry;
    @Mock private ImConversationMemberMapper memberMapper;
    @Mock private ImConversationService conversationService;

    @InjectMocks private ImChatPushService service;

    private ImMessage message(String content, String type) {
        ImMessage m = new ImMessage();
        m.setId(7777L);
        m.setConversationId(50L);
        m.setSenderId(10L);
        m.setContent(content);
        m.setMessageType(type);
        return m;
    }

    private ConversationMemberInfo member(Long memberId, String displayName) {
        ConversationMemberInfo info = new ConversationMemberInfo();
        info.setMemberId(memberId);
        info.setDisplayName(displayName);
        return info;
    }

    @Test
    void noop_when_no_offline_recipients() {
        ImMessage msg = message("hello", "TEXT");
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L));

        service.pushToOfflineMembers(msg, 10L, 1L);

        verify(pushNotificationChannel, never()).send(any());
    }

    @Test
    void noop_when_all_recipients_online() {
        ImMessage msg = message("hello", "TEXT");
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L, 20L, 30L));
        when(sessionRegistry.isOnline(20L)).thenReturn(true);
        when(sessionRegistry.isOnline(30L)).thenReturn(true);

        service.pushToOfflineMembers(msg, 10L, 1L);

        verify(pushNotificationChannel, never()).send(any());
    }

    @Test
    void pushes_to_offline_members_excluding_sender_and_online() {
        ImMessage msg = message("hello world", "TEXT");
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L, 20L, 30L, 40L));
        when(sessionRegistry.isOnline(20L)).thenReturn(true);   // online
        when(sessionRegistry.isOnline(30L)).thenReturn(false);  // offline
        when(sessionRegistry.isOnline(40L)).thenReturn(false);  // offline
        when(conversationService.getMembers(50L, 1L)).thenReturn(List.of(
                member(10L, "Alice")
        ));

        service.pushToOfflineMembers(msg, 10L, 1L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        NotificationMessage sent = captor.getValue();
        assertThat(sent.getRecipientUserIds()).containsExactlyInAnyOrder(30L, 40L);
        assertThat(sent.getSubject()).isEqualTo("Alice");
        assertThat(sent.getBody()).isEqualTo("hello world");
        assertThat(sent.getCategory()).isEqualTo("chat");
        assertThat(sent.getSourceType()).isEqualTo("im_message");
        assertThat(sent.getExtras()).containsEntry("conversationId", 50L);
    }

    @Test
    void uses_System_as_sender_name_when_sender_is_zero() {
        ImMessage msg = message("hi", "TEXT");
        msg.setSenderId(0L);
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(0L, 20L));
        when(sessionRegistry.isOnline(20L)).thenReturn(false);

        service.pushToOfflineMembers(msg, 0L, 1L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("System");
    }

    @Test
    void falls_back_when_member_lookup_throws() {
        ImMessage msg = message("hi", "TEXT");
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L, 20L));
        when(sessionRegistry.isOnline(20L)).thenReturn(false);
        when(conversationService.getMembers(50L, 1L)).thenThrow(new RuntimeException("boom"));

        service.pushToOfflineMembers(msg, 10L, 1L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("User 10");
    }

    @Test
    void sender_name_falls_back_to_user_id_when_not_in_member_list() {
        ImMessage msg = message("hi", "TEXT");
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L, 20L));
        when(sessionRegistry.isOnline(20L)).thenReturn(false);
        // members list does not include the sender
        when(conversationService.getMembers(50L, 1L)).thenReturn(List.of(member(99L, "Other")));

        service.pushToOfflineMembers(msg, 10L, 1L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("User 10");
    }

    @Test
    void body_uses_image_placeholder_for_image_type_with_blank_content() {
        ImMessage msg = message("", "image");
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L, 20L));
        when(sessionRegistry.isOnline(20L)).thenReturn(false);
        when(conversationService.getMembers(50L, 1L)).thenReturn(List.of(member(10L, "Alice")));

        service.pushToOfflineMembers(msg, 10L, 1L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("[Image]");
    }

    @Test
    void body_uses_file_card_voice_message_placeholders_for_other_types() {
        runTypePlaceholder("file", "[File]");
        runTypePlaceholder("card", "[Card]");
        runTypePlaceholder("voice", "[Voice]");
        runTypePlaceholder("unknown", "[Message]");
    }

    private void runTypePlaceholder(String type, String expected) {
        ImChatPushService localService = new ImChatPushService(
                pushNotificationChannel, sessionRegistry, memberMapper, conversationService);
        ImMessage msg = message(null, type);
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L, 20L));
        when(sessionRegistry.isOnline(20L)).thenReturn(false);
        when(conversationService.getMembers(50L, 1L)).thenReturn(List.of(member(10L, "Alice")));

        localService.pushToOfflineMembers(msg, 10L, 1L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo(expected);
        org.mockito.Mockito.reset(pushNotificationChannel);
    }

    @Test
    void body_truncates_long_content() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) sb.append('x');
        ImMessage msg = message(sb.toString(), "TEXT");
        when(memberMapper.findHumanMemberIds(50L, 1L)).thenReturn(List.of(10L, 20L));
        when(sessionRegistry.isOnline(20L)).thenReturn(false);
        when(conversationService.getMembers(50L, 1L)).thenReturn(List.of(member(10L, "Alice")));

        service.pushToOfflineMembers(msg, 10L, 1L);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(pushNotificationChannel).send(captor.capture());
        String body = captor.getValue().getBody();
        assertThat(body).hasSize(200 + 3);
        assertThat(body).endsWith("...");
    }
}
