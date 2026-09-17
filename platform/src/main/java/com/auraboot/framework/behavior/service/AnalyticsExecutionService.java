package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.mapper.AnalyticsExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AnalyticsExecutionService {
    private final AnalyticsExecutionMapper mapper;

    public AnalyticsExecution query(Long tenantId, BehaviorQueryWindow window, Instant cutoff) {
        var counts = mapper.count(tenantId, window.from(), window.to(), cutoff);
        long completed = counts.getSucceeded() + counts.getFailed() + counts.getCancelled();
        return new AnalyticsExecution("agent-execution-cohort-v1", "agent_run", "first_start_in_window",
                window.from(), window.to(), cutoff, "UTC", counts,
                ratio(counts.getSucceeded(), counts.getStarted()), ratio(counts.getSucceeded(), completed),
                counts.getStarted() == 0 ? "no_sample" : "observed",
                "observed_events_not_delivery_guaranteed");
    }

    private static BigDecimal ratio(long count, long total) {
        return total == 0 ? null : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }
}
