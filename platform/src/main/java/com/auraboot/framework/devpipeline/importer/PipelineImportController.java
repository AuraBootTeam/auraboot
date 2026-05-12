package com.auraboot.framework.devpipeline.importer;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/dev-pipeline/import")
@RequirePermission(MetaPermission.MODEL_MANAGE)
public class PipelineImportController {

    private static final String DEFAULT_IMPORTED_BY = "owner";

    private final PipelineImportService importService;

    public PipelineImportController(PipelineImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/preview")
    public ApiResponse<PipelineImportPreview> preview(@RequestBody PipelineImportHttpRequest request) {
        PipelineImportRequest serviceRequest;
        try {
            serviceRequest = toServiceRequest(request, true);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
        if (serviceRequest == null) {
            return ApiResponse.error("packetPath is required");
        }

        return ApiResponse.success(importService.previewPacket(serviceRequest));
    }

    @PostMapping
    public ApiResponse<PipelineImportResult> importPacket(@RequestBody PipelineImportHttpRequest request) {
        PipelineImportRequest serviceRequest;
        try {
            serviceRequest = toServiceRequest(request, false);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
        if (serviceRequest == null) {
            return ApiResponse.error("packetPath is required");
        }

        return ApiResponse.success(importService.importFromPacket(serviceRequest));
    }

    private PipelineImportRequest toServiceRequest(PipelineImportHttpRequest request, boolean preview) {
        if (request == null || request.packetPath() == null || request.packetPath().isBlank()) {
            return null;
        }

        Path packetPath;
        try {
            packetPath = Path.of(request.packetPath().trim());
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("packetPath is invalid: " + request.packetPath(), ex);
        }

        return new PipelineImportRequest(
                packetPath,
                preview || Boolean.TRUE.equals(request.dryRun()),
                parseConflictStrategy(request.conflictStrategy()),
                !preview && request.finalizeMirror() != Boolean.FALSE,
                normalizeImportedBy(request.importedBy())
        );
    }

    private ConflictStrategy parseConflictStrategy(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return ConflictStrategy.ERROR;
        }

        try {
            return ConflictStrategy.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported conflictStrategy: " + rawValue, ex);
        }
    }

    private String normalizeImportedBy(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT_IMPORTED_BY;
        }
        return rawValue.trim();
    }

    public record PipelineImportHttpRequest(
            String packetPath,
            Boolean dryRun,
            String conflictStrategy,
            Boolean finalizeMirror,
            String importedBy
    ) {
    }
}
