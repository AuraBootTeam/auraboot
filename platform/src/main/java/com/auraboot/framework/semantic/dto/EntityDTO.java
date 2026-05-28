package com.auraboot.framework.semantic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class EntityDTO {
    private String name;
    /** primary / foreign / natural */
    private String type;
    @JsonProperty("field_ref")
    private String fieldRef;
    /** Cross-model relationship: {@code <model_code>.<field_code>} */
    private String relation;
}
