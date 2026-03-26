package com.auraboot.module.aps.dto;

import lombok.Data;

import java.util.List;

@Data
public class ApsScheduleRequest {
    private String strategy;
    private List<Long> workOrderIds;
    private Long lineId;
}
