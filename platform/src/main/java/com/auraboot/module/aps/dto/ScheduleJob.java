package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScheduleJob {
    private Long id;
    private String code;
    private String productName;
    private Long productId;
    private String operationName;
    private Long requiredResourceId;
    private String requiredResourceType;
    private int processingTimeMin;
    private LocalDateTime dueDate;
    private int priority;
    private LocalDateTime arrivalTime;
}
