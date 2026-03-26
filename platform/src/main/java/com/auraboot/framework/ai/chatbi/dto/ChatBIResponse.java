package com.auraboot.framework.ai.chatbi.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the ChatBI query endpoint.
 * Contains query results along with chart rendering hints.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ChatBIResponse {

    /**
     * The interpreted query in plain English (what the system understood).
     */
    private String interpretation;

    /**
     * The model code that was queried.
     */
    private String modelCode;

    /**
     * Column names in result order.
     */
    private List<String> columns;

    /**
     * Query result rows.
     */
    private List<Map<String, Object>> records;

    /**
     * Recommended chart type: "table", "bar", "pie", "line".
     */
    private String chartType;

    /**
     * Optional chart configuration hints (axis labels, title, etc.).
     */
    private Map<String, Object> chartConfig;

    /**
     * The generated SQL for transparency/debugging.
     */
    private String sql;

    /**
     * Total row count in the result.
     */
    private int total;
}
