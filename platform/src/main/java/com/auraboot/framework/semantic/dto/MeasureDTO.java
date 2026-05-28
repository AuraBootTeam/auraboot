package com.auraboot.framework.semantic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class MeasureDTO {
    private String code;
    private Map<String, String> label;

    /** SUM / COUNT / AVG / MAX / MIN / COUNT_DISTINCT */
    private String agg;

    @JsonProperty("field_ref")
    private String fieldRef;

    private String expr;
}
