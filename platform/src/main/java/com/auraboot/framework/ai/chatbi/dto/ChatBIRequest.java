package com.auraboot.framework.ai.chatbi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for the ChatBI natural language query endpoint.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
public class ChatBIRequest {

    /**
     * Natural language question from the user.
     * Examples: "top 10 orders by amount", "count leads by status", "monthly revenue trend"
     */
    @NotBlank(message = "question is required")
    @Size(max = 500, message = "question must not exceed 500 characters")
    private String question;

    /**
     * Optional model code to scope the query.
     * If provided, the query will be restricted to this model.
     * If null, the service will attempt to infer the model from question keywords.
     */
    private String modelCode;
}
