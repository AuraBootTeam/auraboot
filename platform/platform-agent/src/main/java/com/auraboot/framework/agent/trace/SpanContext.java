package com.auraboot.framework.agent.trace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SpanContext {
    private String spanId;
    private String traceId;
    private String parentSpanId;
    private String type;
    private String name;
    private Instant startTime;
    private int sequenceOrder;
}
