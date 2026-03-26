package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @AllArgsConstructor @NoArgsConstructor
public class GanttMilestone {
    private LocalDateTime time;
    private String label;
}
