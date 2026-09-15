package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.BehaviorDailyPoint;
import com.auraboot.framework.behavior.dto.BehaviorEventCount;
import com.auraboot.framework.behavior.dto.BehaviorOverview;
import com.auraboot.framework.behavior.dto.BehaviorQueryWindow;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Executes tenant-scoped behavior rollups with one validated window. */
@Service
@RequiredArgsConstructor
public class BehaviorAnalyticsService {
    private final BehaviorEventMapper mapper;

    public BehaviorOverview overview(Long tenantId, BehaviorQueryWindow window) {
        return mapper.overview(tenantId, window.from(), window.to());
    }

    public List<BehaviorEventCount> topEvents(Long tenantId, BehaviorQueryWindow window) {
        return mapper.topEvents(tenantId, window.from(), window.to());
    }

    public List<BehaviorDailyPoint> daily(Long tenantId, BehaviorQueryWindow window) {
        return mapper.dailyTrend(tenantId, window.from(), window.to());
    }
}
