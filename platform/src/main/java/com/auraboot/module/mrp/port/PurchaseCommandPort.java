package com.auraboot.module.mrp.port;

import com.auraboot.module.mrp.dto.PlannedOrderDto;

/**
 * Port interface for creating purchase planned orders.
 */
public interface PurchaseCommandPort {

    Long createPlannedOrder(PlannedOrderDto order);
}
