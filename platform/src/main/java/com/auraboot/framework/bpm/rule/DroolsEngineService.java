package com.auraboot.framework.bpm.rule;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.mapper.BpmRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.runtime.StatelessKieSession;
import org.kie.internal.utils.KieHelper;
import org.kie.api.io.ResourceType;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroolsEngineService {

    /** Dangerous imports that could enable RCE or info leakage */
    private static final List<Pattern> BLOCKED_DRL_PATTERNS = List.of(
            Pattern.compile("import\\s+java\\.lang\\.Runtime"),
            Pattern.compile("import\\s+java\\.lang\\.ProcessBuilder"),
            Pattern.compile("import\\s+java\\.lang\\.System"),
            Pattern.compile("import\\s+java\\.lang\\.reflect\\."),
            Pattern.compile("import\\s+java\\.io\\."),
            Pattern.compile("import\\s+java\\.nio\\."),
            Pattern.compile("import\\s+java\\.net\\."),
            Pattern.compile("import\\s+javax\\.script\\."),
            Pattern.compile("import\\s+javax\\.management\\."),
            Pattern.compile("Runtime\\.getRuntime\\("),
            Pattern.compile("ProcessBuilder"),
            Pattern.compile("System\\.exit"),
            Pattern.compile("System\\.getenv"),
            Pattern.compile("System\\.getProperty"),
            Pattern.compile("Class\\.forName"),
            Pattern.compile("\\.getClass\\(\\)\\."),
            Pattern.compile("java\\.lang\\.Thread")
    );
    private static final int MAX_CACHE_SIZE = 200;

    private final BpmRuleMapper ruleMapper;
    private final ConcurrentHashMap<String, KieBase> kieBaseCache = new ConcurrentHashMap<>();

    /**
     * Evaluate a rule by its code with the given facts.
     */
    public Map<String, Object> evaluate(String ruleCode, Map<String, Object> facts) {
        Long tenantId = MetaContext.getCurrentTenantId();
        BpmRule rule = ruleMapper.findByCode(tenantId, ruleCode);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleCode);
        }
        return evaluateRule(rule, facts);
    }

    /**
     * Evaluate a rule directly.
     */
    public Map<String, Object> evaluateRule(BpmRule rule, Map<String, Object> facts) {
        KieBase kieBase = getOrBuildKieBase(rule);
        StatelessKieSession session = kieBase.newStatelessKieSession();

        // Create result holder
        Map<String, Object> result = new HashMap<>(facts);
        result.put("_ruleResult", new HashMap<String, Object>());

        // Execute with facts
        List<Object> factsList = new ArrayList<>();
        factsList.add(result);
        // Add individual facts
        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            factsList.add(entry.getValue());
        }

        session.execute(factsList);

        @SuppressWarnings("unchecked")
        Map<String, Object> ruleResult = (Map<String, Object>) result.getOrDefault("_ruleResult", Map.of());
        log.debug("Rule evaluated: code={}, result={}", rule.getRuleCode(), ruleResult);
        return ruleResult;
    }

    private KieBase getOrBuildKieBase(BpmRule rule) {
        String cacheKey = rule.getPid() + ":" + rule.getVersion();
        return kieBaseCache.computeIfAbsent(cacheKey, k -> {
            // Enforce cache size limit (DR-2 fix)
            if (kieBaseCache.size() >= MAX_CACHE_SIZE) {
                // Evict oldest entries (simple FIFO via iterator)
                var it = kieBaseCache.entrySet().iterator();
                int toRemove = kieBaseCache.size() / 4; // remove 25%
                for (int i = 0; i < toRemove && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
                log.info("KieBase cache evicted {} entries (size was {})", toRemove, MAX_CACHE_SIZE);
            }

            // Security check before compiling DRL (DR-1 + DR-3 fix)
            validateDrlSecurity(rule.getRuleContent());

            log.info("Building KieBase for rule: code={}, version={}", rule.getRuleCode(), rule.getVersion());
            KieHelper helper = new KieHelper();
            helper.addContent(rule.getRuleContent(), ResourceType.DRL);
            return helper.build();
        });
    }

    /**
     * Validate DRL content does not contain dangerous Java calls that could lead to RCE.
     */
    private void validateDrlSecurity(String drlContent) {
        for (Pattern pattern : BLOCKED_DRL_PATTERNS) {
            if (pattern.matcher(drlContent).find()) {
                throw new IllegalArgumentException(
                        "DRL rule contains blocked pattern (potential security risk): " + pattern.pattern());
            }
        }
    }

    /**
     * Invalidate cache for a rule.
     */
    public void invalidateCache(String rulePid) {
        kieBaseCache.keySet().removeIf(key -> key.startsWith(rulePid + ":"));
        log.info("Cache invalidated for rule: pid={}", rulePid);
    }

    /**
     * Validate DRL syntax.
     */
    public List<String> validateDrl(String drlContent) {
        List<String> errors = new ArrayList<>();

        // Security validation first (DR-3)
        for (Pattern pattern : BLOCKED_DRL_PATTERNS) {
            if (pattern.matcher(drlContent).find()) {
                errors.add("Security: blocked pattern detected — " + pattern.pattern());
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }

        // Syntax validation
        try {
            KieHelper helper = new KieHelper();
            helper.addContent(drlContent, ResourceType.DRL);
            var results = helper.verify();
            if (results.hasMessages(Message.Level.ERROR)) {
                for (var msg : results.getMessages(Message.Level.ERROR)) {
                    errors.add("Line " + msg.getLine() + ": " + msg.getText());
                }
            }
        } catch (Exception e) {
            errors.add("Compilation error: " + e.getMessage());
        }
        return errors;
    }
}
