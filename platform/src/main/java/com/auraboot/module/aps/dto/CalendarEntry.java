package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CalendarEntry {
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
    private boolean holiday;
}
