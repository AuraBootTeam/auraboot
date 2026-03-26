package com.auraboot.framework.meta.ai;

import com.auraboot.framework.common.dto.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * AI Model Suggestion Controller
 *
 * Provides AI-powered model/field/view suggestions based on
 * natural language business scenario descriptions.
 *
 * Delegates to {@link AiModelSuggestionService} for core logic.
 */
@RestController
@RequestMapping("/api/meta/ai")
@RequiredArgsConstructor
public class AiModelSuggestionController {

    private final AiModelSuggestionService aiModelSuggestionService;

    /**
     * Suggest a model structure from a natural language description
     *
     * @param request description of the business scenario
     * @return suggested model with fields and views
     */
    @PostMapping("/suggest-model")
    public ApiResponse<AiModelSuggestionService.ModelSuggestion> suggestModel(@RequestBody SuggestModelRequest request) {
        AiModelSuggestionService.ModelSuggestion suggestion =
                aiModelSuggestionService.suggestModel(request.getDescription(), request.getLanguage());
        return ApiResponse.ok(suggestion);
    }

    @Data
    public static class SuggestModelRequest {
        /** Natural language description of the business scenario */
        private String description;
        /** Language for field names: zh, en */
        private String language;
    }
}
