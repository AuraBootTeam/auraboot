package com.auraboot.module.mrp.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class FixedOrderQtyStrategy implements LotSizingStrategy {

    @Override
    public String name() {
        return "foq";
    }

    @Override
    public BigDecimal calculate(BigDecimal netDemand, BigDecimal moq, Map<String, Object> params) {
        BigDecimal fixedQty = (BigDecimal) params.get("fixedOrderQty");
        if (fixedQty == null || fixedQty.compareTo(BigDecimal.ZERO) <= 0) {
            return netDemand;
        }

        // ceil(netDemand / fixedQty) * fixedQty
        BigDecimal lots = netDemand.divide(fixedQty, 0, RoundingMode.CEILING);
        BigDecimal result = lots.multiply(fixedQty);

        if (moq != null && moq.compareTo(BigDecimal.ZERO) > 0 && result.compareTo(moq) < 0) {
            return moq;
        }
        return result;
    }
}
