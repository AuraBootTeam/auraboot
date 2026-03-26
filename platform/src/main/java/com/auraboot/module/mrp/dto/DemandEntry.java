package com.auraboot.module.mrp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DemandEntry {

    private Long materialId;
    private String materialName;
    private BigDecimal quantity;
    private LocalDate needDate;
}
