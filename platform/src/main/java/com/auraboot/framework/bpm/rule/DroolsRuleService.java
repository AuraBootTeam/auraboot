package com.auraboot.framework.bpm.rule;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.mapper.BpmRuleMapper;
import com.auraboot.framework.common.util.UlidGenerator;
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
}
