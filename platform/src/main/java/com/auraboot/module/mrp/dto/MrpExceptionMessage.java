package com.auraboot.module.mrp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MrpExceptionMessage {

    private Long materialId;
    private String materialName;
    private String type;
    private String severity;
    private String description;
    private String suggestedAction;
}
