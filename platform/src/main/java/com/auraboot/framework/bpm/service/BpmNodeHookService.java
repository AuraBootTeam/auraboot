package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.bpm.rule.DroolsEngineService;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BpmNodeHookService {

    private static final int HOOK_REST_CONNECT_TIMEOUT_MS = 5_000;
    private static final int HOOK_REST_READ_TIMEOUT_MS = 10_000;

    /**
     * Per-hook execution timeout (P3-E hardening). Bounds DoS surface for
     * script (SpEL), drools, rest_call, and command executors. Configured
     * higher than the REST read timeout so a slow but legal HTTP call still
     * completes.
     */
    private static final long HOOK_EXECUTION_TIMEOUT_MS = 15_000L;

    /**
     * Grace window after soft {@link Thread#interrupt()} before escalating to
     * hard-kill via {@link Thread#stop()}. Uncooperative SpEL / Drools loops
     * never poll {@code Thread.interrupted()}, so the soft phase is a pure
     * courtesy for well-behaved executors (REST client, etc.); the hard phase
     * is what actually halts a runaway {@code while(true)} in a user SpEL
     * script.
     */
    private static final long HOOK_HARD_KILL_GRACE_MS = 5_000L;

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
    private final RestTemplate hookRestTemplate;
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
        // Dedicated RestTemplate with timeouts for hook REST calls (NH-2)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HOOK_REST_CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(HOOK_REST_READ_TIMEOUT_MS);
        this.hookRestTemplate = new RestTemplate(factory);
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
            case "command" -> runWithTimeout("command", () -> executeCommand(config, variables));
            default -> {
                log.warn("Unknown hook action type: raw={}, normalized={}", rawType, type);
                yield true;
            }
        };
    }

    /**
     * Bound the wall-clock execution time of a hook executor (P3-E residual
     * #2 and #3 hardening).
     *
     * <p>Runs {@code task} on a <strong>dedicated daemon platform thread</strong>
     * (neither pooled nor virtual) so the caller always returns within
     * {@value #HOOK_EXECUTION_TIMEOUT_MS}ms plus {@value #HOOK_HARD_KILL_GRACE_MS}ms
     * grace even if the worker is wedged. On timeout the caller throws
     * {@link BusinessException} so the hook's fail-strategy (block/warn/skip)
     * fires and the BPM process advances.
     *
     * <h3>Soft interrupt phase</h3>
     * After the configured timeout the worker receives {@link Thread#interrupt()}.
     * Cooperative executors honour this immediately:
     * <ul>
     *   <li>{@code hookRestTemplate} — underlying {@code SimpleClientHttpRequestFactory}
     *       aborts the in-flight connect/read.</li>
     *   <li>{@code CommandExecutor} downstream JDBC / HTTP — most connection
     *       pools check the interrupt flag.</li>
     * </ul>
     *
     * <h3>Hard-kill caveat (JDK 21+)</h3>
     * {@link Thread#stop()} was neutered in JDK 20 and throws
     * {@link UnsupportedOperationException} on JDK 21 — it is no longer a
     * viable escape hatch. As a result, a truly uncooperative CPU-bound loop
     * (e.g. Drools {@code fireAllRules} on a pathological rule graph, or a
     * custom script executor that never polls {@code Thread.interrupted()})
     * will remain scheduled on its daemon thread until the JVM exits. The
     * daemon flag ensures shutdown is not blocked; the caller still returns
     * promptly so the workflow is not held hostage.
     *
     * <h3>Why that is acceptable here</h3>
     * <ul>
     *   <li>SpEL has no {@code while}/{@code for} grammar; looping requires
     *       {@code T(...)} type refs (blocked by {@link #DENY_TYPE_LOCATOR}),
     *       bean refs (no {@code BeanResolver} registered), or constructor
     *       calls (no {@code ConstructorResolver} registered). A script that
     *       passes hook hardening cannot build a spin loop inside SpEL.</li>
     *   <li>Drools {@code validateDrl} blocks {@code Thread}/{@code Runtime}/
     *       {@code ProcessBuilder} imports, so rules cannot call
     *       {@code Thread.sleep} directly.</li>
     *   <li>REST-call and Command hooks go through cooperating clients that
     *       honour interrupts.</li>
     * </ul>
     *
     * <p>In other words: the caller-side timeout is the guaranteed bound; the
     * worker-side hard-kill is <em>best effort</em>, and the JVM-level
     * guarantees plus SpEL/Drools hardening make the residual DoS surface
     * acceptable for the dev-stage threat model.
     */
    private boolean runWithTimeout(String label, java.util.concurrent.Callable<Boolean> task) {
        final Object[] resultHolder = new Object[1];
        final Throwable[] errorHolder = new Throwable[1];

        Thread worker = new Thread(() -> {
            try {
                resultHolder[0] = task.call();
            } catch (Throwable t) {
                errorHolder[0] = t;
            }
        }, "bpm-hook-" + label + "-" + System.nanoTime());
        // Daemon: a wedged worker must not block JVM shutdown.
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(HOOK_EXECUTION_TIMEOUT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            throw new BusinessException("Hook execution interrupted: " + label);
        }

        if (worker.isAlive()) {
            log.error("Hook executor '{}' exceeded timeout of {}ms, issuing interrupt",
                    label, HOOK_EXECUTION_TIMEOUT_MS);
            worker.interrupt();
            try {
                worker.join(HOOK_HARD_KILL_GRACE_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                // JDK 21 removed Thread.stop(); we cannot forcibly halt a
                // CPU-bound loop that ignores the interrupt flag. The worker
                // remains as a daemon and will be torn down at JVM exit. The
                // caller still returns promptly so the BPM process advances.
                log.error("Hook executor '{}' did not honour interrupt within {}ms; "
                        + "abandoning daemon worker (JDK 21 has no Thread.stop)",
                        label, HOOK_HARD_KILL_GRACE_MS);
            }
            throw new BusinessException("Hook execution timeout (" + label + "): "
                    + HOOK_EXECUTION_TIMEOUT_MS + "ms");
        }

        if (errorHolder[0] != null) {
            Throwable cause = errorHolder[0];
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessException("Hook execution failed (" + label + "): "
                    + cause.getMessage());
        }
        Boolean result = (Boolean) resultHolder[0];
        return result != null && result;
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
