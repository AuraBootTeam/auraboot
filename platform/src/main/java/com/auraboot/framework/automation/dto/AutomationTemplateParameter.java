package com.auraboot.framework.automation.dto;

import lombok.Data;

/** A record reference input declared by a portable automation template. */
@Data
public class AutomationTemplateParameter {
    private String key;
    private String kind;
    private String label;
    private String helpText;
    private String modelCode;
    private String displayField;
}
