package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.notification.entity.NotificationPreference;
import com.auraboot.framework.notification.mapper.NotificationPreferenceMapper;
import com.auraboot.framework.notification.service.NotificationPreferenceService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of NotificationPreferenceService.
 *
 * Opt-out model: all channels enabled by default. Only explicit records
 * with enabled=false disable a channel+category combination.
 *
 * SYSTEM category + IN_APP channel is forced on regardless of preferences.
 *
 * @since 6.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceMapper preferenceMapper;

    @Override
    public List<NotificationPreference> getPreferences(Long userId) {
        return preferenceMapper.selectList(
                new QueryWrapper<NotificationPreference>()
                        .eq("user_id", userId));
    }

    @Override
    @Transactional
    public void updatePreference(Long userId, String channel, String category, boolean enabled) {
        NotificationPreference existing = preferenceMapper.selectOne(
                new QueryWrapper<NotificationPreference>()
                        .eq("user_id", userId)
                        .eq("channel", channel)
                        .eq("category", category));

        if (existing != null) {
            existing.setEnabled(enabled);
            preferenceMapper.updateById(existing);
        } else {
            NotificationPreference pref = new NotificationPreference();
            pref.setUserId(userId);
            pref.setChannel(channel);
            pref.setCategory(category);
            pref.setEnabled(enabled);
            preferenceMapper.insert(pref);
        }
    }

    @Override
    public boolean isEnabled(Long userId, String channel, String category) {
        // SYSTEM + IN_APP is always forced on
        if ("system".equalsIgnoreCase(category) && "in_app".equalsIgnoreCase(channel)) {
            return true;
        }

        NotificationPreference pref = preferenceMapper.selectOne(
                new QueryWrapper<NotificationPreference>()
                        .eq("user_id", userId)
                        .eq("channel", channel)
                        .eq("category", category));

        // Opt-out model: enabled by default when no record exists
        if (pref == null) {
            return true;
        }
        return Boolean.TRUE.equals(pref.getEnabled());
    }

    @Override
    public List<Long> filterRecipients(List<Long> userIds, String channel, String category) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        // SYSTEM + IN_APP is forced on — no filtering needed
        if ("system".equalsIgnoreCase(category) && "in_app".equalsIgnoreCase(channel)) {
            return userIds;
        }

        // Find users who have explicitly opted out (enabled=false)
        List<NotificationPreference> optedOut = preferenceMapper.selectList(
                new QueryWrapper<NotificationPreference>()
                        .eq("channel", channel)
                        .eq("category", category)
                        .eq("enabled", false)
                        .in("user_id", userIds));

        if (optedOut.isEmpty()) {
            return userIds;
        }

        Set<Long> excludedUserIds = optedOut.stream()
                .map(NotificationPreference::getUserId)
                .collect(Collectors.toSet());

        return userIds.stream()
                .filter(id -> !excludedUserIds.contains(id))
                .toList();
    }
}
