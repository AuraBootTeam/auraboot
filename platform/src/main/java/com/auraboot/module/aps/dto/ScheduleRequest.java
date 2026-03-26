package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScheduleRequest {
    private List<ScheduleJob> jobs;
    private List<ResourceInfo> resources;
    private Map<Long, List<CalendarEntry>> resourceCalendars;
    private Map<String, Integer> setupTimes;
    private LocalDateTime scheduleStart;
}
