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

@Service
@RequiredArgsConstructor
public class TenantPreferenceServiceImpl implements TenantPreferenceService {

    private final TenantPreferenceMapper preferenceMapper;

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
}
