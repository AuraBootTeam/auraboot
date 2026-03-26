package com.auraboot.module.mrp.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class LotForLotStrategy implements LotSizingStrategy {

    @Override
    public String name() {
        return "lfl";
    }

    @Override
    public BigDecimal calculate(BigDecimal netDemand, BigDecimal moq, Map<String, Object> params) {
        if (moq != null && moq.compareTo(BigDecimal.ZERO) > 0) {
            return netDemand.max(moq);
        }
        return netDemand;
    }
}
