package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.GanttData;
import com.auraboot.module.aps.dto.ScheduleRequest;
import com.auraboot.module.aps.dto.ScheduleResult;
import com.auraboot.module.aps.visualization.GanttDataBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SchedulingEngine {

    private final Map<String, SchedulingStrategy> strategies;
    private final GanttDataBuilder ganttBuilder;

    public ScheduleResult schedule(ScheduleRequest request, String strategyName) {
        SchedulingStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown strategy: " + strategyName + ". Available: " + strategies.keySet());
        }
        return strategy.schedule(request);
    }

    public GanttData scheduleWithGantt(ScheduleRequest request, String strategyName) {
        ScheduleResult result = schedule(request, strategyName);
        return ganttBuilder.build(result);
    }

    public List<String> getAvailableStrategies() {
        return strategies.values().stream()
            .map(s -> s.name() + ": " + s.description())
            .collect(Collectors.toList());
    }
}
