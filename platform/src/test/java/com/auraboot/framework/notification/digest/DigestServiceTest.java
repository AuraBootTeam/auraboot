package com.auraboot.framework.notification.digest;

import com.auraboot.framework.notification.mapper.DigestEntryMapper;
import com.auraboot.framework.notification.channel.NotificationChannel;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.NotificationResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DigestService.
 *
 * @since 6.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DigestService")
class DigestServiceTest {

    @Mock
    private DigestEntryMapper digestMapper;

    @Mock
    private NotificationChannel emailChannel;

    @Mock
    private NotificationChannel inAppChannel;

    private DigestService digestService;

    @BeforeEach
    void setUp() {
        lenient().when(emailChannel.getChannelCode()).thenReturn("email");
        lenient().when(inAppChannel.getChannelCode()).thenReturn("in_app");
        digestService = new DigestService(digestMapper, List.of(emailChannel, inAppChannel));
    }

    // ==================== accumulate Tests ====================

    @Nested
    @DisplayName("accumulate()")
    class AccumulateTests {

        @Test
        @DisplayName("creates new entry when none exists")
        void createsNewEntryWhenNoneExists() {
            when(digestMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

            digestService.accumulate(1L, 42L, "email", "orderCreated", "business");

            ArgumentCaptor<DigestEntry> captor = ArgumentCaptor.forClass(DigestEntry.class);
            verify(digestMapper).insert(captor.capture());

            DigestEntry entry = captor.getValue();
            assertEquals(1L, entry.getTenantId());
            assertEquals(42L, entry.getUserId());
            assertEquals("email", entry.getChannel());
            assertEquals("orderCreated", entry.getTemplateCode());
            assertEquals("business", entry.getCategory());
            assertEquals(1, entry.getCount());
            assertFalse(entry.getFlushed());
            assertNotNull(entry.getWindowStart());
        }

        @Test
        @DisplayName("increments count on existing entry within window")
        void incrementsCountOnExistingEntry() {
            DigestEntry existing = new DigestEntry();
            existing.setId(100L);
            existing.setTenantId(1L);
            existing.setUserId(42L);
            existing.setChannel("email");
            existing.setTemplateCode("orderCreated");
            existing.setCategory("business");
            existing.setCount(2);
            existing.setFlushed(false);
            existing.setWindowStart(Instant.now().minus(Duration.ofMinutes(2)));

            when(digestMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

            digestService.accumulate(1L, 42L, "email", "orderCreated", "business");

            verify(digestMapper).updateById(existing);
            assertEquals(3, existing.getCount());
            verify(digestMapper, never()).insert(any(DigestEntry.class));
        }

        @Test
        @DisplayName("creates new entry when existing is outside window")
        void createsNewEntryWhenOutsideWindow() {
            // selectOne returns null because the query includes window_start >= cutoff
            when(digestMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

            digestService.accumulate(1L, 42L, "email", "orderCreated", "business");

            verify(digestMapper).insert(any(DigestEntry.class));
            verify(digestMapper, never()).updateById(any(DigestEntry.class));
        }
    }

    // ==================== flushDigests Tests ====================

    @Nested
    @DisplayName("flushDigests()")
    class FlushDigestsTests {

        @Test
        @DisplayName("sends notification for entries above threshold")
        void sendsNotificationAboveThreshold() {
            DigestEntry entry = buildEntry(1L, 42L, "email", "orderCreated", "business", 5);
            when(digestMapper.findFlushableEntriesForUpdate(anyInt(), any(Instant.class)))
                    .thenReturn(List.of(entry));
            when(emailChannel.isAvailable()).thenReturn(true);
            when(emailChannel.send(any())).thenReturn(NotificationResult.ok());

            digestService.flushDigests();

            ArgumentCaptor<NotificationMessage> captor =
                    ArgumentCaptor.forClass(NotificationMessage.class);
            verify(emailChannel).send(captor.capture());

            NotificationMessage msg = captor.getValue();
            assertEquals(1L, msg.getTenantId());
            assertEquals(List.of(42L), msg.getRecipientUserIds());
            assertTrue(msg.getSubject().contains("5"));
            assertTrue(msg.getSubject().contains("业务"));
            assertEquals("business", msg.getCategory());
            assertEquals("orderCreated", msg.getTemplateCode());
        }

        @Test
        @DisplayName("sends notification for expired window entries")
        void sendsNotificationForExpiredWindow() {
            DigestEntry entry = buildEntry(1L, 42L, "email", "orderCreated", "approval", 1);
            entry.setWindowStart(Instant.now().minus(Duration.ofMinutes(10))); // Expired window
            when(digestMapper.findFlushableEntriesForUpdate(anyInt(), any(Instant.class)))
                    .thenReturn(List.of(entry));
            when(emailChannel.isAvailable()).thenReturn(true);
            when(emailChannel.send(any())).thenReturn(NotificationResult.ok());

            digestService.flushDigests();

            verify(emailChannel).send(any());
            assertTrue(entry.getFlushed());
            assertNotNull(entry.getWindowEnd());
        }

        @Test
        @DisplayName("marks entries as flushed after sending")
        void marksEntriesAsFlushed() {
            DigestEntry entry = buildEntry(1L, 42L, "email", "orderCreated", "system", 4);
            when(digestMapper.findFlushableEntriesForUpdate(anyInt(), any(Instant.class)))
                    .thenReturn(List.of(entry));
            when(emailChannel.isAvailable()).thenReturn(true);
            when(emailChannel.send(any())).thenReturn(NotificationResult.ok());

            digestService.flushDigests();

            assertTrue(entry.getFlushed());
            assertNotNull(entry.getWindowEnd());
            verify(digestMapper).updateById(entry);
        }

        @Test
        @DisplayName("skips entries below threshold within window")
        void skipsEntriesBelowThreshold() {
            // findFlushableEntriesForUpdate only returns entries meeting criteria;
            // if empty, nothing is processed
            when(digestMapper.findFlushableEntriesForUpdate(anyInt(), any(Instant.class)))
                    .thenReturn(List.of());

            digestService.flushDigests();

            verify(emailChannel, never()).send(any());
            verify(digestMapper, never()).updateById(any(DigestEntry.class));
        }

        @Test
        @DisplayName("still marks as flushed when channel is unavailable")
        void marksAsFlushedWhenChannelUnavailable() {
            DigestEntry entry = buildEntry(1L, 42L, "email", "orderCreated", "alert", 5);
            when(digestMapper.findFlushableEntriesForUpdate(anyInt(), any(Instant.class)))
                    .thenReturn(List.of(entry));
            when(emailChannel.isAvailable()).thenReturn(false);

            digestService.flushDigests();

            verify(emailChannel, never()).send(any());
            assertTrue(entry.getFlushed());
            verify(digestMapper).updateById(entry);
        }

        @Test
        @DisplayName("handles send failure gracefully — entry still marked as flushed (idempotent)")
        void handlesSendFailureGracefully() {
            DigestEntry entry = buildEntry(1L, 42L, "email", "orderCreated", "business", 5);
            when(digestMapper.findFlushableEntriesForUpdate(anyInt(), any(Instant.class)))
                    .thenReturn(List.of(entry));
            when(emailChannel.isAvailable()).thenReturn(true);
            when(emailChannel.send(any())).thenThrow(new RuntimeException("SMTP error"));

            digestService.flushDigests();

            // Entry is marked flushed BEFORE sending (idempotent design — prevents duplicate sends)
            assertTrue(entry.getFlushed());
            verify(digestMapper).updateById(entry);
        }
    }

    // ==================== getCategoryLabel Tests ====================

    @Nested
    @DisplayName("getCategoryLabel()")
    class CategoryLabelTests {

        @Test
        @DisplayName("returns correct labels for known categories")
        void returnsCorrectLabels() {
            assertEquals("业务", digestService.getCategoryLabel("business"));
            assertEquals("审批", digestService.getCategoryLabel("approval"));
            assertEquals("系统", digestService.getCategoryLabel("system"));
            assertEquals("告警", digestService.getCategoryLabel("alert"));
        }

        @Test
        @DisplayName("returns raw value for unknown category")
        void returnsRawForUnknown() {
            assertEquals("custom", digestService.getCategoryLabel("custom"));
        }
    }

    // ==================== Helpers ====================

    private DigestEntry buildEntry(Long tenantId, Long userId, String channel,
                                    String templateCode, String category, int count) {
        DigestEntry entry = new DigestEntry();
        entry.setId(1L);
        entry.setTenantId(tenantId);
        entry.setUserId(userId);
        entry.setChannel(channel);
        entry.setTemplateCode(templateCode);
        entry.setCategory(category);
        entry.setCount(count);
        entry.setWindowStart(Instant.now().minus(Duration.ofMinutes(2)));
        entry.setFlushed(false);
        return entry;
    }
}
