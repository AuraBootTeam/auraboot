package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScheduleConflict {
    private Long jobId;
    private String reason;
    private LocalDateTime requestedBy;
    private LocalDateTime achievableBy;
}
