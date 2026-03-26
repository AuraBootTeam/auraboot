package com.auraboot.framework.ai.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single filter condition parsed from a natural language query by the LLM.
 *
 * @author AuraBoot Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedFilter {

    /** Field code in the model (e.g. "crm_lead_status") */
    private String fieldName;

    /** Operator: EQ, NE, GT, GE, LT, LE, LIKE, IN */
    private String operator;

    /** Raw value parsed by the LLM */
    private Object value;

    /** Human-readable description (e.g. "status is active") */
    private String displayValue;
}
