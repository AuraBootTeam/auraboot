package com.auraboot.framework.notification.service;

import com.auraboot.framework.notification.entity.NotificationPreference;
import com.auraboot.framework.notification.mapper.NotificationPreferenceMapper;
import com.auraboot.framework.notification.service.impl.NotificationPreferenceServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationPreferenceService.
 *
 * @since 6.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationPreferenceService")
class NotificationPreferenceServiceTest {

    @Mock
    private NotificationPreferenceMapper preferenceMapper;

    @InjectMocks
    private NotificationPreferenceServiceImpl preferenceService;

    // ==================== isEnabled Tests ====================

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("returns true by default when no preference record exists")
        void returnsTrueByDefault() {
            when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

            boolean result = preferenceService.isEnabled(1L, "email", "business");

            assertTrue(result, "Should be enabled by default (opt-out model)");
        }

        @Test
        @DisplayName("returns false when user has opted out")
        void returnsFalseWhenOptedOut() {
            NotificationPreference pref = buildPreference(1L, "email", "business", false);
            when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(pref);

            boolean result = preferenceService.isEnabled(1L, "email", "business");

            assertFalse(result, "Should be disabled when user opted out");
        }

        @Test
        @DisplayName("returns true for SYSTEM+IN_APP even when opted out (forced on)")
        void returnsTrueForSystemInAppForcedOn() {
            // Even if there's an opt-out record, SYSTEM+IN_APP should be forced on
            // The method should return true WITHOUT even querying the DB
            boolean result = preferenceService.isEnabled(1L, "in_app", "system");

            assertTrue(result, "SYSTEM+IN_APP should always be forced on");
            // Verify mapper was never called — forced on skips DB
            verify(preferenceMapper, never()).selectOne(any());
        }

        @Test
        @DisplayName("returns true when preference record exists with enabled=true")
        void returnsTrueWhenExplicitlyEnabled() {
            NotificationPreference pref = buildPreference(1L, "email", "approval", true);
            when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(pref);

            boolean result = preferenceService.isEnabled(1L, "email", "approval");

            assertTrue(result);
        }
    }

    // ==================== filterRecipients Tests ====================

    @Nested
    @DisplayName("filterRecipients()")
    class FilterRecipientsTests {

        @Test
        @DisplayName("returns all users when no preferences exist")
        void returnsAllUsersWhenNoPreferences() {
            when(preferenceMapper.selectList(any(QueryWrapper.class)))
                    .thenReturn(Collections.emptyList());

            List<Long> result = preferenceService.filterRecipients(
                    List.of(1L, 2L, 3L), "email", "business");

            assertEquals(List.of(1L, 2L, 3L), result);
        }

        @Test
        @DisplayName("filters out opted-out users")
        void filtersOutOptedOutUsers() {
            NotificationPreference optOut = buildPreference(2L, "email", "business", false);
            when(preferenceMapper.selectList(any(QueryWrapper.class)))
                    .thenReturn(List.of(optOut));

            List<Long> result = preferenceService.filterRecipients(
                    List.of(1L, 2L, 3L), "email", "business");

            assertEquals(List.of(1L, 3L), result, "User 2 should be filtered out");
        }

        @Test
        @DisplayName("returns all users for SYSTEM+IN_APP (forced on, no filtering)")
        void returnsAllForSystemInApp() {
            List<Long> result = preferenceService.filterRecipients(
                    List.of(1L, 2L, 3L), "in_app", "system");

            assertEquals(List.of(1L, 2L, 3L), result);
            // Verify mapper was never called — forced on skips DB
            verify(preferenceMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("returns empty list for null input")
        void returnsEmptyForNullInput() {
            List<Long> result = preferenceService.filterRecipients(
                    null, "email", "business");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty list for empty input")
        void returnsEmptyForEmptyInput() {
            List<Long> result = preferenceService.filterRecipients(
                    List.of(), "email", "business");

            assertTrue(result.isEmpty());
        }
    }

    // ==================== updatePreference Tests ====================

    @Nested
    @DisplayName("updatePreference()")
    class UpdatePreferenceTests {

        @Test
        @DisplayName("creates new record when none exists")
        void createsNewRecord() {
            when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            when(preferenceMapper.insert(any(NotificationPreference.class))).thenReturn(1);

            preferenceService.updatePreference(1L, "email", "business", false);

            ArgumentCaptor<NotificationPreference> captor =
                    ArgumentCaptor.forClass(NotificationPreference.class);
            verify(preferenceMapper).insert(captor.capture());

            NotificationPreference inserted = captor.getValue();
            assertEquals(1L, inserted.getUserId());
            assertEquals("email", inserted.getChannel());
            assertEquals("business", inserted.getCategory());
            assertFalse(inserted.getEnabled());
        }

        @Test
        @DisplayName("updates existing record")
        void updatesExistingRecord() {
            NotificationPreference existing = buildPreference(1L, "email", "business", true);
            existing.setId(42L);
            when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
            when(preferenceMapper.updateById(any(NotificationPreference.class))).thenReturn(1);

            preferenceService.updatePreference(1L, "email", "business", false);

            ArgumentCaptor<NotificationPreference> captor =
                    ArgumentCaptor.forClass(NotificationPreference.class);
            verify(preferenceMapper).updateById(captor.capture());
            verify(preferenceMapper, never()).insert(any(NotificationPreference.class));

            NotificationPreference updated = captor.getValue();
            assertEquals(42L, updated.getId());
            assertFalse(updated.getEnabled());
        }
    }

    // ==================== getPreferences Tests ====================

    @Nested
    @DisplayName("getPreferences()")
    class GetPreferencesTests {

        @Test
        @DisplayName("returns all preferences for user")
        void returnsAllPreferencesForUser() {
            NotificationPreference p1 = buildPreference(1L, "email", "business", false);
            NotificationPreference p2 = buildPreference(1L, "wechat_work", "approval", true);
            when(preferenceMapper.selectList(any(QueryWrapper.class)))
                    .thenReturn(List.of(p1, p2));

            List<NotificationPreference> result = preferenceService.getPreferences(1L);

            assertEquals(2, result.size());
            assertEquals("email", result.get(0).getChannel());
            assertEquals("wechat_work", result.get(1).getChannel());
        }
    }

    // ==================== Helpers ====================

    private NotificationPreference buildPreference(Long userId, String channel,
                                                    String category, boolean enabled) {
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setChannel(channel);
        pref.setCategory(category);
        pref.setEnabled(enabled);
        return pref;
    }
}
