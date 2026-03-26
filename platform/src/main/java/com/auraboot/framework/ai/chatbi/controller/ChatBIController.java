package com.auraboot.framework.ai.chatbi.controller;

import com.auraboot.framework.ai.chatbi.dto.ChatBIRequest;
import com.auraboot.framework.ai.chatbi.dto.ChatBIResponse;
import com.auraboot.framework.ai.chatbi.service.ChatBIService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * ChatBI Controller — natural language to structured query to chart.
 *
 * <p>Accepts a plain-English question, parses it into a structured query,
 * executes against the model data, and returns results with chart rendering hints.</p>
 *
 * <p>Current implementation uses keyword-based parsing (no LLM required).
 * Future: integrate with ACP LLM providers for full NLP support.</p>
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/ai/chat-bi")
@RequiredArgsConstructor
@Tag(name = "ChatBI", description = "Natural language to chart query API")
public class ChatBIController {

    private final ChatBIService chatBIService;

    /**
     * Execute a natural language query and return chart-ready results.
     *
     * @param request contains the question and optional modelCode
     * @return query results with columns, records, chartType, chartConfig, and sql
     */
    @PostMapping("/query")
    @Operation(
            summary = "ChatBI query",
            description = "Convert a natural language question to a structured query and return chart-ready results. "
                    + "Supports COUNT, SUM, AVG aggregations with GROUP BY and TOP N ranking. "
                    + "Chart type is automatically suggested based on the query shape.")
    public ApiResponse<ChatBIResponse> query(@Valid @RequestBody ChatBIRequest request) {
        log.info("ChatBI query: question='{}', modelCode='{}'", request.getQuestion(), request.getModelCode());
        ChatBIResponse response = chatBIService.analyzeQuestion(request);
        return ApiResponse.success(response);
    }

    /**
     * Health check endpoint — confirms the ChatBI service is available.
     */
    @GetMapping("/health")
    @Operation(summary = "ChatBI health check", description = "Returns status of the ChatBI service")
    public ApiResponse<String> health() {
        return ApiResponse.success("ChatBI service is running (keyword-parsing mode)");
    }
}
