package com.auraboot.framework.view.dto;

import com.auraboot.framework.view.entity.ViewConfig;
import lombok.Data;

@Data
public class SavedViewCapabilityCheckRequest {
    private String modelCode;
    private String pageKey;
    private String viewType;
    private ViewConfig viewConfig;
}
