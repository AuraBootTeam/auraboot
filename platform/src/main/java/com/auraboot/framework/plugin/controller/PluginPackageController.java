package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.plugin.dto.packages.*;
import com.auraboot.framework.plugin.dto.uninstall.UninstallPreviewResult;
import com.auraboot.framework.plugin.service.PluginPackageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * REST API for unified plugin package operations.
 *
 * <p>This controller handles the complete lifecycle of unified plugin packages
 * that may contain configuration (DSL), backend (PF4J), and frontend (Module Federation) components.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins/packages")
@RequiredArgsConstructor
@Tag(name = "Plugin Packages", description = "Unified plugin package management")
public class PluginPackageController {

    private final PluginPackageService packageService;

    private static final String LOCAL_PATH_API_DISABLED =
            "Parsing server-local paths through the REST API is disabled. Upload the package file instead.";
    private static final String LOCAL_DIRECTORY_API_DISABLED =
            "Parsing server-local directories through the REST API is disabled. Upload the package file instead.";
    private static final String LOCAL_INSTALL_PATH_API_DISABLED =
            "Installing server-local paths through the REST API is disabled. Upload the package file instead.";

    // ==================== Upload & Parse ====================

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and parse package",
            description = "Upload a unified plugin package (ZIP) and parse its contents")
    public ResponseEntity<PackageParseResult> upload(
            @Parameter(description = "Plugin package file (ZIP or JSON)")
            @RequestParam("file") MultipartFile file) {

        log.info("Uploading plugin package: {}", file.getOriginalFilename());
        PackageParseResult result = packageService.parsePackage(file);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Upload and parse package (stream)",
            description = "Upload a unified plugin package as octet-stream from BFF")
    public ResponseEntity<PackageParseResult> uploadStream(
            @Parameter(description = "Filename from BFF")
            @RequestHeader(value = "X-Filename", required = false) String filename,
            @Parameter(description = "Request body containing file data")
            java.io.InputStream inputStream) {

        String decodedFilename = "unknown.zip";
        if (filename != null && !filename.isBlank()) {
            try {
                decodedFilename = java.net.URLDecoder.decode(filename, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                decodedFilename = filename;
            }
        }
        // Sanitize filename: strip path separators to prevent path traversal
        decodedFilename = Paths.get(decodedFilename).getFileName().toString();

        log.info("Uploading plugin package (stream): {}", decodedFilename);
        PackageParseResult result = packageService.parsePackageFromStream(inputStream, decodedFilename);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/parse-path")
    @Operation(summary = "Parse package from path",
            description = "Parse a plugin package from a local file path")
    public ResponseEntity<PackageParseResult> parsePath(
            @Parameter(description = "Path to the plugin package")
            @RequestBody Map<String, String> request) {

        return ResponseEntity.badRequest()
                .body(PackageParseResult.failure(null, LOCAL_PATH_API_DISABLED));
    }

    @PostMapping("/parse-directory")
    @Operation(summary = "Parse package from directory",
            description = "Parse a plugin package from a directory structure")
    public ResponseEntity<PackageParseResult> parseDirectory(
            @Parameter(description = "Directory path")
            @RequestBody Map<String, String> request) {

        return ResponseEntity.badRequest()
                .body(PackageParseResult.failure(null, LOCAL_DIRECTORY_API_DISABLED));
    }

    // ==================== Installation ====================

    @PostMapping("/{packageId}/install")
    @Operation(summary = "Install package",
            description = "Install a previously parsed plugin package")
    public ResponseEntity<PackageInstallResult> install(
            @Parameter(description = "Package ID from parse result")
            @PathVariable String packageId,
            @Parameter(description = "Installation options")
            @RequestBody(required = false) PackageInstallOptions options) {

        log.info("Installing plugin package: {}", packageId);
        if (options == null) {
            options = new PackageInstallOptions();
        }
        PackageInstallResult result = packageService.install(packageId, options);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and install package",
            description = "Upload, parse and install a plugin package in one operation")
    public ResponseEntity<PackageInstallResult> uploadAndInstall(
            @Parameter(description = "Plugin package file")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Skip config component")
            @RequestParam(value = "skipConfig", defaultValue = "false") boolean skipConfig,
            @Parameter(description = "Skip backend component")
            @RequestParam(value = "skipBackend", defaultValue = "false") boolean skipBackend,
            @Parameter(description = "Skip frontend component")
            @RequestParam(value = "skipFrontend", defaultValue = "false") boolean skipFrontend,
            @Parameter(description = "Force overwrite existing plugin")
            @RequestParam(value = "forceOverwrite", defaultValue = "false") boolean forceOverwrite,
            @Parameter(description = "Auto-enable after installation")
            @RequestParam(value = "autoEnable", defaultValue = "true") boolean autoEnable) {

        log.info("Uploading and installing plugin package: {}", file.getOriginalFilename());

        PackageInstallOptions options = PackageInstallOptions.builder()
                .skipConfig(skipConfig)
                .skipBackend(skipBackend)
                .skipFrontend(skipFrontend)
                .forceOverwrite(forceOverwrite)
                .conflictStrategy(forceOverwrite
                        ? PackageInstallOptions.ConflictStrategy.OVERWRITE
                        : PackageInstallOptions.ConflictStrategy.FAIL)
                .autoEnable(autoEnable)
                .build();

        PackageInstallResult result = packageService.installFromFile(file, options);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/install", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Upload and install package (stream)",
            description = "Upload and install a plugin package as octet-stream from BFF")
    public ResponseEntity<PackageInstallResult> uploadAndInstallStream(
            @Parameter(description = "Filename from BFF")
            @RequestHeader(value = "X-Filename", required = false) String filename,
            @Parameter(description = "Skip config component")
            @RequestHeader(value = "X-Skip-Config", defaultValue = "false") boolean skipConfig,
            @Parameter(description = "Skip backend component")
            @RequestHeader(value = "X-Skip-Backend", defaultValue = "false") boolean skipBackend,
            @Parameter(description = "Skip frontend component")
            @RequestHeader(value = "X-Skip-Frontend", defaultValue = "false") boolean skipFrontend,
            @Parameter(description = "Force overwrite existing plugin")
            @RequestHeader(value = "X-Force-Overwrite", defaultValue = "false") boolean forceOverwrite,
            @Parameter(description = "Auto-enable after installation")
            @RequestHeader(value = "X-Auto-Enable", defaultValue = "true") boolean autoEnable,
            java.io.InputStream inputStream) {

        String decodedFilename = "unknown.zip";
        if (filename != null && !filename.isBlank()) {
            try {
                decodedFilename = java.net.URLDecoder.decode(filename, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                decodedFilename = filename;
            }
        }
        // Sanitize filename: strip path separators to prevent path traversal
        decodedFilename = Paths.get(decodedFilename).getFileName().toString();

        log.info("Uploading and installing plugin package (stream): {}", decodedFilename);

        PackageInstallOptions options = PackageInstallOptions.builder()
                .skipConfig(skipConfig)
                .skipBackend(skipBackend)
                .skipFrontend(skipFrontend)
                .forceOverwrite(forceOverwrite)
                .conflictStrategy(forceOverwrite
                        ? PackageInstallOptions.ConflictStrategy.OVERWRITE
                        : PackageInstallOptions.ConflictStrategy.FAIL)
                .autoEnable(autoEnable)
                .build();

        PackageInstallResult result = packageService.installFromStream(inputStream, decodedFilename, options);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/install-path")
    @Operation(summary = "Install from path",
            description = "Install a plugin package from a local path")
    public ResponseEntity<PackageInstallResult> installFromPath(
            @Parameter(description = "Path and installation options")
            @RequestBody InstallFromPathRequest request) {

        return ResponseEntity.badRequest()
                .body(PackageInstallResult.failure(null, LOCAL_INSTALL_PATH_API_DISABLED));
    }

    // ==================== Uninstallation ====================

    @PostMapping("/{pluginPid}/uninstall")
    @Operation(summary = "Uninstall plugin",
            description = "Uninstall a plugin package and optionally remove its resources")
    public ResponseEntity<PackageUninstallResult> uninstall(
            @Parameter(description = "Plugin PID to uninstall")
            @PathVariable String pluginPid,
            @Parameter(description = "Uninstallation options")
            @RequestBody(required = false) PackageUninstallOptions options) {

        log.info("Uninstalling plugin: {}", pluginPid);
        if (options == null) {
            options = new PackageUninstallOptions();
        }
        PackageUninstallResult result = packageService.uninstall(pluginPid, options);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{pluginPid}/uninstall/preview")
    @Operation(summary = "Preview uninstallation",
            description = "Preview what resources will be affected by uninstallation")
    public ResponseEntity<UninstallPreviewResult> getUninstallPreview(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid) {

        log.info("Getting uninstall preview for plugin: {}", pluginPid);
        UninstallPreviewResult result = packageService.getUninstallPreview(pluginPid);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    // ==================== Rollback ====================

    @PostMapping("/{packageId}/rollback")
    @Operation(summary = "Rollback installation",
            description = "Rollback a previous installation")
    public ResponseEntity<PackageUninstallResult> rollback(
            @Parameter(description = "Package history ID to rollback")
            @PathVariable String packageId) {

        log.info("Rolling back package installation: {}", packageId);
        PackageUninstallResult result = packageService.rollback(packageId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{packageId}/can-rollback")
    @Operation(summary = "Check rollback eligibility",
            description = "Check if an installation can be rolled back")
    public ResponseEntity<Map<String, Boolean>> canRollback(
            @Parameter(description = "Package history ID")
            @PathVariable String packageId) {

        boolean canRollback = packageService.canRollback(packageId);
        return ResponseEntity.ok(Map.of("canRollback", canRollback));
    }

    // ==================== Status & History ====================

    @GetMapping("/{pluginPid}/status")
    @Operation(summary = "Get package status",
            description = "Get the current status of a plugin package including component statuses")
    public ResponseEntity<PackageStatusDTO> getStatus(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid) {

        PackageStatusDTO status = packageService.getStatus(pluginPid);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status")
    @Operation(summary = "Get status by plugin ID",
            description = "Get package status by plugin ID instead of PID")
    public ResponseEntity<PackageStatusDTO> getStatusByPluginId(
            @Parameter(description = "Plugin ID")
            @RequestParam String pluginId) {

        PackageStatusDTO status = packageService.getStatusByPluginId(pluginId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/history")
    @Operation(summary = "Get installation history",
            description = "Get recent plugin package installation history")
    public ResponseEntity<List<PackageHistoryDTO>> getHistory(
            @Parameter(description = "Maximum number of records")
            @RequestParam(defaultValue = "20") int limit) {

        List<PackageHistoryDTO> history = packageService.getHistory(limit);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/history/{pluginId}")
    @Operation(summary = "Get plugin history",
            description = "Get installation history for a specific plugin")
    public ResponseEntity<List<PackageHistoryDTO>> getPluginHistory(
            @Parameter(description = "Plugin ID")
            @PathVariable String pluginId) {

        List<PackageHistoryDTO> history = packageService.getPluginHistory(pluginId);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/history/record/{packageId}")
    @Operation(summary = "Get history record",
            description = "Get a specific installation history record")
    public ResponseEntity<PackageHistoryDTO> getHistoryRecord(
            @Parameter(description = "Package history ID")
            @PathVariable String packageId) {

        PackageHistoryDTO record = packageService.getHistoryRecord(packageId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    // ==================== Management ====================

    @PostMapping("/{packageId}/cancel")
    @Operation(summary = "Cancel installation",
            description = "Cancel an in-progress installation")
    public ResponseEntity<Map<String, Boolean>> cancelInstallation(
            @Parameter(description = "Package ID")
            @PathVariable String packageId) {

        log.info("Cancelling installation: {}", packageId);
        boolean cancelled = packageService.cancelInstallation(packageId);
        return ResponseEntity.ok(Map.of("cancelled", cancelled));
    }

    @PostMapping("/cleanup")
    @Operation(summary = "Cleanup temp files",
            description = "Clean up temporary files from incomplete installations")
    public ResponseEntity<Map<String, Integer>> cleanupTempFiles() {
        log.info("Cleaning up temp files");
        int cleaned = packageService.cleanupTempFiles();
        return ResponseEntity.ok(Map.of("cleanedFiles", cleaned));
    }

    // ==================== Request DTOs ====================

    /**
     * Request for installing from a local path.
     */
    @lombok.Data
    public static class InstallFromPathRequest {
        private String path;
        private PackageInstallOptions options;
    }
}
