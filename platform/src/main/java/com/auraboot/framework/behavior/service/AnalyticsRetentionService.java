package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.AnalyticsRetention;
import com.auraboot.framework.behavior.dto.BehaviorQueryWindow;
import com.auraboot.framework.behavior.mapper.AnalyticsRetentionMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnalyticsRetentionService {
    private final AnalyticsRetentionMapper mapper;

    public AnalyticsRetention query(Long tenantId, String unit, BehaviorQueryWindow window, Instant cutoff) {
        if (unit == null || !Set.of("user", "artifact").contains(unit)) {
            throw new BusinessException(ResponseCode.BadParam, "Retention unit must be user or artifact");
        }
        var points = mapper.query(tenantId, unit, window.from(), window.to(), cutoff).stream().map(point -> {
            boolean calculable = point.isMature() && point.getCohortSize() > 0;
            return new AnalyticsRetention.Point(point.getCohortDay(), point.getDayOffset(), point.getCohortSize(),
                    calculable ? point.getReturningCount() : null,
                    calculable ? BigDecimal.valueOf(point.getReturningCount()).divide(BigDecimal.valueOf(point.getCohortSize()), 6, RoundingMode.HALF_UP) : null,
                    !point.isMature() ? "immature" : point.getCohortSize() == 0 ? "no_sample" : "observed");
        }).toList();
        return new AnalyticsRetention("analytics-first-use-retention-v1", unit, "first_successful_dashboard_use",
                "UTC", window.from(), window.to(), cutoff, "observed_events_not_delivery_guaranteed", points);
    }
}
