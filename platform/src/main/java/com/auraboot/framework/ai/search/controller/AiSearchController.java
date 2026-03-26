package com.auraboot.framework.ai.search.controller;

import com.auraboot.framework.ai.search.dto.AiSearchRequest;
import com.auraboot.framework.ai.search.dto.AiSearchResult;
import com.auraboot.framework.ai.search.service.AiSearchService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI-powered natural language search endpoint.
 * <p>
 * Accepts free-form text queries and uses LLM to parse them into structured
 * filters, then executes against the dynamic data layer. Falls back to
 * cross-model keyword search when no LLM provider is configured.
 *
 * @author AuraBoot Team
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/ai/search")
@RequiredArgsConstructor
@Tag(name = "AI Search", description = "Natural language search powered by LLM")
public class AiSearchController {

    private final AiSearchService aiSearchService;

    @GetMapping
    @Operation(
            summary = "AI natural language search",
            description = "Parse a natural language query into structured filters using LLM, "
                    + "then execute against dynamic data. Falls back to keyword search if no LLM is configured."
    )
    public ApiResponse<AiSearchResult> search(
            @Parameter(description = "Natural language search query", required = true)
            @RequestParam String query,

            @Parameter(description = "Platform hint: web or mobile")
            @RequestParam(defaultValue = "web") String platform,

            @Parameter(description = "Maximum number of results")
            @RequestParam(defaultValue = "20") int maxResults) {

        log.info("AI search request: query='{}', platform={}, maxResults={}", query, platform, maxResults);

        AiSearchRequest request = AiSearchRequest.builder()
                .query(query)
                .platform(platform)
                .maxResults(maxResults)
                .build();

        AiSearchResult result = aiSearchService.search(request);

        log.info("AI search completed: modelCode={}, llmUsed={}, totalCount={}",
                result.getModelCode(), result.isLlmUsed(), result.getTotalCount());

        return ApiResponse.success(result);
    }
}
