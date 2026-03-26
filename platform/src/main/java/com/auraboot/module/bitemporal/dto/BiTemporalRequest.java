package com.auraboot.module.bitemporal.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Request DTO for bi-temporal record creation and correction.
 *
 * @since 6.0.0
 */
@Data
public class BiTemporalRequest {

    /**
     * Business-time start (inclusive).
     */
    private LocalDateTime validFrom;

    /**
     * Business-time end (exclusive). Defaults to 9999-12-31T23:59:59 if not provided.
     */
    private LocalDateTime validTo;

    /**
     * The entity state as JSON payload.
     */
    private JsonNode payload;
}
