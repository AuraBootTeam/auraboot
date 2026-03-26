package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BpmNodeHookService {

    private static final int HOOK_REST_CONNECT_TIMEOUT_MS = 5_000;
    private static final int HOOK_REST_READ_TIMEOUT_MS = 10_000;

    private final BpmNodeHookMapper hookMapper;
    private final DroolsEngineService droolsEngineService;
    private final RestTemplate restTemplate;
    private final RestTemplate hookRestTemplate;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    public BpmNodeHookService(BpmNodeHookMapper hookMapper, DroolsEngineService droolsEngineService,
                              RestTemplate restTemplate) {
        this.hookMapper = hookMapper;
        this.droolsEngineService = droolsEngineService;
        this.restTemplate = restTemplate;
        // Create a dedicated RestTemplate with timeouts for hook REST calls (NH-2)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HOOK_REST_CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(HOOK_REST_READ_TIMEOUT_MS);
        this.hookRestTemplate = new RestTemplate(factory);
    }

    public List<BpmNodeHook> getHooks(String processKey, String nodeId, String hookType) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return hookMapper.findHooks(tenantId, processKey, nodeId, hookType);
    }

    public List<BpmNodeHook> getHooksByProcessKey(String processKey) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return hookMapper.findByProcessKey(tenantId, processKey);
    }

    /**
     * Execute pre-check hooks. Returns result indicating if all checks pass.
     */
    public HookExecutionResult executePreChecks(String processKey, String nodeId, Map<String, Object> variables) {
        List<BpmNodeHook> hooks = getHooks(processKey, nodeId, "pre_check");
        if (hooks.isEmpty()) {
            return new HookExecutionResult(true, null);
        }

        for (BpmNodeHook hook : hooks) {
            try {
                boolean passed = executeHook(hook, variables);
                if (!passed) {
                    if ("block".equals(hook.getFailStrategy())) {
                        return new HookExecutionResult(false, "Pre-check failed: " + hook.getPid());
                    } else if ("warn".equals(hook.getFailStrategy())) {
                        log.warn("Pre-check warning: hook={}, processKey={}, nodeId={}", hook.getPid(), processKey, nodeId);
                    }
                    // SKIP: continue
                }
            } catch (Exception e) {
                log.error("Pre-check execution error: hook={}", hook.getPid(), e);
                if ("block".equals(hook.getFailStrategy())) {
                    return new HookExecutionResult(false, "Pre-check error: " + e.getMessage());
                }
            }
        }
        return new HookExecutionResult(true, null);
    }

    /**
     * Execute post-action hooks.
     */
    public void executePostActions(String processKey, String nodeId, Map<String, Object> variables) {
        List<BpmNodeHook> hooks = getHooks(processKey, nodeId, "post_action");
        for (BpmNodeHook hook : hooks) {
            if (Boolean.TRUE.equals(hook.getAsync())) {
                // Capture MetaContext before spawning virtual thread (NH-1 fix)
                Long tenantId = MetaContext.getCurrentTenantId();
                Long userId = MetaContext.getCurrentUserId();
                String userPid = MetaContext.getCurrentUserPid();
                String username = MetaContext.getCurrentUsername();
                Thread.startVirtualThread(() -> {
                    MetaContext.setContext(tenantId, userId, userPid, username);
                    try {
                        executeHook(hook, variables);
                    } catch (Exception e) {
                        log.error("Async post-action failed: hook={}", hook.getPid(), e);
                    } finally {
                        MetaContext.clear();
                    }
                });
            } else {
                try {
                    executeHook(hook, variables);
                } catch (Exception e) {
                    log.error("Post-action failed: hook={}", hook.getPid(), e);
                    if ("block".equals(hook.getFailStrategy())) {
                        throw new BusinessException("Post-action failed: " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private boolean executeHook(BpmNodeHook hook, Map<String, Object> variables) {
        Map<String, Object> config = hook.getHookConfig();
        String type = (String) config.get("type");

        return switch (type) {
            case "rest_call" -> executeRestCall(config, variables);
            case "script" -> executeScript(config, variables);
            case "drools_rule" -> executeDroolsRule(config, variables);
            default -> {
                log.warn("Unknown hook type: {}", type);
                yield true;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private boolean executeRestCall(Map<String, Object> config, Map<String, Object> variables) {
        String url = (String) config.get("url");
        String method = (String) config.getOrDefault("method", "post");

        // Validate URL to prevent SSRF attacks
        SsrfValidator.validateUrl(url);

        try {
            if ("get".equalsIgnoreCase(method)) {
                hookRestTemplate.getForEntity(url, Map.class);
            } else {
                hookRestTemplate.postForEntity(url, variables, Map.class);
            }
            return true;
        } catch (Exception e) {
            log.error("REST hook call failed: url={}", url, e);
            return false;
        }
    }

    private boolean executeScript(Map<String, Object> config, Map<String, Object> variables) {
        String script = (String) config.get("script");
        if (!StringUtils.hasText(script)) {
            log.warn("SCRIPT hook missing script expression");
            return true;
        }

        try {
            SimpleEvaluationContext evalContext = SimpleEvaluationContext
                    .forPropertyAccessors(new MapAccessor())
                    .withRootObject(variables)
                    .build();
            evalContext.setVariable("vars", variables);
            if (variables != null) {
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    evalContext.setVariable(entry.getKey(), entry.getValue());
                }
            }

            Object result = spelParser.parseExpression(script).getValue(evalContext);
            if (result == null) {
                return false;
            }
            if (result instanceof Boolean bool) {
                return bool;
            }
            if (result instanceof Number number) {
                return number.doubleValue() != 0D;
            }
            return true;
        } catch (Exception e) {
            log.error("Script hook execution failed: script={}", script, e);
            return false;
        }
    }

    private boolean executeDroolsRule(Map<String, Object> config, Map<String, Object> variables) {
        String ruleCode = (String) config.get("ruleCode");
        if (ruleCode == null) {
            log.warn("DROOLS_RULE hook missing ruleCode in config");
            return true;
        }
        try {
            Map<String, Object> result = droolsEngineService.evaluate(ruleCode, variables);
            Object passed = result.get("passed");
            if (passed instanceof Boolean b) {
                return b;
            }
            // If rule doesn't set "passed", treat as passed
            return true;
        } catch (Exception e) {
            log.error("Drools rule execution failed: ruleCode={}", ruleCode, e);
            return false;
        }
    }

    @Transactional
    public BpmNodeHook createHook(BpmNodeHook hook) {
        hook.setPid(UlidGenerator.generate());
        hook.setTenantId(MetaContext.getCurrentTenantId());
        hook.setCreatedAt(Instant.now());
        hook.setUpdatedAt(Instant.now());
        hookMapper.insert(hook);
        return hook;
    }

    @Transactional
    public BpmNodeHook updateHook(String pid, BpmNodeHook update) {
        BpmNodeHook existing = hookMapper.findByPid(pid);
        if (existing == null) {
            throw new IllegalArgumentException("Hook not found: " + pid);
        }
        if (update.getHookConfig() != null) existing.setHookConfig(update.getHookConfig());
        if (update.getFailStrategy() != null) existing.setFailStrategy(update.getFailStrategy());
        if (update.getAsync() != null) existing.setAsync(update.getAsync());
        if (update.getEnabled() != null) existing.setEnabled(update.getEnabled());
        if (update.getExecutionOrder() != null) existing.setExecutionOrder(update.getExecutionOrder());
        existing.setUpdatedAt(Instant.now());
        hookMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void deleteHook(String pid) {
        BpmNodeHook hook = hookMapper.findByPid(pid);
        if (hook != null) {
            hookMapper.deleteById(hook.getId());
        }
    }

    public record HookExecutionResult(boolean passed, String message) {}
}
