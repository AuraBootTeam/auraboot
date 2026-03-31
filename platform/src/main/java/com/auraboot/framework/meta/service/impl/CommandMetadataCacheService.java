package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cached access to command metadata (CommandDefinition + BindingRule).
 * These are static config that only change during plugin import/admin operations.
 *
 * @since 2.4.0
 */
@Service
@RequiredArgsConstructor
public class CommandMetadataCacheService {

    public static final String CACHE_COMMAND_DEFS = "commandDefinitions";
    public static final String CACHE_BINDING_RULES = "bindingRules";

    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;

    @Cacheable(value = CACHE_COMMAND_DEFS,
            key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #code",
            unless = "#result == null")
    public CommandDefinition findCurrentCommandByCode(String code) {
        return commandDefinitionMapper.findCurrentByCode(code);
    }

    @Cacheable(value = CACHE_BINDING_RULES,
            key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #commandId")
    public List<BindingRule> findBindingRulesByCommandId(Long commandId) {
        return bindingRuleMapper.findByCommandId(commandId);
    }

    @Caching(evict = {
            @CacheEvict(value = CACHE_COMMAND_DEFS, allEntries = true),
            @CacheEvict(value = CACHE_BINDING_RULES, allEntries = true)
    })
    public void evictAll() {
        // Evicts both caches — called during plugin import
    }

    @CacheEvict(value = CACHE_BINDING_RULES, allEntries = true)
    public void evictBindingRules() {
        // Evicts binding rules cache
    }

    @CacheEvict(value = CACHE_COMMAND_DEFS, allEntries = true)
    public void evictCommandDefinitions() {
        // Evicts command definitions cache
    }
}
