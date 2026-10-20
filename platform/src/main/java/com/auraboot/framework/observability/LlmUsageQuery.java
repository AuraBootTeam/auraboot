package com.auraboot.framework.observability;

import com.auraboot.framework.observability.dto.LlmUsageView;
import java.util.List;

/** Consumer-owned query boundary for tenant-scoped usage correlation. */
@FunctionalInterface
public interface LlmUsageQuery {
    List<LlmUsageView> findByTrace(Long tenantId, String traceId);
}
