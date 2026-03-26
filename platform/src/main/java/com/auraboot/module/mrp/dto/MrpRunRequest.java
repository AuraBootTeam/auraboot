package com.auraboot.module.mrp.dto;

import lombok.Data;

import java.util.List;

@Data
public class MrpRunRequest {

    private String scope;
    private List<Long> materialIds;
    private List<Long> orderIds;
    private int horizonDays = 90;
    private List<DemandEntry> demands;
}
