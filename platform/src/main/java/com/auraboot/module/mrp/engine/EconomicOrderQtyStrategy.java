package com.auraboot.module.mrp.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class EconomicOrderQtyStrategy implements LotSizingStrategy {

    @Override
    public String name() {
        return "eoq";
    }

    @Override
    public BigDecimal calculate(BigDecimal netDemand, BigDecimal moq, Map<String, Object> params) {
        BigDecimal annualDemand = (BigDecimal) params.get("annualDemand");
        BigDecimal orderCost = (BigDecimal) params.get("orderCost");
        BigDecimal holdingCost = (BigDecimal) params.get("holdingCostPerUnit");

        if (annualDemand == null || orderCost == null || holdingCost == null
            || holdingCost.compareTo(BigDecimal.ZERO) <= 0) {
            return netDemand;
        }

        // EOQ = sqrt(2 * D * S / H), using BigDecimal.sqrt (Java 9+)
        BigDecimal twoDS = annualDemand.multiply(orderCost).multiply(BigDecimal.TWO);
        BigDecimal eoqSquared = twoDS.divide(holdingCost, 10, RoundingMode.HALF_UP);
        BigDecimal eoqBatch = eoqSquared.sqrt(new MathContext(10));
        eoqBatch = eoqBatch.setScale(0, RoundingMode.CEILING);

        // Order enough batches to cover the net demand
        if (eoqBatch.compareTo(BigDecimal.ZERO) > 0 && netDemand.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal batches = netDemand.divide(eoqBatch, 0, RoundingMode.CEILING);
            BigDecimal result = batches.multiply(eoqBatch);

            if (moq != null && moq.compareTo(BigDecimal.ZERO) > 0 && result.compareTo(moq) < 0) {
                return moq;
            }
            return result;
        }

        if (moq != null && moq.compareTo(BigDecimal.ZERO) > 0 && netDemand.compareTo(moq) < 0) {
            return moq;
        }
        return netDemand;
    }
}
