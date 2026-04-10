package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.IdempotencyService;
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
@Order(300)
@RequiredArgsConstructor
public class IdempotencyPhase implements CommandPhase {

    private final IdempotencyService idempotencyService;

    @Override
    public String name() {
        return "idempotency_check";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        return !StringUtils.hasText(ctx.getRequest().getClientRequestId());
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        Map<String, Object> cachedResult = idempotencyService.checkIdempotency(
                ctx.getRequest().getClientRequestId(), ctx.getTenantId());
        if (cachedResult != null) {
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
