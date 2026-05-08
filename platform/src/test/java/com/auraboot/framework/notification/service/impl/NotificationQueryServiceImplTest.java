package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.notification.dto.NotificationDTO;
import com.auraboot.framework.notification.dto.NotificationQueryRequest;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationSseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationQueryServiceImpl")
class NotificationQueryServiceImplTest {

    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationSseService notificationSseService;

    @InjectMocks
    private NotificationQueryServiceImpl service;

    private MockedStatic<MetaContext> metaContextMock;

    @BeforeEach
    void setUp() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
    }

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) metaContextMock.close();
    }

    private Notification entity(Long id, boolean read) {
        Notification n = new Notification();
        n.setId(id);
        n.setTitle("t" + id);
        n.setContent("c" + id);
        n.setCategory("SYSTEM");
        n.setPriority("NORMAL");
        n.setSourceType("system");
        n.setSourceId("src" + id);
        n.setIsRead(read);
        n.setReadAt(read ? Instant.now() : null);
        n.setCreatedAt(Instant.now());
        return n;
    }

    @Test
    @DisplayName("listByUser unread branch invokes findUnreadByUser + countUnread")
    void listUnread() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setIsRead(false);
        req.setPageNum(2);
        req.setPageSize(10);

        when(notificationMapper.findUnreadByUser(eq(99L), eq(7L), eq(10), eq(10)))
                .thenReturn(List.of(entity(1L, false)));
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(1);

        PaginationResult<NotificationDTO> result = service.listByUser(7L, req);
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        verify(notificationMapper, never()).findByUser(eq(99L), eq(7L), anyInt(), anyInt());
    }

    @Test
    @DisplayName("listByUser default (isRead=null) uses findByUser + countByUser")
    void listAll() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setPageNum(1);
        req.setPageSize(20);

        when(notificationMapper.findByUser(eq(99L), eq(7L), eq(20), eq(0)))
                .thenReturn(List.of(entity(1L, true), entity(2L, false)));
        when(notificationMapper.countByUser(99L, 7L)).thenReturn(2L);

        PaginationResult<NotificationDTO> result = service.listByUser(7L, req);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("listByUser clamps pageSize to [1,100] and pageNum to >=1")
    void listClampsParams() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setPageNum(0);     // -> 1
        req.setPageSize(500);  // -> 100

        when(notificationMapper.findByUser(eq(99L), eq(7L), eq(100), eq(0)))
                .thenReturn(List.of());
        when(notificationMapper.countByUser(99L, 7L)).thenReturn(0L);

        PaginationResult<NotificationDTO> result = service.listByUser(7L, req);
        assertNotNull(result);
        verify(notificationMapper).findByUser(99L, 7L, 100, 0);
    }

    @Test
    @DisplayName("getUnreadCount delegates with current tenant")
    void unreadCount() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(5);
        assertEquals(5, service.getUnreadCount(7L));
    }

    @Test
    @DisplayName("markAsRead pushes unread count via SSE when user found")
    void markAsRead() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(notificationMapper.findUserIdById(99L, 100L)).thenReturn(7L);
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(3);

        service.markAsRead(100L);
        verify(notificationMapper).markAsRead(99L, 100L);
        verify(notificationSseService).pushUnreadCount(7L, 3);
    }

    @Test
    @DisplayName("markAsRead skips SSE push when notification owner unknown")
    void markAsReadNoOwner() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(notificationMapper.findUserIdById(99L, 100L)).thenReturn(null);

        service.markAsRead(100L);
        verify(notificationMapper).markAsRead(99L, 100L);
        verify(notificationSseService, never()).pushUnreadCount(org.mockito.ArgumentMatchers.anyLong(), anyInt());
    }

    @Test
    @DisplayName("markAllAsRead pushes updated count")
    void markAllAsRead() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(0);

        service.markAllAsRead(7L);
        verify(notificationMapper).markAllAsRead(99L, 7L);
        verify(notificationSseService).pushUnreadCount(7L, 0);
    }

    @Test
    @DisplayName("markAllAsRead swallows SSE failures")
    void markAllAsReadSseFailure() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(0);
        Mockito.doThrow(new RuntimeException("sse down"))
                .when(notificationSseService).pushUnreadCount(7L, 0);

        // should not throw
        service.markAllAsRead(7L);
        verify(notificationMapper).markAllAsRead(99L, 7L);
    }
}
