package com.auraboot.framework.plugin.template;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Template Center API -- product-level endpoints for template browsing, preview, and installation.
 *
 * <p>Unlike {@link com.auraboot.framework.plugin.controller.PluginImportController} (which requires
 * file system paths for CLI/dev use), this controller accepts {@code templateId} and resolves
 * the source internally via {@link TemplateRegistry}. Frontend never needs to know file paths.</p>
 */
@Slf4j
@RestController("pluginTemplateController")
@RequestMapping("/api/templates")
@RequiredArgsConstructor
@Tag(name = "Template Center", description = "Browse, preview, and install application templates")
public class TemplateController {

    private final TemplateRegistry templateRegistry;
    private final PluginImportService importService;

    /**
     * List all available templates.
     */
    @GetMapping
    @Operation(summary = "List templates", description = "List all available application templates")
    public ResponseEntity<ApiResponse<List<TemplateRegistry.TemplateDef>>> listTemplates() {
        return ResponseEntity.ok(ApiResponse.success(templateRegistry.listAll()));
    }

    /**
     * Preview template contents (dry-run parse, no installation).
     * Returns the same {@link ImportPreviewResult} as parse-directory, but resolved by templateId.
     */
    @GetMapping("/{templateId}/preview")
    @Operation(summary = "Preview template",
            description = "Parse template resources without installing. Returns models, fields, commands, pages that would be created.")
    public ResponseEntity<?> preview(
            @Parameter(description = "Template ID, e.g., 'crm-quick-start'")
            @PathVariable String templateId) {

        String absolutePath = templateRegistry.resolveAbsolutePath(templateId);
        if (absolutePath == null) {
            return ResponseEntity.notFound().build();
        }

        log.info("Previewing template: id={}, path={}", templateId, absolutePath);
        ImportPreviewResult result = importService.parseDirectory(absolutePath);
        return ResponseEntity.ok(result);
    }

    /**
     * Install a template into the current tenant.
     * Equivalent to import-directory-sync but resolved by templateId.
     */
    @PostMapping("/{templateId}/install")
    @RequirePermission("plugin.plugin.manage")
    @Operation(summary = "Install template",
            description = "Install a template into the current tenant. Creates all models, fields, commands, pages, menus, and permissions.")
    public ResponseEntity<?> install(
            @Parameter(description = "Template ID, e.g., 'crm-quick-start'")
            @PathVariable String templateId,
            @RequestBody(required = false) Map<String, Object> options) {

        String absolutePath = templateRegistry.resolveAbsolutePath(templateId);
        if (absolutePath == null) {
            return ResponseEntity.notFound().build();
        }

        log.info("Installing template: id={}, path={}", templateId, absolutePath);

        // Parse
        ImportPreviewResult preview = importService.parseDirectory(absolutePath);
        if (!preview.isValid()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Invalid template: " + String.join(", ", preview.getErrors())));
        }

        // Build import request with defaults
        ImportRequest importRequest = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true)
                .autoPublishFields(true)
                .autoPublishCommands(true)
                .autoPublishPages(true)
                .build();

        // Allow overrides from request body
        if (options != null) {
            if (options.containsKey("conflictStrategy")) {
                String strategy = String.valueOf(options.get("conflictStrategy"));
                try {
                    importRequest.setConflictStrategy(
                            ImportRequest.ConflictStrategy.valueOf(strategy.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // Keep default OVERWRITE if invalid value provided
                }
            }
            if (Boolean.FALSE.equals(options.get("autoPublishModels"))) {
                importRequest.setAutoPublishModels(false);
            }
            if (Boolean.FALSE.equals(options.get("autoPublishFields"))) {
                importRequest.setAutoPublishFields(false);
            }
            if (Boolean.FALSE.equals(options.get("autoPublishCommands"))) {
                importRequest.setAutoPublishCommands(false);
            }
            if (Boolean.FALSE.equals(options.get("autoPublishPages"))) {
                importRequest.setAutoPublishPages(false);
            }
        }

        // Execute
        ImportExecuteResult result = importService.execute(preview.getImportId(), importRequest);

        log.info("Template installed: id={}, success={}", templateId, result.isSuccess());
        return ResponseEntity.ok(result);
    }
}
