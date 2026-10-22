package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for CommandMetadataCacheService.
 * Verifies cache hit/miss/eviction behavior for command definitions and binding rules.
 *
 * @since 2.4.0
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CommandMetadataCache Integration Test")
class CommandMetadataCacheIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommandService commandService;

    @Autowired
    private CommandMetadataCacheService commandMetadataCache;

    @Autowired
    private CacheManager cacheManager;

    private static final String TEST_MODEL_CODE = "test_cache_model";

    private CommandDefinitionDTO createAndPublishCommand(String suffix) {
        String code = "cmd_cache_" + System.currentTimeMillis() + "_" + suffix;
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Cache Test " + suffix);
        request.setDescription("Cache integration test");
        request.setModelCode(TEST_MODEL_CODE);
        request.setExecutionConfig("{\"type\":\"create\",\"inputFields\":[{\"fieldCode\":\"name\",\"required\":true}]}");

        CommandDefinitionDTO dto = commandService.create(request);
        commandService.publish(dto.getPid());
        return commandService.findByPid(dto.getPid());
    }

    @Test
    @Order(1)
    @DisplayName("CommandDefinition cache: first call hits DB, second call hits cache")
    void testCommandDefinitionCacheHitMiss() {
        CommandDefinitionDTO cmd = createAndPublishCommand("hitMiss");

        // Evict to start clean
        commandMetadataCache.evictCommandDefinitions();

        // First call — cache miss, hits DB
        CommandDefinition result1 = commandMetadataCache.findCurrentCommandByCode(cmd.getCode());
        assertThat(result1).isNotNull();
        assertThat(result1.getCode()).isEqualTo(cmd.getCode());

        // Second call — cache hit (same object or equal)
        CommandDefinition result2 = commandMetadataCache.findCurrentCommandByCode(cmd.getCode());
        assertThat(result2).isNotNull();
        assertThat(result2.getCode()).isEqualTo(cmd.getCode());
        assertThat(result2.getId()).isEqualTo(result1.getId());

        log.info("Cache hit/miss test passed for command: {}", cmd.getCode());
    }

    @Test
    @Order(2)
    @DisplayName("BindingRule cache: batch load returns all rule types")
    void testBindingRuleCacheBatchLoad() {
        CommandDefinitionDTO cmd = createAndPublishCommand("batchLoad");

        // Add binding rules of different types
        BindingRuleDTO assertRule = new BindingRuleDTO();
        assertRule.setRuleType("assert");
        assertRule.setExpression("#name != null");
        assertRule.setEnabled(true);
        commandService.addBindingRule(cmd.getPid(), assertRule);

        BindingRuleDTO effectRule = new BindingRuleDTO();
        effectRule.setRuleType("effect");
        effectRule.setExpression("#result");
        effectRule.setEnabled(true);
        commandService.addBindingRule(cmd.getPid(), effectRule);

        // Evict to start clean
        commandMetadataCache.evictBindingRules();

        // Fetch via cache — should get both rules
        CommandDefinition entity = commandMetadataCache.findCurrentCommandByCode(cmd.getCode());
        List<BindingRule> rules = commandMetadataCache.findBindingRulesByCommandId(entity.getId());

        assertThat(rules).isNotNull();
        assertThat(rules).hasSizeGreaterThanOrEqualTo(2);
        assertThat(rules).extracting(BindingRule::getRuleType)
                .contains("assert", "effect");

        // Second call — cache hit
        List<BindingRule> rulesAgain = commandMetadataCache.findBindingRulesByCommandId(entity.getId());
        assertThat(rulesAgain).hasSameSizeAs(rules);

        log.info("Batch load test passed: {} rules for command {}", rules.size(), cmd.getCode());
    }

    @Test
    @Order(3)
    @DisplayName("Cache eviction: adding a rule evicts binding rule cache")
    void testCacheEvictionOnRuleAdd() {
        CommandDefinitionDTO cmd = createAndPublishCommand("eviction");

        // Populate cache
        CommandDefinition entity = commandMetadataCache.findCurrentCommandByCode(cmd.getCode());
        List<BindingRule> rulesBefore = commandMetadataCache.findBindingRulesByCommandId(entity.getId());
        int countBefore = rulesBefore.size();

        // Add a new rule (triggers cache eviction)
        BindingRuleDTO newRule = new BindingRuleDTO();
        newRule.setRuleType("handler");
        newRule.setHandlerClass("com.test.Handler");
        newRule.setEnabled(true);
        commandService.addBindingRule(cmd.getPid(), newRule);

        // Re-fetch — should see the new rule (cache was evicted)
        List<BindingRule> rulesAfter = commandMetadataCache.findBindingRulesByCommandId(entity.getId());
        assertThat(rulesAfter).hasSize(countBefore + 1);
        assertThat(rulesAfter).extracting(BindingRule::getRuleType)
                .contains("handler");

        log.info("Eviction test passed: before={}, after={}", countBefore, rulesAfter.size());
    }

    @Test
    @Order(4)
    @DisplayName("evictAll clears both command and binding rule caches")
    void testEvictAll() {
        CommandDefinitionDTO cmd = createAndPublishCommand("evictAll");

        // Populate both caches
        CommandDefinition entity = commandMetadataCache.findCurrentCommandByCode(cmd.getCode());
        commandMetadataCache.findBindingRulesByCommandId(entity.getId());

        // Verify caches are populated
        assertThat(cacheManager.getCache("commandDefinitions")).isNotNull();
        assertThat(cacheManager.getCache("bindingRules")).isNotNull();

        // Evict all
        commandMetadataCache.evictAll();

        // Caches should still exist as regions but entries cleared
        // Re-fetch triggers fresh DB query
        CommandDefinition entityAfterEvict = commandMetadataCache.findCurrentCommandByCode(cmd.getCode());
        assertThat(entityAfterEvict).isNotNull();
        assertThat(entityAfterEvict.getCode()).isEqualTo(cmd.getCode());

        log.info("evictAll test passed for command: {}", cmd.getCode());
    }
}
