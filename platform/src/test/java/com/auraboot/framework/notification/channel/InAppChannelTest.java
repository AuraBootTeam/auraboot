package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationQueryService;
import com.auraboot.framework.notification.service.NotificationSseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InAppChannelTest {

    @Mock NotificationMapper mapper;
    @Mock NotificationSseService sseService;
    @Mock NotificationQueryService queryService;

    private InAppChannel channel() {
        return new InAppChannel(mapper, sseService, queryService);
    }

    @Test
    void getChannelCode_returnsInApp() {
        assertEquals("in_app", channel().getChannelCode());
        assertTrue(channel().isAvailable());
    }

    @Test
    void send_persistsAndPushesUnreadCount_perRecipient() {
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L)
                .recipientUserIds(List.of(1L, 2L))
                .subject("Hello")
                .body("Hi there")
                .category("approval")
                .sourceType("task")
                .sourceId("t-1")
                .build();
        when(queryService.getUnreadCount(1L)).thenReturn(3);
        when(queryService.getUnreadCount(2L)).thenReturn(5);

        NotificationResult result = channel().send(msg);

        assertTrue(result.isSuccess());
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(mapper, times(2)).insert(cap.capture());
        List<Notification> saved = cap.getAllValues();
        assertThat(saved).extracting(Notification::getUserId).containsExactly(1L, 2L);
        assertThat(saved.get(0).getTenantId()).isEqualTo(10L);
        assertThat(saved.get(0).getTitle()).isEqualTo("Hello");
        assertThat(saved.get(0).getCategory()).isEqualTo("approval");
        assertThat(saved.get(0).getPriority()).isEqualTo("normal");
        assertThat(saved.get(0).getIsRead()).isFalse();
        verify(sseService).pushUnreadCount(1L, 3);
        verify(sseService).pushUnreadCount(2L, 5);
    }

    @Test
    void send_nullSubject_andNullCategory_useDefaults() {
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L)
                .recipientUserIds(List.of(1L))
                .body("Hi")
                .build();

        channel().send(msg);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getTitle()).isEmpty();
        assertThat(cap.getValue().getCategory()).isEqualTo("system");
    }

    @Test
    void send_mapperThrows_returnsFailure() {
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L)
                .recipientUserIds(List.of(1L))
                .body("Hi")
                .build();
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(Notification.class));

        NotificationResult result = channel().send(msg);

        assertFalse(result.isSuccess());
        assertThat(result.getErrorMessage()).contains("db down");
    }

    @Test
    void send_sseFailure_doesNotFailOverallSend() {
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(10L)
                .recipientUserIds(List.of(1L))
                .body("Hi")
                .build();
        when(queryService.getUnreadCount(1L)).thenThrow(new RuntimeException("sse down"));

        NotificationResult result = channel().send(msg);

        assertTrue(result.isSuccess());
        verify(mapper).insert(any(Notification.class));
    }
}
