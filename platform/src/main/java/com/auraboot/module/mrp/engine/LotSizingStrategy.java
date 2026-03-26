package com.auraboot.module.mrp.engine;

import java.math.BigDecimal;
import java.util.Map;

public interface LotSizingStrategy {

    String name();

    BigDecimal calculate(BigDecimal netDemand, BigDecimal moq, Map<String, Object> params);
}
