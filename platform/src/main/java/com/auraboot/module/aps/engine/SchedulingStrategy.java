package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.ScheduleRequest;
import com.auraboot.module.aps.dto.ScheduleResult;

public interface SchedulingStrategy {
    String name();
    String description();
    ScheduleResult schedule(ScheduleRequest request);
}
