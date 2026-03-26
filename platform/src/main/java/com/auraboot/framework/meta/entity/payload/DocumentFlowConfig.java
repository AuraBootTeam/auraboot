package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * Configuration for cross-module document flow.
 * When a command executes, this config drives automatic creation of downstream documents
 * (e.g., confirming a sales order creates an inventory outbound order + AR invoice).
 *
 * <p>Supported expression formats in {@code fieldMapping} values:</p>
 * <ul>
 *   <li>{@code ${record.fieldCode}} — lookup field value from source record</li>
 *   <li>{@code ${recordId}} — source record PID</li>
 *   <li>{@code 'literal'} — string literal (single quotes stripped)</li>
 *   <li>Plain values — used as-is</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 2.7.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentFlowConfig {

    /**
     * Target model code to create a document in.
     * e.g., "inv_outbound_order", "ar_invoice"
     */
    private String targetModelCode;

    /**
     * Header-level field mapping: target field code → source expression.
     * Supports ${record.xxx}, ${recordId}, and 'literal' syntax.
     */
    private Map<String, String> fieldMapping;

    /**
     * Optional line-level mapping for order line replication.
     * If present, source lines are fetched and replicated into the target line model.
     */
    private LineMapping lineMapping;

    /**
     * Line-item mapping configuration for document flow.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineMapping {

        /**
         * Source line model code.
         * e.g., "sales_order_line"
         */
        private String sourceLineModel;

        /**
         * Foreign key column in the source line table pointing to the source header record.
         * e.g., "so_order_id"
         */
        private String sourceForeignKey;

        /**
         * Target line model code.
         * e.g., "inv_outbound_order_line"
         */
        private String targetLineModel;

        /**
         * Foreign key column in the target line table pointing to the newly created target header.
         * e.g., "iol_order_id"
         */
        private String targetForeignKey;

        /**
         * Field mapping for each line: target line field code → source expression.
         * Supports ${record.xxx} (source header), ${line.xxx} (source line item),
         * ${recordId} (source header PID), and 'literal' syntax.
         */
        private Map<String, String> fieldMapping;
    }
}
