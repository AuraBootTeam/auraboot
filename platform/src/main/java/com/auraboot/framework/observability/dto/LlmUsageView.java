package com.auraboot.framework.observability.dto;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/** Public correlation projection, independent of the model usage persistence entity. */
@Data
public class LlmUsageView {
    private Long id;

    private Long tenantId;
    private String runId;
    private String traceId;
    private String spanId;
    private String provider;
    private String requestModel;
    private String responseModel;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cacheReadTokens;
    private Integer cacheWriteTokens;
    private Integer reasoningTokens;
    private BigDecimal amount;
    private String currency;
    private String pricingVersion;

    private Instant createdAt;
}
