package com.auraboot.framework.agent.nlmodeling.controller;

import com.auraboot.framework.agent.nlmodeling.NlModelingService;
import com.auraboot.framework.agent.nlmodeling.dto.*;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for AI Natural Language Modeling.
 *
 * <ul>
 *   <li>POST /api/agent/nl-modeling/generate — Generate DSL from natural language</li>
 *   <li>POST /api/agent/nl-modeling/refine — Conversational refinement</li>
 *   <li>POST /api/agent/nl-modeling/apply — Apply generated config as plugin</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/nl-modeling")
@RequiredArgsConstructor
@Tag(name = "NL Modeling", description = "AI Natural Language Modeling — generate DSL from text")
// NL modeling generates and applies model/page/command schema into the tenant (apply imports a
// plugin with autoPublish + OVERWRITE). This is a designer/admin capability — guard it with the
// meta model-management permission rather than the PermissionInterceptor fail-open default.
@RequirePermission(MetaPermission.MODEL_MANAGE)
public class NlModelingController {

    private final NlModelingService nlModelingService;

    @PostMapping("/generate")
    @Operation(summary = "Generate DSL from natural language description")
    public ResponseEntity<NlModelingResponse> generate(@RequestBody NlModelingRequest request) {
        log.info("NL Modeling generate request: description length={}",
                request.getDescription() != null ? request.getDescription().length() : 0);

        NlModelingResponse response = nlModelingService.generate(request);

        if (response.getValidationErrors() != null && !response.getValidationErrors().isEmpty()) {
            log.warn("NL Modeling generate returned validation errors: {}", response.getValidationErrors());
        } else {
            log.info("NL Modeling generated plugin: {}, summary: {}",
                    response.getPluginCode(), response.getSummary());
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/generate-page")
    @Operation(summary = "Tools-off completion for the in-designer page-generation copilot")
    public ResponseEntity<java.util.Map<String, Object>> generatePage(
            @RequestBody java.util.Map<String, String> request) {
        String message = request.get("message");
        log.info("Page-design generate request: message length={}",
                message != null ? message.length() : 0);
        String content = nlModelingService.generatePageDsl(request.get("systemPrompt"), message);
        if (content == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "LLM provider not configured or returned no content"));
        }
        return ResponseEntity.ok(java.util.Map.of("content", content));
    }

    @PostMapping("/refine")
    @Operation(summary = "Refine generated DSL via conversational instruction")
    public ResponseEntity<NlModelingResponse> refine(@RequestBody NlRefineRequest request) {
        log.info("NL Modeling refine request: sessionId={}, instruction length={}",
                request.getSessionId(),
                request.getInstruction() != null ? request.getInstruction().length() : 0);

        NlModelingResponse response = nlModelingService.refine(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/apply")
    @Operation(summary = "Apply generated DSL configuration as a plugin")
    public ResponseEntity<ApiResponse<ImportExecuteResult>> apply(@RequestBody NlApplyRequest request) {
        log.info("NL Modeling apply request: pluginCode={}", request.getPluginCode());

        ImportExecuteResult result = nlModelingService.apply(request);

        if (result.isSuccess()) {
            log.info("NL Modeling plugin applied successfully: {}", request.getPluginCode());
            return ResponseEntity.ok(ApiResponse.success("Plugin applied successfully", result));
        } else {
            log.warn("NL Modeling plugin apply failed: {}", result.getErrorMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(result.getErrorMessage()));
        }
    }
}
