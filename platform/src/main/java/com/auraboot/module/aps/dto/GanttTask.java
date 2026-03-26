package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GanttTask {
    private String id;
    private Long resourceId;
    private String jobCode;
    private String productName;
    private String operationName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int setupTimeMin;
    private int processingTimeMin;
    private String color;
}
