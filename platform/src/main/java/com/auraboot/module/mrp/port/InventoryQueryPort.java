package com.auraboot.module.mrp.port;

import java.math.BigDecimal;

/**
 * Port interface for querying inventory data.
 * Decouples MRP algorithm from AuraBoot's dynamic table infrastructure.
 */
public interface InventoryQueryPort {

    BigDecimal getOnHandQty(Long materialId, Long warehouseId);

    BigDecimal getInTransitQty(Long materialId);

    BigDecimal getAllocatedQty(Long materialId);

    BigDecimal getSafetyStock(Long materialId);

    BigDecimal getAvailableQty(Long materialId);
}
