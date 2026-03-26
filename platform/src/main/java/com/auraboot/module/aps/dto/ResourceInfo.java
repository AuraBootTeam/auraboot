package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ResourceInfo {
    private Long id;
    private String name;
    private String type;
    private BigDecimal capacityPerHour;
}
