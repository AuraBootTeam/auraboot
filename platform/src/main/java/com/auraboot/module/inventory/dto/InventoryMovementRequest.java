package com.auraboot.module.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for recording an inventory movement (manual adjustment or API trigger).
 */
@Data
public class InventoryMovementRequest {

    /**
     * Movement type: IN, OUT, TRANSFER, ADJUSTMENT
     */
    private String movementType;

    /**
     * Product PID (required)
     */
    private String productPid;

    /**
     * Warehouse PID (optional)
     */
    private String warehousePid;

    /**
     * Signed quantity: positive = IN, negative = OUT.
     * For ADJUSTMENT this may be positive or negative.
     */
    private BigDecimal quantity;

    /**
     * Unit cost at time of movement (optional)
     */
    private BigDecimal unitCost;

    /**
     * Originating document type: PURCHASE_ORDER, SALES_ORDER, MANUAL, etc.
     */
    private String referenceType;

    /**
     * Originating document PID (optional)
     */
    private String referencePid;

    /**
     * Free-form note
     */
    private String note;
}
