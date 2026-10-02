package com.auraboot.framework.branding;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthAppearanceController {
    private final AuthAppearanceService service;
    private final AuthAppearanceAssetService assetService;

    public record SaveRequest(@NotNull @Min(0) Long expectedVersion, @NotNull JsonNode appearance) {}
    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) {}
    public record RollbackRequest(@NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) Long targetVersion) {}

    @PostMapping(value = "/admin/auth-appearance/assets", consumes = "multipart/form-data")
    @RequirePlatformAdmin
    public ApiResponse<AuthAppearanceAssetService.Asset> upload(
            @org.springframework.web.bind.annotation.RequestParam("file")
            org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return ApiResponse.success(assetService.upload(file));
    }

    @GetMapping("/auth/appearance/assets/{filename}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> asset(
            @org.springframework.web.bind.annotation.PathVariable String filename) throws java.io.IOException {
        java.nio.file.Path file;
        try {
            file = assetService.read(filename);
        } catch (java.nio.file.NoSuchFileException exception) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        String mediaType = filename.endsWith(".png") ? "image/png"
                : filename.endsWith(".jpg") ? "image/jpeg" : "image/webp";
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.parseMediaType(mediaType))
                .body(new org.springframework.core.io.FileSystemResource(file));
    }

    @GetMapping("/auth/appearance")
    public ApiResponse<AuthAppearanceService.Published> published() {
        return ApiResponse.success(service.published());
    }

    @GetMapping("/admin/auth-appearance")
    @RequirePlatformAdmin
    public ApiResponse<AuthAppearanceService.View> view() {
        return ApiResponse.success(service.view());
    }

    @PutMapping("/admin/auth-appearance/draft")
    @RequirePlatformAdmin
    public ApiResponse<AuthAppearanceService.View> save(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.success(service.save(request.expectedVersion(), request.appearance()));
    }

    @PostMapping("/admin/auth-appearance/publish")
    @RequirePlatformAdmin
    public ApiResponse<AuthAppearanceService.View> publish(@Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(service.publish(request.expectedVersion()));
    }

    @PostMapping("/admin/auth-appearance/rollback")
    @RequirePlatformAdmin
    public ApiResponse<AuthAppearanceService.View> rollback(@Valid @RequestBody RollbackRequest request) {
        return ApiResponse.success(service.rollback(request.expectedVersion(), request.targetVersion()));
    }
}
