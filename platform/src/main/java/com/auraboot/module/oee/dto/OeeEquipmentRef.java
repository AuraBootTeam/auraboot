package com.auraboot.module.oee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight reference to one piece of equipment, used by the fleet OEE service to iterate every
 * equipment of a tenant. {@code equipmentId} is the {@code pe_equipment} primary key (ULID string).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OeeEquipmentRef {
    private String equipmentId;
    private String code;
    private String name;
}
