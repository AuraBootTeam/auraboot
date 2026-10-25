package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.CommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for command metadata cache invalidation after command mutations.
 */
@DisplayName("P5 command metadata mutations evict command cache projections")
class CommandMetadataCacheEvictionIT extends BaseIntegrationTest {

    @Autowired
    private CommandService commandService;

    @Autowired
    private CommandMetadataCacheService commandMetadataCache;

    @BeforeEach
    void clearCommandMetadataCacheBeforeTest() {
        commandMetadataCache.evictAll();
    }

    @AfterEach
    void clearCommandMetadataCacheAfterTest() {
        commandMetadataCache.evictAll();
    }

    @Test
    @DisplayName("command update evicts cached command definition")
    void commandUpdateEvictsCachedCommandDefinition() {
        String code = uniqueCode("cmd_update");
        CommandDefinitionDTO command = commandService.create(commandRequest(code, "Original Command"));

        CommandDefinition warmed = commandMetadataCache.findCurrentCommandByCode(code);
        assertThat(warmed.getDisplayName()).isEqualTo("Original Command");

        commandService.update(command.getPid(), commandRequest(code, "Updated Command"));

        CommandDefinition refreshed = commandMetadataCache.findCurrentCommandByCode(code);
        assertThat(refreshed.getDisplayName()).isEqualTo("Updated Command");
    }

    @Test
    @DisplayName("command publish evicts cached command definition")
    void commandPublishEvictsCachedCommandDefinition() {
        String code = uniqueCode("cmd_publish");
        CommandDefinitionDTO command = commandService.create(commandRequest(code, "Publish Command"));

        CommandDefinition warmed = commandMetadataCache.findCurrentCommandByCode(code);
        assertThat(warmed.getStatus()).isEqualTo("draft");

        commandService.publish(command.getPid());

        CommandDefinition refreshed = commandMetadataCache.findCurrentCommandByCode(code);
        assertThat(refreshed.getStatus()).isEqualTo("published");
    }

    @Test
    @DisplayName("binding rule removal evicts cached binding rules")
    void bindingRuleRemovalEvictsCachedBindingRules() {
        String code = uniqueCode("cmd_rule_remove");
        CommandDefinitionDTO command = commandService.create(commandRequest(code, "Rule Removal Command"));
        BindingRuleDTO keepRule = commandService.addBindingRule(command.getPid(), bindingRule("assert", "#name != null", 0));
        BindingRuleDTO removeRule = commandService.addBindingRule(command.getPid(), bindingRule("effect", "#result", 1));

        CommandDefinition entity = commandMetadataCache.findCurrentCommandByCode(code);
        List<BindingRule> warmedRules = commandMetadataCache.findBindingRulesByCommandId(entity.getId());
        assertThat(warmedRules)
                .extracting(BindingRule::getPid)
                .contains(keepRule.getPid(), removeRule.getPid());

        commandService.removeBindingRule(removeRule.getPid());

        List<BindingRule> refreshedRules = commandMetadataCache.findBindingRulesByCommandId(entity.getId());
        assertThat(refreshedRules)
                .extracting(BindingRule::getPid)
                .contains(keepRule.getPid())
                .doesNotContain(removeRule.getPid());
    }

    private CommandDefinitionCreateRequest commandRequest(String code, String displayName) {
        CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName(displayName);
        request.setDescription(displayName + " description");
        request.setModelCode("p5_command_cache_model");
        request.setInputSchema("{}");
        request.setTargetModels("[]");
        request.setExecutionConfig("{\"type\":\"update\"}");
        request.setCmdRiskLevel("L1");
        return request;
    }

    private BindingRuleDTO bindingRule(String ruleType, String expression, int sequence) {
        BindingRuleDTO rule = new BindingRuleDTO();
        rule.setRuleType(ruleType);
        rule.setExpression(expression);
        rule.setSequence(sequence);
        rule.setEnabled(true);
        return rule;
    }

    private String uniqueCode(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + System.nanoTime();
    }
}
