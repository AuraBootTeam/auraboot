package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.constant.CommandResultCachePolicy;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandIdempotencyIntent;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Check idempotency key — if a cached result exists, short-circuit the pipeline.
 */
@Slf4j
@Component
@Order(525)
@RequiredArgsConstructor
public class IdempotencyPhase implements CommandPhase {

    private final IdempotencyService idempotencyService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.plugin.pf4j.CommandRecordActionScopeAccessorFactory recordScopeFactory;

    record CachedRead(String model, java.util.List<String> path) { }

    /** Trusted scalar result references only; no client expressions, wildcards or alternate policy engine. */
    static java.util.List<CachedRead> cachedReads(CommandPipelineContext ctx) {
        if (ctx == null || ctx.getExecConfig() == null) return java.util.List.of();
        Object rawParams = ctx.getExecConfig().get("handlerParams");
        if (!(rawParams instanceof Map<?,?> params)) return java.util.List.of();
        boolean strict = "handler-with-result-reads".equals(params.get("invocationAuthorization"));
        if (!params.containsKey("cachedResultReadReferences")) {
            if (strict) throw cachedReadDenied();
            return java.util.List.of();
        }
        if (!strict || !(params.get("cachedResultReadReferences") instanceof java.util.List<?> refs)
                || refs.isEmpty() || refs.size() > 32) throw cachedReadDenied();
        var result = new java.util.ArrayList<CachedRead>();
        var seen = new java.util.HashSet<CachedRead>();
        for (Object value : refs) {
            if (!(value instanceof Map<?,?> ref) || !ref.keySet().equals(java.util.Set.of("model","path"))
                    || !(ref.get("model") instanceof String model) || !model.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                    || !(ref.get("path") instanceof String path) || path.length()>255
                    || !path.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){0,7}")) throw cachedReadDenied();
            var entry = new CachedRead(model,java.util.List.of(path.split("\\.")));
            if (!seen.add(entry)) throw cachedReadDenied();
            result.add(entry);
        }
        return java.util.List.copyOf(result);
    }

    private void requireCachedReads(CommandPipelineContext ctx, Map<String,Object> cached, java.util.List<CachedRead> reads) {
        if (reads.isEmpty()) return;
        if (recordScopeFactory == null) throw cachedReadDenied();
        try (var scope = recordScopeFactory.forInvocation(ctx)) {
            if (scope == null || scope.accessor() == null) throw cachedReadDenied();
            for (var read : reads) {
                Object current = cached;
                for (String key : read.path()) {
                    if (!(current instanceof Map<?,?> values)) throw cachedReadDenied();
                    current = values.get(key);
                }
                if (!(current instanceof String pid) || !pid.matches("[0-9A-HJKMNP-TV-Z]{26}")
                        || !scope.accessor().permits(read.model(),pid,"read")) throw cachedReadDenied();
            }
        }
    }
    private static org.springframework.security.access.AccessDeniedException cachedReadDenied() {
        return new org.springframework.security.access.AccessDeniedException("Cached command records are not currently readable");
    }

    @Override
    public String name() {
        return "idempotency_check";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        return !CommandResultCachePolicy.enabled(ctx.getExecConfig())
                || !StringUtils.hasText(ctx.getRequest().getClientRequestId());
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        if (shouldSkip(ctx)) return;
        var reads = cachedReads(ctx);
        if (!reads.isEmpty() && recordScopeFactory == null) throw cachedReadDenied();
        Map<String, Object> intent = CommandIdempotencyIntent.snapshot(ctx);
        ctx.setIdempotencyIntent(intent);
        Map<String, Object> cachedResult = idempotencyService.claimScopedIdempotency(
                ctx.getRequest().getClientRequestId(),
                ctx.getCommandCode(),
                intent,
                ctx.getTenantId());
        if (cachedResult != null) {
            requireCachedReads(ctx,cachedResult,reads);
            log.info("Idempotent replay for command {} with clientRequestId {}",
                    ctx.getCommandCode(), ctx.getRequest().getClientRequestId());
            ctx.setShortCircuitResult(CommandExecuteResult.builder()
                    .commandCode(ctx.getCommandCode())
                    .phaseReached("completed")
                    .data(cachedResult)
                    .executionTimeMs(System.currentTimeMillis() - ctx.getStartTime())
                    .idempotentReplay(true)
                    .build());
        }
    }
}
