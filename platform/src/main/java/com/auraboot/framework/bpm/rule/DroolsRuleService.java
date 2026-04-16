package com.auraboot.framework.bpm.rule;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.mapper.BpmRuleMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.plugin.dto.imports.BpmRuleDefinitionDTO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroolsRuleService {

    private final BpmRuleMapper ruleMapper;
    private final DroolsEngineService engineService;

    @Transactional
    public BpmRule createRule(BpmRule rule) {
        rule.setPid(UlidGenerator.generate());
        rule.setTenantId(MetaContext.getCurrentTenantId());
        rule.setVersion(1);
        rule.setEnabled(true);
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        ruleMapper.insert(rule);
        log.info("Rule created: pid={}, code={}", rule.getPid(), rule.getRuleCode());
        return rule;
    }

    public List<BpmRule> listRules() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ruleMapper.findAll(tenantId);
    }

    public List<BpmRule> listRulesByType(String ruleType) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ruleMapper.findByType(tenantId, ruleType);
    }

    public BpmRule getRule(String pid) {
        BpmRule rule = ruleMapper.findByPid(pid);
        if (rule == null) throw new IllegalArgumentException("Rule not found: " + pid);
        return rule;
    }

    @Transactional
    public BpmRule updateRule(String pid, BpmRule update) {
        BpmRule existing = getRule(pid);
        if (update.getRuleName() != null) existing.setRuleName(update.getRuleName());
        if (update.getRuleContent() != null) existing.setRuleContent(update.getRuleContent());
        if (update.getRuleType() != null) existing.setRuleType(update.getRuleType());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getInputSchema() != null) existing.setInputSchema(update.getInputSchema());
        if (update.getOutputSchema() != null) existing.setOutputSchema(update.getOutputSchema());
        if (update.getEnabled() != null) existing.setEnabled(update.getEnabled());
        existing.setVersion(existing.getVersion() + 1);
        existing.setUpdatedAt(Instant.now());
        ruleMapper.updateById(existing);

        // Invalidate cache
        engineService.invalidateCache(pid);

        log.info("Rule updated: pid={}, newVersion={}", pid, existing.getVersion());
        return existing;
    }

    @Transactional
    public void deleteRule(String pid) {
        BpmRule rule = getRule(pid);
        ruleMapper.deleteById(rule.getId());
        engineService.invalidateCache(pid);
        log.info("Rule deleted: pid={}", pid);
    }

    public Map<String, Object> evaluateRule(String pid, Map<String, Object> facts) {
        BpmRule rule = getRule(pid);
        return engineService.evaluateRule(rule, facts);
    }

    public List<String> validateDrl(String drlContent) {
        return engineService.validateDrl(drlContent);
    }

    /**
     * Upsert a rule from a plugin import DTO. Uses {@code (tenantId, ruleCode)}
     * as the unique key. Existing rows are updated in place (preserving pid and
     * bumping {@code version}); missing rows are inserted.
     *
     * <p>Intended to be called by the plugin import pipeline — not by general
     * CRUD callers.
     */
    @Transactional
    public BpmRule importRule(BpmRuleDefinitionDTO dto) {
        Long tenantId = MetaContext.getCurrentTenantId();
        BpmRule existing = ruleMapper.selectOne(new QueryWrapper<BpmRule>()
                .eq("tenant_id", tenantId)
                .eq("rule_code", dto.getRuleCode()));

        Instant now = Instant.now();
        if (existing == null) {
            BpmRule rule = BpmRule.builder()
                    .pid(UlidGenerator.generate())
                    .tenantId(tenantId)
                    .ruleCode(dto.getRuleCode())
                    .ruleName(dto.getRuleName())
                    .ruleType(dto.getRuleType())
                    .ruleContent(dto.getRuleContent())
                    .inputSchema(dto.getInputSchema())
                    .outputSchema(dto.getOutputSchema())
                    .description(dto.getDescription())
                    .enabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled())
                    .version(1)
                    .createdAt(now)
                    .updatedAt(now)
                    .deletedFlag(false)
                    .build();
            ruleMapper.insert(rule);
            log.info("Imported rule (created): code={}, pid={}", rule.getRuleCode(), rule.getPid());
            return rule;
        }

        existing.setRuleName(dto.getRuleName());
        existing.setRuleType(dto.getRuleType());
        existing.setRuleContent(dto.getRuleContent());
        existing.setInputSchema(dto.getInputSchema());
        existing.setOutputSchema(dto.getOutputSchema());
        existing.setDescription(dto.getDescription());
        if (dto.getEnabled() != null) {
            existing.setEnabled(dto.getEnabled());
        }
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        existing.setUpdatedAt(now);
        ruleMapper.updateById(existing);
        engineService.invalidateCache(existing.getPid());
        log.info("Imported rule (updated): code={}, pid={}, newVersion={}",
                existing.getRuleCode(), existing.getPid(), existing.getVersion());
        return existing;
    }
}
