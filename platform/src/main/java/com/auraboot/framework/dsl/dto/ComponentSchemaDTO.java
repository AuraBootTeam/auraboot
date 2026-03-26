package com.auraboot.framework.dsl.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO representing a single component's schema definition.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComponentSchemaDTO {

    private String type;
    private String name;
    private String category;
    private String description;
    private List<String> compatibleDataTypes;
    private List<Map<String, Object>> properties;
    private List<String> tags;
}
