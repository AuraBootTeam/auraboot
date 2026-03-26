package com.auraboot.module.mrp.port;

import com.auraboot.module.mrp.dto.PlannedOrderDto;

/**
 * Port interface for creating production planned orders.
 */
public interface ProductionCommandPort {

    Long createPlannedOrder(PlannedOrderDto order);
}
