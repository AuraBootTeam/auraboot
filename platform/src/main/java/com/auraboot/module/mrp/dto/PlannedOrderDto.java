package com.auraboot.module.mrp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlannedOrderDto {

    private Long mrpRunId;
    private Long materialId;
    private String materialName;
    private String orderType;
    private BigDecimal orderQty;
    private LocalDate orderDate;
    private LocalDate needDate;
    private int leadTimeDays;
    private String lotSizingPolicy;
    private String sourceDemandJson;
}
