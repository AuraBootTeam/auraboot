package com.auraboot.framework.meta.template.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Configuration for DOCUMENT-type models.
 * Parsed from model extension.documentConfig in plugin models.json.
 *
 * Drives auto-generation of standard commands (CREATE, ADD_LINE, DELETE_LINE, state transitions).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentConfig {

    /** Child line model code (e.g. "sl_sales_order_line") */
    private String lineModel;

    /** FK field in line model pointing to header (e.g. "sl_sol_order_id") */
    private String lineForeignKey;

    /** Auto-generated document number field (e.g. "sl_so_code") */
    private String codeField;

    /** Number pattern (e.g. "SO-{yyyyMMdd}-{seq}") */
    private String codePattern;

    /** Status field on header (e.g. "sl_so_status") */
    private String statusField;

    /** Aggregate fields: each maps parentField → childField for SUM */
    private List<TotalFieldMapping> totalFields;

    /** Qty field on line for computed amount (e.g. "sl_sol_qty") */
    private String lineQtyField;

    /** Price field on line for computed amount (e.g. "sl_sol_price") */
    private String linePriceField;

    /** Computed amount field on line (e.g. "sl_sol_amount") */
    private String lineAmountField;

    /** State machine template: SIMPLE, STANDARD, FULL. Default: STANDARD */
    private String stateMachine;

    public String getEffectiveStateMachine() {
        return stateMachine != null ? stateMachine : "standard";
    }

    public boolean hasLineModel() {
        return lineModel != null && !lineModel.isBlank();
    }

    public boolean hasComputedAmount() {
        return lineQtyField != null && linePriceField != null && lineAmountField != null;
    }

    /**
     * Mapping of parent total field to child detail field for AGGREGATE SUM.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TotalFieldMapping {
        /** Field on header model to write sum (e.g. "sl_so_total_amount") */
        private String parentField;
        /** Field on line model to aggregate (e.g. "sl_sol_amount") */
        private String childField;
    }

    /**
     * Parse documentConfig from model extension map.
     * Returns null if not present.
     */
    @SuppressWarnings("unchecked")
    public static DocumentConfig fromExtension(Map<String, Object> extension) {
        if (extension == null) return null;
        Object dcObj = extension.get("documentConfig");
        if (dcObj == null) return null;

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.convertValue(dcObj, DocumentConfig.class);
        } catch (Exception e) {
            return null;
        }
    }
}
