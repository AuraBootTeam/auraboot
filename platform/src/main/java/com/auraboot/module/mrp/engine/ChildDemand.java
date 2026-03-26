package com.auraboot.module.mrp.engine;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class ChildDemand {

    private Long materialId;
    private String materialName;
    private BigDecimal quantity;
    private Long bomLineId;
}
