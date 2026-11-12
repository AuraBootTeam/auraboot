package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.tenant.dao.entity.TenantPreference;
import com.auraboot.framework.tenant.dao.mapper.TenantPreferenceMapper;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
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
public class TenantPreferenceServiceImpl implements TenantPreferenceService {

    private final TenantPreferenceMapper preferenceMapper;
    private final com.auraboot.framework.permission.service.RoleAuthorizationWriteLock authorizationWrites;

    @Override
    public JsonNode getPreference(Long tenantId, String key) {
        TenantPreference pref = preferenceMapper.selectOne(
                new QueryWrapper<TenantPreference>()
                        .eq("tenant_id", tenantId)
                        .eq("preference_key", key)
        );
        return pref != null ? pref.getPreferenceValue() : null;
    }

    @Override
    @Transactional
    public void setPreference(Long tenantId, String key, JsonNode value) {
        if (key != null && key.startsWith(com.auraboot.framework.application.tenant.ApplicationCatalogPolicy.KEY_PREFIX)) {
            var scope = com.auraboot.framework.application.tenant.ApplicationCatalogPolicy.parse(value);
            if (!key.equals(com.auraboot.framework.application.tenant.ApplicationCatalogPolicy.key(scope.applicationCode()))) {
                throw new IllegalStateException("Application catalog identity mismatch");
            }
            authorizationWrites.mutateForTenant(tenantId);
        }
        TenantPreference existing = preferenceMapper.selectOne(
                new QueryWrapper<TenantPreference>()
                        .eq("tenant_id", tenantId)
                        .eq("preference_key", key)
        );

        if (existing != null) {
            existing.setPreferenceValue(value);
            preferenceMapper.updateById(existing);
            return;
        }

        TenantPreference pref = new TenantPreference();
        pref.setPid(UlidGenerator.generate());
        pref.setTenantId(tenantId);
        pref.setPreferenceKey(key);
        pref.setPreferenceValue(value);
        preferenceMapper.insert(pref);
    }

    @Override
    public Map<String, JsonNode> getPreferencesByPrefix(Long tenantId, String prefix) {
        List<TenantPreference> prefs = preferenceMapper.selectList(
                new QueryWrapper<TenantPreference>()
                        .eq("tenant_id", tenantId)
                        .likeRight("preference_key", prefix)
        );
        Map<String, JsonNode> result = new HashMap<>();
        for (TenantPreference pref : prefs) {
            result.put(pref.getPreferenceKey(), pref.getPreferenceValue());
        }
        return result;
    }
}
