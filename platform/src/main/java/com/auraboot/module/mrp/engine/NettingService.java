package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.port.InventoryQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class NettingService {

    private final InventoryQueryPort inventoryPort;

    /**
     * Calculate net demand for a material.
     * Formula: net = gross - (onHand - allocated) - inTransit + safetyStock
     * If net <= 0, no replenishment needed.
     */
    public BigDecimal calculateNetDemand(Long materialId, BigDecimal grossDemand) {
        BigDecimal onHand = nullToZero(inventoryPort.getOnHandQty(materialId, null));
        BigDecimal allocated = nullToZero(inventoryPort.getAllocatedQty(materialId));
        BigDecimal inTransit = nullToZero(inventoryPort.getInTransitQty(materialId));
        BigDecimal safetyStock = nullToZero(inventoryPort.getSafetyStock(materialId));

        BigDecimal available = onHand.subtract(allocated);

        BigDecimal net = grossDemand
            .subtract(available)
            .subtract(inTransit)
            .add(safetyStock);

        return net.max(BigDecimal.ZERO);
    }

    /**
     * Check if a material needs replenishment.
     */
    public boolean needsReplenishment(Long materialId, BigDecimal grossDemand) {
        return calculateNetDemand(materialId, grossDemand).compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
