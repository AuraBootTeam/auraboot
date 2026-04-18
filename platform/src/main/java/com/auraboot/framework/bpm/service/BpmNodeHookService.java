package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class BpmNodeHookService {

    private static final int HOOK_REST_CONNECT_TIMEOUT_MS = 5_000;
    private static final int HOOK_REST_READ_TIMEOUT_MS = 10_000;

    /**
     * Per-hook execution timeout (P3-E hardening). Bounds DoS surface for
     * script (SpEL), drools, and rest_call executors. Configured higher than
     * the REST read timeout so a slow but legal HTTP call still completes.
     */
    private static final long HOOK_EXECUTION_TIMEOUT_MS = 15_000L;

    /**
     * hookType vocabulary alias map (GAP-255).
     *
     * <p>Frontend BPMN designer emits UI vocab ({@code pre_execute / post_execute /
     * pre_complete / post_complete}); the internal execution pipeline uses backend
     * vocab ({@code pre_check / post_action}). Both are normalized to backend vocab
     * before being persisted and before being used for queries.
     */
    private static final Map<String, String> HOOK_TYPE_ALIASES = Map.of(
            "pre_execute", "pre_check",
            "pre_check", "pre_check",
            "pre_complete", "pre_check",
            "post_execute", "post_action",
            "post_complete", "post_action",
            "post_action", "post_action"
    );

    /**
     * actionType vocabulary alias map (GAP-256).
     *
     * <p>Frontend emits {@code http_callback / script / command}; backend pipeline
     * dispatches on {@code rest_call / script / drools_rule / command}.
     */
    private static final Map<String, String> ACTION_TYPE_ALIASES = Map.of(
            "http_callback", "rest_call",
            "rest_call", "rest_call",
            "script", "script",
            "command", "command",
            "drools_rule", "drools_rule"
    );

    private final BpmNodeHookMapper hookMapper;
    private final DroolsEngineService droolsEngineService;
    private final CommandExecutor commandExecutor;
    private final RestTemplate restTemplate;
    /**
     * SpEL parser configured without compiler optimisation and without auto-grow,
     * to keep script evaluation deterministic and bounded (GAP-257).
     */
    private final ExpressionParser spelParser = new SpelExpressionParser(
            new SpelParserConfiguration(false, false));

    /**
     * TypeLocator that always refuses {@code T(...)} references. Any attempt to
     * resolve a {@link Class} (e.g. {@code T(java.lang.Runtime)}) throws
     * {@link SpelEvaluationException}, blocking the most common SpEL RCE vectors
     * in hook scripts (GAP-257).
     */
    private static final TypeLocator DENY_TYPE_LOCATOR = typeName -> {
        throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND,
                "Type references are not allowed in hook scripts: " + typeName);
    };

    public BpmNodeHookService(BpmNodeHookMapper hookMapper,
                              DroolsEngineService droolsEngineService,
                              CommandExecutor commandExecutor,
                              RestTemplate restTemplate) {
        this.hookMapper = hookMapper;
        this.droolsEngineService = droolsEngineService;
        this.commandExecutor = commandExecutor;
        this.restTemplate = restTemplate;
        // Hook REST calls are now executed via Java HttpClient with IP pinning
        // (see PINNED_HOOK_CLIENT + executeRestCall). The previous hookRestTemplate
        // could not pin DNS and was replaced for P3-E #1 (DNS rebinding TOCTOU).
    }

    /**
     * Normalize a hookType value (UI vocab or internal vocab) into internal vocab.
     * Unknown values pass through lower-cased for caller-level diagnostics.
     */
    static String normalizeHookType(String input) {
        if (input == null) {
            return null;
        }
        String lower = input.toLowerCase().trim();
        return HOOK_TYPE_ALIASES.getOrDefault(lower, lower);
    }

    /**
     * Normalize an actionType (hookConfig.type) value (UI vocab or internal vocab)
     * into internal dispatch vocab.
     */
    static String normalizeActionType(String input) {
        if (input == null) {
            return null;
        }
        String lower = input.toLowerCase().trim();
        return ACTION_TYPE_ALIASES.getOrDefault(lower, lower);
    }

    public List<BpmNodeHook> getHooks(String processKey, String nodeId, String hookType) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return hookMapper.findHooks(tenantId, processKey, nodeId, normalizeHookType(hookType));
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
        String rawType = (String) config.get("type");
        String type = normalizeActionType(rawType);

        return switch (type == null ? "" : type) {
            case "rest_call" -> runWithTimeout("rest_call", () -> executeRestCall(config, variables));
            case "script" -> runWithTimeout("script", () -> executeScript(config, variables));
            case "drools_rule" -> runWithTimeout("drools_rule", () -> executeDroolsRule(config, variables));
            case "command" -> executeCommand(config, variables);
            default -> {
                log.warn("Unknown hook action type: raw={}, normalized={}", rawType, type);
                yield true;
            }
        };
    }

    /**
     * Bound the wall-clock execution time of a hook executor (P3-E hardening).
     *
     * <p>Runs {@code task} on a virtual thread and waits at most
     * {@link #HOOK_EXECUTION_TIMEOUT_MS}. On timeout we return {@code false}
     * (treated by callers as a hook failure) and surface a {@link BusinessException}
     * up to the caller's fail-strategy handling so {@code block} can short-circuit
     * the workflow. The runaway task continues on the virtual thread but is
     * detached from the caller; SpEL/Drools have no kill switch so we cannot
     * forcibly interrupt without {@code Thread.stop()}.
     *
     * <p>Command hooks are intentionally NOT wrapped: they reuse the platform's
     * {@code CommandExecutor} pipeline which already runs inside the caller's
     * transaction and has its own pipeline-level timeouts.
     */
    private boolean runWithTimeout(String label, java.util.concurrent.Callable<Boolean> task) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, runnable -> Thread.startVirtualThread(runnable));

        try {
            Boolean result = future.get(HOOK_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result != null && result;
        } catch (TimeoutException te) {
            future.cancel(true);
            log.error("Hook executor '{}' exceeded timeout of {}ms", label, HOOK_EXECUTION_TIMEOUT_MS);
            throw new BusinessException("Hook execution timeout (" + label + "): "
                    + HOOK_EXECUTION_TIMEOUT_MS + "ms");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Hook execution interrupted: " + label);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessException("Hook execution failed (" + label + "): " + cause.getMessage());
        }
    }

    /**
     * Dedicated Java HttpClient for rest_call hooks (P3-E DNS-rebinding hardening,
     * 2026-04-18). Replaces the previous {@code hookRestTemplate} because JDK
     * {@link HttpClient} is what {@link PinnedHttpRequests} targets for pinning.
     */
    private static final HttpClient PINNED_HOOK_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(HOOK_REST_CONNECT_TIMEOUT_MS))
            .build();

    private boolean executeRestCall(Map<String, Object> config, Map<String, Object> variables) {
        String url = (String) config.get("url");
        String method = (String) config.getOrDefault("method", "post");

        // Validate URL + capture resolved IP to pin at connect time (P3-E #1).
        // If validate() throws, SSRF was detected and we never touch the socket.
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(url);
        if (target == null) {
            log.warn("REST hook URL could not be resolved: {}", url);
            return false;
        }

        try {
            HttpRequest.Builder builder = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofMillis(HOOK_REST_READ_TIMEOUT_MS));

            if ("get".equalsIgnoreCase(method)) {
                builder.GET();
            } else {
                String body = com.auraboot.framework.common.util.JsonUtil.toJson(variables);
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            }

            HttpResponse<String> response = PINNED_HOOK_CLIENT.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            log.warn("REST hook returned non-2xx: url={}, status={}", url, status);
            return false;
        } catch (Exception e) {
            log.error("REST hook call failed: url={}", url, e);
            return false;
        }
    }

    /**
     * Execute a SpEL "script" hook against the given process variables map.
     *
     * <p>Until GAP-257 this used {@code SimpleEvaluationContext}, which is
     * read-only — any {@code #vars['x'] = 'y'} or {@code #setVar(...)} from a
     * hook script silently had no effect, so downstream nodes (gateway
     * conditions, userTask assignees) could not observe hook-produced values.</p>
     *
     * <p>The implementation now uses {@link StandardEvaluationContext}, but with
     * a hardened surface to keep hook scripts far away from generic SpEL RCE
     * vectors:</p>
     * <ul>
     *   <li>{@code T(...)} type references are rejected by
     *       {@link #DENY_TYPE_LOCATOR} — {@code T(java.lang.Runtime).exec(...)}
     *       cannot resolve a class and throws {@link SpelEvaluationException}.</li>
     *   <li>No {@code ConstructorResolver} is registered, so {@code new Foo()}
     *       constructor calls cannot be resolved.</li>
     *   <li>No {@code BeanResolver} is registered, so {@code @someBean.xxx}
     *       cannot reach Spring beans.</li>
     *   <li>{@link SpelParserConfiguration} is built with {@code autoGrow=false}
     *       and {@code compilerMode=false} to keep behaviour predictable.</li>
     * </ul>
     *
     * <p>Scripts can now write variables via either route — both land in the
     * same mutable {@code variables} map that the caller handed in (which in
     * runtime is {@code executionContext.getRequest()}), so SmartEngine sees
     * the updates on the next node:</p>
     * <ul>
     *   <li>{@code #setVar(#vars, 'approverRole', 'manager')} — registered SpEL
     *       function, thin facade over {@link Map#put}.</li>
     *   <li>{@code #vars['approverRole'] = 'manager'} — SpEL indexer mutation
     *       on the {@code #vars} variable, which is the same map instance.</li>
     * </ul>
     */
    private boolean executeScript(Map<String, Object> config, Map<String, Object> variables) {
        String script = (String) config.get("script");
        if (!StringUtils.hasText(script)) {
            log.warn("SCRIPT hook missing script expression");
            return true;
        }

        try {
            StandardEvaluationContext evalContext = new StandardEvaluationContext();
            // Allow map-key property access (#vars.someKey) without exposing a
            // rootObject — rootObject would let scripts navigate arbitrary
            // getters/setters on whatever we set.
            evalContext.addPropertyAccessor(new MapAccessor());
            // Harden: block T(...) type references and explicit bean resolution.
            evalContext.setTypeLocator(DENY_TYPE_LOCATOR);
            evalContext.setBeanResolver(null);

            // Make the full variable map available as #vars (same instance as
            // the caller's map — mutations via #vars['x'] = 'y' propagate back
            // to the SmartEngine ExecutionContext request map).
            evalContext.setVariable("vars", variables);
            if (variables != null) {
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    evalContext.setVariable(entry.getKey(), entry.getValue());
                }
            }

            // Register #setVar(#vars, 'name', value) as an explicit writer —
            // thin facade over Map.put, so scripts can call
            //   #setVar(#vars, 'approverRole', 'manager')
            // and the write lands on the caller's live map (which is the same
            // instance as SmartEngine's ExecutionContext request map).
            evalContext.registerFunction("setVar",
                    BpmNodeHookService.class.getDeclaredMethod(
                            "setVar", Map.class, String.class, Object.class));

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
        } catch (NoSuchMethodException e) {
            // Programming error — the registered function must exist on this
            // class. Surface as an IllegalStateException so the listener's
            // fail-strategy path reports it instead of silently swallowing.
            throw new IllegalStateException(
                    "BpmNodeHookService SpEL function registration failed", e);
        } catch (EvaluationException e) {
            // Security guard tripped (e.g. T(...) rejected, constructor call
            // rejected) or script is invalid. Propagate so failStrategy
            // handling in executePreChecks / executePostActions can act on it
            // (block → "Pre-check error: …", warn/skip → swallow).
            log.error("Script hook execution failed (SpEL): script={}, reason={}",
                    script, e.getMessage());
            throw e;
        }
    }

    /**
     * SpEL-callable writer used by {@code #setVar(#vars, 'key', value)} hook
     * scripts. Writes directly into the process variables map so the change is
     * visible to downstream SmartEngine nodes (GAP-257).
     */
    public static Object setVar(Map<String, Object> vars, String key, Object value) {
        if (vars == null || key == null) {
            return value;
        }
        vars.put(key, value);
        return value;
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

    /**
     * Execute an AuraBoot Command as a node hook (GAP-256).
     *
     * <p>Config shape:
     * <pre>
     *   { "type": "command",
     *     "commandCode": "wd:create_leave_balance",
     *     "operationType": "create",       // optional (create/update/delete)
     *     "targetRecordId": "...",         // optional
     *     "payload": { ... } }             // optional; defaults to hook variables
     * </pre>
     *
     * <p>Parity with {@code commandServiceTaskDelegate}: treats a non-exceptional
     * return as success; exceptions bubble up so the caller's fail-strategy logic
     * can decide BLOCK / WARN / SKIP.
     */
    @SuppressWarnings("unchecked")
    private boolean executeCommand(Map<String, Object> config, Map<String, Object> variables) {
        String commandCode = (String) config.get("commandCode");
        if (!StringUtils.hasText(commandCode)) {
            log.warn("COMMAND hook missing commandCode in config");
            return false;
        }

        String operationType = (String) config.get("operationType");
        String targetRecordId = (String) config.get("targetRecordId");
        Object payloadRaw = config.get("payload");
        Map<String, Object> payload = payloadRaw instanceof Map
                ? (Map<String, Object>) payloadRaw
                : variables;

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType(operationType);
        request.setTargetRecordId(targetRecordId);
        request.setPayload(payload);

        CommandExecuteResult result = commandExecutor.execute(commandCode, request);
        log.info("COMMAND hook executed: commandCode={}, phaseReached={}",
                commandCode, result != null ? result.getPhaseReached() : null);
        return true;
    }

    @Transactional
    public BpmNodeHook createHook(BpmNodeHook hook) {
        hook.setPid(UlidGenerator.generate());
        hook.setTenantId(MetaContext.getCurrentTenantId());
        // GAP-255: normalize UI vocab → internal vocab at write time.
        if (hook.getHookType() != null) {
            hook.setHookType(normalizeHookType(hook.getHookType()));
        }
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
        if (update.getHookType() != null) {
            existing.setHookType(normalizeHookType(update.getHookType()));
        }
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
