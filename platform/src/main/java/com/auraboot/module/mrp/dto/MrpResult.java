package com.auraboot.module.mrp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MrpResult {

    private List<PlannedOrderDto> plannedOrders;
    private List<MrpExceptionMessage> exceptions;
}
