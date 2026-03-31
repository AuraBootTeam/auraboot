package com.auraboot.framework.application.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.StateGraphService;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Preloads hot metadata caches at startup to eliminate cold-start latency.
 * Iterates over all active tenants and warms model/command/state-graph caches.
 *
 * @since 2.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmupRunner {

    private final TenantMapper tenantMapper;
    private final MetaModelMapper metaModelMapper;
    private final CommandDefinitionMapper commandDefinitionMapper;
    private final MetaModelService metaModelService;
    private final StateGraphService stateGraphService;
    private final CommandMetadataCacheService commandMetadataCache;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupCaches() {
        long start = System.currentTimeMillis();
        int modelCount = 0;
        int commandCount = 0;

        List<Tenant> tenants = tenantMapper.findByStatus("active");
        log.info("Cache warmup: starting for {} active tenant(s)", tenants.size());

        for (Tenant tenant : tenants) {
            try {
                MetaContext.setContext(tenant.getId(), null, null, "cache-warmup");

                List<Model> models = metaModelMapper.findCurrentByTenant();
                for (Model model : models) {
                    try {
                        metaModelService.getModelDefinition(model.getCode());
                        stateGraphService.listByModelCode(model.getCode());
                        modelCount++;

                        List<CommandDefinition> commands = commandDefinitionMapper.findByModelCode(model.getCode());
                        for (CommandDefinition cmd : commands) {
                            commandMetadataCache.findCurrentCommandByCode(cmd.getCode());
                            commandMetadataCache.findBindingRulesByCommandId(cmd.getId());
                            commandCount++;
                        }
                    } catch (Exception e) {
                        log.debug("Cache warmup: skipped model {} — {}", model.getCode(), e.getMessage());
                    }
                }
            } finally {
                MetaContext.clear();
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Cache warmup: completed in {}ms — {} models, {} commands across {} tenant(s)",
                elapsed, modelCount, commandCount, tenants.size());
    }
}
