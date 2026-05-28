package com.auraboot.framework.semantic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class DimensionDTO {
    private String code;
    private Map<String, String> label;
    private String description;
    /** time / categorical / numeric / boolean */
    private String type;

    @JsonProperty("field_ref")
    private String fieldRef;

    @JsonProperty("time_grains")
    private List<String> timeGrains;

    @JsonProperty("primary_time")
    private Boolean primaryTime;
}
