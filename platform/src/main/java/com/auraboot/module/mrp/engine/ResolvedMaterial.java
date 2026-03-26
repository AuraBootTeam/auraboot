package com.auraboot.module.mrp.engine;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class ResolvedMaterial {

    private Long materialId;
    private boolean alternative;
    private BigDecimal conversionFactor;
}
