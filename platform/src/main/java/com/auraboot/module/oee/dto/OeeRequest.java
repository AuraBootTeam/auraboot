package com.auraboot.module.oee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OeeRequest {
    private Long tenantId;
    private String equipmentId;   // PCBA equipment primary key (ULID string, do NOT Long.parseLong)
    private String equipmentCode; // business equipment code; telemetry sources may key asset_code by this value
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
}
