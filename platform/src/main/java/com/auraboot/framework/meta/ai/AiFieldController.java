package com.auraboot.framework.meta.ai;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationRequest;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Field Controller
 *
 * Provides APIs for AI-powered field operations like text generation,
 * summarization, translation, classification, and extraction.
 */
@RestController
@RequestMapping("/api/meta/ai")
@RequiredArgsConstructor
public class AiFieldController {

    private final AiFieldProcessor aiFieldProcessor;

    /**
     * Generate AI content for a field
     *
     * @param request AI generation request containing operation type, prompt, and source content
     * @return generated content
     */
    @PostMapping("/generate")
    public ApiResponse<AiGenerationResult> generate(@RequestBody AiGenerationRequest request) {
        AiGenerationResult result = aiFieldProcessor.process(request);
        if (result.isSuccess()) {
            return ApiResponse.ok(result);
        }
        return ApiResponse.error(result.getError());
    }

    /**
     * Fill a specific record's AI field
     *
     * @param modelCode model code
     * @param recordId record ID
     * @param request request containing fieldCode, operation, sourceFields, prompt
     */
    @PostMapping("/models/{modelCode}/records/{recordId}/ai-fill")
    public ApiResponse<AiGenerationResult> aiFillField(
            @PathVariable String modelCode,
            @PathVariable String recordId,
            @RequestBody AiFillRequest request) {

        // Build generation request from fill request
        AiGenerationRequest genRequest = AiGenerationRequest.builder()
                .operation(request.getOperation())
                .prompt(request.getPrompt())
                .sourceContent(request.getSourceContent())
                .targetLanguage(request.getTargetLanguage())
                .categories(request.getCategories())
                .extractFields(request.getExtractFields())
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .build();

        AiGenerationResult result = aiFieldProcessor.process(genRequest);
        return result.isSuccess() ? ApiResponse.ok(result) : ApiResponse.error(result.getError());
    }

    /**
     * Batch AI fill for multiple records
     *
     * @param modelCode model code
     * @param request batch fill request
     */
    @PostMapping("/models/{modelCode}/batch-ai-fill")
    public ApiResponse<Map<String, AiGenerationResult>> batchAiFill(
            @PathVariable String modelCode,
            @RequestBody BatchAiFillRequest request) {

        Map<String, AiGenerationResult> results = new java.util.LinkedHashMap<>();

        for (String recordId : request.getRecordIds()) {
            AiGenerationRequest genRequest = AiGenerationRequest.builder()
                    .operation(request.getOperation())
                    .prompt(request.getPrompt())
                    .sourceContent(request.getSourceContents() != null
                            ? request.getSourceContents().getOrDefault(recordId, Map.of())
                            : Map.of())
                    .maxTokens(request.getMaxTokens())
                    .temperature(request.getTemperature())
                    .build();

            results.put(recordId, aiFieldProcessor.process(genRequest));
        }

        return ApiResponse.ok(results);
    }

    /**
     * Get available AI operations
     */
    @GetMapping("/operations")
    public ApiResponse<List<Map<String, String>>> getOperations() {
        return ApiResponse.ok(List.of(
                Map.of("code", "generate", "name", "Generate", "description", "Generate content from a prompt"),
                Map.of("code", "summarize", "name", "Summarize", "description", "Summarize content from source fields"),
                Map.of("code", "translate", "name", "Translate", "description", "Translate content to target language"),
                Map.of("code", "classify", "name", "Classify", "description", "Classify content into categories"),
                Map.of("code", "extract", "name", "Extract", "description", "Extract structured data from text")
        ));
    }

    /**
     * Single record AI fill request
     */
    @lombok.Data
    public static class AiFillRequest {
        private String fieldCode;
        private String operation;
        private String prompt;
        private Map<String, String> sourceContent;
        private String targetLanguage;
        private List<String> categories;
        private List<String> extractFields;
        private Integer maxTokens;
        private Double temperature;
    }

    /**
     * Batch AI fill request
     */
    @lombok.Data
    public static class BatchAiFillRequest {
        private List<String> recordIds;
        private String fieldCode;
        private String operation;
        private String prompt;
        private Map<String, Map<String, String>> sourceContents;
        private Integer maxTokens;
        private Double temperature;
    }
}
