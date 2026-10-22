package com.auraboot.framework.agent.trace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@Builder
public class TraceContext {
    private String traceId;
    private Long tenantId;
    private String sessionId;
    private Instant startTime;
    @Builder.Default
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    public int nextSequence() {
        return sequenceCounter.getAndIncrement();
    }
}
