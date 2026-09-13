package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.mapper.AnalyticsFunnelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class AnalyticsFunnelService {
    private final AnalyticsFunnelMapper mapper;

    public AnalyticsFunnel query(Long tenantId, BehaviorQueryWindow window, Instant cutoff) {
        var counts = mapper.count(tenantId, window.from(), window.to(), cutoff);
        long[] values = {counts.getRequested(), counts.getSucceeded(), counts.getViewed(), counts.getSaved(), counts.getUsed()};
        String[] codes = {"requested", "query_succeeded", "result_viewed", "artifact_saved", "artifact_used"};
        var stages = new ArrayList<AnalyticsFunnel.Stage>();
        for (int i = 0; i < values.length; i++) {
            long previous = i == 0 ? values[0] : values[i - 1];
            stages.add(new AnalyticsFunnel.Stage(codes[i], values[i], values[0], previous,
                    ratio(values[i], values[0]), ratio(values[i], previous), previous == 0 ? "no_sample" : "observed"));
        }
        return new AnalyticsFunnel("analysis-task-funnel-v2", "analysis_task", "initiator_first_entry_in_window",
                window.from(), window.to(), cutoff, "UTC", stages,
                new AnalyticsFunnel.Quality(counts.getMissingCorrelationEvents(), counts.getWithoutWindowEntryTasks(),
                        counts.getUnmatchedStageEvents(), counts.getSampledEvents(), "observed_events_not_delivery_guaranteed"));
    }

    private static BigDecimal ratio(long count, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
}
