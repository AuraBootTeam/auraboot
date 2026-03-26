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
public class BomLineDto {

    private Long id;
    private Long parentMaterialId;
    private Long childMaterialId;
    private String childMaterialName;
    private BigDecimal quantityPer;
    private BigDecimal lossRate;
    private String refDesignator;
}
