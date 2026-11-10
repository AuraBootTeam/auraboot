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

    private static final int MAX_COLLECTION_ITEMS=1000, MAX_CACHED_RECORDS=4096;
    record CachedRead(String model, java.util.List<String> path, int maxItems, java.util.List<String> countPath) { }

    /** Trusted scalar or bounded PID-collection references; no expressions, wildcards or alternate policy engine. */
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
        var seen = new java.util.HashSet<String>();
        for (Object value : refs) {
            if (!(value instanceof Map<?,?> ref)
                    || !(ref.keySet().equals(java.util.Set.of("model","path"))
                        || ref.keySet().equals(java.util.Set.of("model","path","many","maxItems","countPath")))
                    || !(ref.get("model") instanceof String model) || !model.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                    || !(ref.get("path") instanceof String path) || !validPath(path)) throw cachedReadDenied();
            int maximum=0;var count=java.util.List.<String>of();
            if(ref.containsKey("many")) {
                maximum=positiveInteger(ref.get("maxItems"));
                if(!Boolean.TRUE.equals(ref.get("many")) || maximum>MAX_COLLECTION_ITEMS
                        || !(ref.get("countPath") instanceof String countPath) || !validPath(countPath))throw cachedReadDenied();
                count=java.util.List.of(countPath.split("\\."));
            }
            var entry = new CachedRead(model,java.util.List.of(path.split("\\.")),maximum,count);
            if (!seen.add(model+"|"+path)) throw cachedReadDenied();
            result.add(entry);
        }
        return java.util.List.copyOf(result);
    }

    private void requireCachedReads(CommandPipelineContext ctx, Map<String,Object> cached, java.util.List<CachedRead> reads) {
        if (reads.isEmpty()) return;
        if (recordScopeFactory == null) throw cachedReadDenied();
        try (var scope = recordScopeFactory.forInvocation(ctx)) {
            if (scope == null || scope.accessor() == null) throw cachedReadDenied();
            var records=cachedRecords(cached,reads);
            for (var record : records) {
                if (!scope.accessor().permits(record.getKey(),record.getValue(),"read")) throw cachedReadDenied();
            }
        }
    }
    private static java.util.List<java.util.Map.Entry<String,String>> cachedRecords(Map<String,Object> cached,java.util.List<CachedRead> reads) {
        // Validate the complete manifest and its budgets before making any permission decision.
        var records=new java.util.ArrayList<java.util.Map.Entry<String,String>>();
        for(var read:reads) {
            Object value=at(cached,read.path());
            java.util.List<?> pids;
            if(read.maxItems()==0) {
                if(!(value instanceof String))throw cachedReadDenied();
                pids=java.util.List.of(value);
            } else {
                if(!(value instanceof java.util.List<?> values) || values.isEmpty() || values.size()>read.maxItems()
                        || positiveInteger(at(cached,read.countPath()))!=values.size())throw cachedReadDenied();
                pids=values;
            }
            var seen=new java.util.HashSet<String>();
            for(Object item:pids) {
                if(!(item instanceof String pid) || !pid.matches("[0-9A-HJKMNP-TV-Z]{26}") || !seen.add(pid)
                        || records.size()>=MAX_CACHED_RECORDS)throw cachedReadDenied();
                records.add(java.util.Map.entry(read.model(),pid));
            }
        }
        return java.util.List.copyOf(records);
    }
    private static boolean validPath(String path) {
        return path.length()<=255 && path.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){0,7}");
    }
    private static Object at(Map<String,Object> root,java.util.List<String> path) {
        Object current=root;
        for(String key:path) {
            if(!(current instanceof Map<?,?> map))throw cachedReadDenied();
            current=map.get(key);
        }
        return current;
    }
    private static int positiveInteger(Object value) {
        if(!(value instanceof Number number))throw cachedReadDenied();
        try {
            int result=new java.math.BigDecimal(number.toString()).intValueExact();
            if(result<1)throw cachedReadDenied();return result;
        } catch(ArithmeticException | NumberFormatException malformed) {throw cachedReadDenied();}
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
