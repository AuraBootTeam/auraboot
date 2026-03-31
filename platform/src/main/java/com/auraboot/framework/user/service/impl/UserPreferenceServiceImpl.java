package com.auraboot.framework.user.service.impl;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.user.dao.entity.UserPreference;
import com.auraboot.framework.user.mapper.UserPreferenceMapper;
import com.auraboot.framework.user.service.UserPreferenceService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserPreferenceServiceImpl implements UserPreferenceService {

    private final UserPreferenceMapper preferenceMapper;

    @Override
    public JsonNode getPreference(Long userId, String key) {
        UserPreference pref = preferenceMapper.selectOne(
                new QueryWrapper<UserPreference>()
                        .eq("user_id", userId)
                        .eq("preference_key", key)
        );
        return pref != null ? pref.getPreferenceValue() : null;
    }

    @Override
    @Transactional
    public void setPreference(Long userId, String key, JsonNode value) {
        UserPreference existing = preferenceMapper.selectOne(
                new QueryWrapper<UserPreference>()
                        .eq("user_id", userId)
                        .eq("preference_key", key)
        );

        if (existing != null) {
            existing.setPreferenceValue(value);
            preferenceMapper.updateById(existing);
        } else {
            UserPreference pref = new UserPreference();
            pref.setPid(UlidGenerator.generate());
            pref.setUserId(userId);
            pref.setPreferenceKey(key);
            pref.setPreferenceValue(value);
            preferenceMapper.insert(pref);
        }
    }

    @Override
    public Map<String, JsonNode> getPreferencesByPrefix(Long userId, String prefix) {
        List<UserPreference> prefs = preferenceMapper.selectList(
                new QueryWrapper<UserPreference>()
                        .eq("user_id", userId)
                        .likeRight("preference_key", prefix)
        );
        Map<String, JsonNode> result = new HashMap<>();
        for (UserPreference pref : prefs) {
            result.put(pref.getPreferenceKey(), pref.getPreferenceValue());
        }
        return result;
    }
}
