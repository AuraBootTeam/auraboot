package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.notification.dto.NotificationDTO;
import com.auraboot.framework.notification.dto.NotificationQueryRequest;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationSseService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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

    /**
     * LambdaQueryWrapper resolves column names through MyBatis-Plus's TableInfo cache,
     * which only gets populated when a SqlSessionFactory boots. These are plain Mockito
     * tests with no Spring context, so the entity is registered by hand — otherwise every
     * wrapper build fails with "can not find lambda cache for this entity".
     */
    @BeforeAll
    static void registerEntityMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Notification.class);
    }

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

    /** SQL fragment (WHERE clause + LIMIT) of the wrapper the service handed the mapper. */
    private String capturedListSql() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Notification>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(notificationMapper).selectList(captor.capture());
        LambdaQueryWrapper<Notification> w = captor.getValue();
        String sql = w.getSqlSegment() == null ? "" : w.getSqlSegment();
        for (Object v : w.getParamNameValuePairs().values()) {
            sql = sql.replaceFirst("#\\{[^}]+}", String.valueOf(v));
        }
        return sql.toLowerCase();
    }

    @Test
    @DisplayName("listByUser unread branch filters is_read = false")
    void listUnread() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setIsRead(false);
        req.setPageNum(2);
        req.setPageSize(10);

        when(notificationMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(entity(1L, false)));
        when(notificationMapper.selectCount(ArgumentMatchers.any())).thenReturn(1L);

        PaginationResult<NotificationDTO> result = service.listByUser(7L, req);
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        String sql = capturedListSql();
        assertTrue(sql.contains("is_read"), "unread query must constrain is_read, got: " + sql);
        assertTrue(sql.contains("limit 10 offset 10"), "pagination must reach SQL, got: " + sql);
    }

    @Test
    @DisplayName("listByUser default (isRead=null) constrains neither is_read nor category")
    void listAll() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setPageNum(1);
        req.setPageSize(20);

        when(notificationMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(entity(1L, true), entity(2L, false)));
        when(notificationMapper.selectCount(ArgumentMatchers.any())).thenReturn(2L);

        PaginationResult<NotificationDTO> result = service.listByUser(7L, req);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
        String sql = capturedListSql();
        assertFalse(sql.contains("is_read"), "no read filter was asked for, got: " + sql);
        assertFalse(sql.contains("category"), "no category filter was asked for, got: " + sql);
    }

    @Test
    @DisplayName("listByUser honours the category filter (regression: it used to be ignored)")
    void listFiltersByCategory() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setCategory("Approval");
        req.setPageNum(1);
        req.setPageSize(20);

        when(notificationMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(entity(1L, false)));
        when(notificationMapper.selectCount(ArgumentMatchers.any())).thenReturn(1L);

        service.listByUser(7L, req);

        // The category the caller asked for must actually reach SQL. Dropping the
        // `.apply(...)` in the service turns this red — which is the whole point:
        // the old implementation accepted `category` and silently discarded it.
        String sql = capturedListSql();
        assertTrue(sql.contains("category"), "category filter missing from SQL: " + sql);
        assertTrue(sql.contains("approval"), "category value must be lower-cased into SQL: " + sql);
    }

    @Test
    @DisplayName("listByUser combines category AND read-state instead of letting one win")
    void listCombinesCategoryAndReadState() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setCategory("alert");
        req.setIsRead(true);
        req.setPageNum(1);
        req.setPageSize(20);

        when(notificationMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(entity(1L, true)));
        when(notificationMapper.selectCount(ArgumentMatchers.any())).thenReturn(1L);

        service.listByUser(7L, req);

        String sql = capturedListSql();
        assertTrue(sql.contains("category"), "category dropped when combined with isRead: " + sql);
        assertTrue(sql.contains("is_read"), "isRead dropped when combined with category: " + sql);
    }

    @Test
    @DisplayName("listByUser clamps pageSize to [1,100] and pageNum to >=1")
    void listClampsParams() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        NotificationQueryRequest req = new NotificationQueryRequest();
        req.setPageNum(0);     // -> 1
        req.setPageSize(500);  // -> 100

        when(notificationMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of());
        when(notificationMapper.selectCount(ArgumentMatchers.any())).thenReturn(0L);

        PaginationResult<NotificationDTO> result = service.listByUser(7L, req);
        assertNotNull(result);
        assertTrue(capturedListSql().contains("limit 100 offset 0"));
    }

    @Test
    @DisplayName("deleteByIds scopes the DELETE to the caller and refreshes the unread badge")
    void deleteByIdsScoped() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(notificationMapper.delete(ArgumentMatchers.any())).thenReturn(2);
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(1);

        assertEquals(2, service.deleteByIds(7L, List.of(10L, 11L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Notification>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(notificationMapper).delete(captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        // A caller must never be able to delete another member's rows by guessing ids.
        assertTrue(sql.contains("tenant_id"), "DELETE must be tenant-scoped: " + sql);
        assertTrue(sql.contains("user_id"), "DELETE must be user-scoped: " + sql);
        verify(notificationSseService).pushUnreadCount(7L, 1);
    }

    @Test
    @DisplayName("deleteByIds with no ids touches nothing")
    void deleteByIdsEmpty() {
        assertEquals(0, service.deleteByIds(7L, List.of()));
        verify(notificationMapper, never()).delete(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("getUnreadCount delegates with current tenant")
    void unreadCount() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(5);
        assertEquals(5, service.getUnreadCount(7L));
    }

    @Test
    @DisplayName("markAsRead scopes the UPDATE by the caller's userId and pushes SSE when owned")
    void markAsRead() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(7L);
        // caller (userId=7) owns the notification → 1 row updated
        when(notificationMapper.markAsRead(99L, 100L, 7L)).thenReturn(1);
        when(notificationMapper.countUnread(99L, 7L)).thenReturn(3);

        service.markAsRead(100L);
        // security: UPDATE is scoped by the caller's own userId (not an untrusted id)
        verify(notificationMapper).markAsRead(99L, 100L, 7L);
        verify(notificationSseService).pushUnreadCount(7L, 3);
    }

    @Test
    @DisplayName("markAsRead on a notification the caller does not own updates nothing and pushes no SSE")
    void markAsReadNoOwner() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(7L);
        // notification 100 does not belong to caller 7 → 0 rows updated
        when(notificationMapper.markAsRead(99L, 100L, 7L)).thenReturn(0);

        service.markAsRead(100L);
        verify(notificationMapper).markAsRead(99L, 100L, 7L);
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
