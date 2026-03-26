package com.auraboot.module.mrp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlternativeMaterialDto {

    private Long id;
    private Long bomLineId;
    private Long materialId;
    private String materialName;
    private int priority;
    private BigDecimal conversionFactor;
}
