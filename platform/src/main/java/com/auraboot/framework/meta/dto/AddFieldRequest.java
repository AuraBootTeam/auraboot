package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddFieldRequest {
    private String modelCode;
    private String code;
    private String dataType;
    private String displayName;
    private Boolean required;
    private Integer maxLength;
    private String comment;
    private Long tenantId;
}
