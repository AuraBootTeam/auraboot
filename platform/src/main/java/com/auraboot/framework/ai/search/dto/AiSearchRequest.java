package com.auraboot.framework.ai.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for AI search endpoint.
 *
 * @author AuraBoot Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSearchRequest {

    /** Natural language query from the user */
    private String query;

    /** Platform hint: "web" or "mobile" — affects result size */
    @Builder.Default
    private String platform = "web";

    /** Maximum number of results to return */
    @Builder.Default
    private int maxResults = 20;
}
