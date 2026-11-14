package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.service.PluginRecordStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Record-level enable/disable transitions (see
 * {@link PluginRecordStateService}). Behavior preserved from the controller
 * code it replaces: not-found and cross-tenant both surface as
 * "Plugin not found"; the tenant lifecycle guard runs before a disable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginRecordStateServiceImpl implements PluginRecordStateService {

    private final PluginRecordMapper pluginRecordMapper;
    private final AuraPluginManager auraPluginManager;

    @Override
    public PluginRecord setEnabled(String pid, boolean enabled) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pid);
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin not found");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId != null && !tenantId.equals(plugin.getTenantId())) {
            throw new IllegalArgumentException("Plugin not found");
        }

        if (enabled) {
            pluginRecordMapper.markAsEnabled(pid);
        } else {
            auraPluginManager.requireTenantLifecycleChange(plugin);
            pluginRecordMapper.markAsDisabled(pid);
        }
        return pluginRecordMapper.findByPid(pid);
    }
}
