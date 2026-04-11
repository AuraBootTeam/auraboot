package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.source.PluginSource;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * Service interface for plugin import operations.
 * Supports importing configuration data from JSON or ZIP files.
 */
public interface PluginImportService {

    // ==================== Upload & Parse ====================

    /**
     * Upload and parse a plugin package (JSON or ZIP).
     *
     * @param file the uploaded file
     * @return parsed manifest with preview information
     */
    ImportPreviewResult upload(MultipartFile file);

    /**
     * Parse a plugin manifest from JSON string.
     *
     * @param jsonContent JSON content
     * @param sourceName  source name for tracking
     * @return parsed manifest with preview information
     */
    ImportPreviewResult parseJson(String jsonContent, String sourceName);

    /**
     * Parse a plugin package from input stream.
     *
     * @param inputStream input stream
     * @param fileName    file name for type detection
     * @return parsed manifest with preview information
     */
    ImportPreviewResult parse(InputStream inputStream, String fileName);

    /**
     * Parse a plugin from a directory-based structure.
     *
     * Expected directory structure:
     * <pre>
     * plugin-dir/
     * ├── plugin.json              # Main plugin metadata with resourceDirs
     * ├── models/                  # Model definitions (one file per model)
     * ├── fields/                  # Field definitions (one file per field)
     * ├── bindings/                # Model-field bindings (one file per model, contains array)
     * ├── dicts/                   # Dictionary definitions
     * ├── commands/                # Command definitions
     * ├── menus/                   # Menu definitions
     * ├── permissions/             # Permission definitions
     * ├── roles/                   # Role definitions
     * └── pages/                   # Page DSL definitions
     * </pre>
     *
     * @param directoryPath path to the plugin directory
     * @return parsed manifest with preview information
     */
    ImportPreviewResult parseDirectory(String directoryPath);

    /**
     * Parse a plugin from any PluginSource abstraction.
     * Supports filesystem, URL, S3, and other source types.
     *
     * @param source the plugin source
     * @return parsed manifest with preview information
     * @since 7.2.0
     */
    default ImportPreviewResult parseSource(PluginSource source) {
        throw new UnsupportedOperationException("parseSource not implemented");
    }

    // ==================== Preview ====================

    /**
     * Generate a preview of changes that would be made by the import.
     *
     * @param importId import ID from upload
     * @param request  import options
     * @return preview result with changes
     */
    ImportPreviewResult preview(String importId, ImportRequest request);

    /**
     * Get the current preview result for an import.
     *
     * @param importId import ID
     * @return preview result or null if not found
     */
    ImportPreviewResult getPreview(String importId);

    // ==================== Execute ====================

    /**
     * Execute the import after preview.
     *
     * @param importId import ID from upload/preview
     * @param request  import options
     * @return execution result
     */
    ImportExecuteResult execute(String importId, ImportRequest request);

    /**
     * Generate an import preview from a manifest without executing.
     * Used by marketplace upgrade flow to show diff before confirming.
     *
     * @param manifest the extended manifest
     * @return preview result with changes (importId is removed from cache — caller must not execute)
     */
    ImportPreviewResult previewFromManifest(PluginManifestExtended manifest);

    /**
     * Execute import directly from manifest (skip upload/preview).
     *
     * @param manifest the extended manifest
     * @param request  import options
     * @return execution result
     */
    ImportExecuteResult executeFromManifest(PluginManifestExtended manifest, ImportRequest request);

    // ==================== Rollback ====================

    /**
     * Rollback a successful import.
     *
     * @param importId import ID to rollback
     * @return rollback result
     */
    ImportExecuteResult rollback(String importId);

    /**
     * Check if an import can be rolled back.
     *
     * @param importId import ID
     * @return true if rollback is possible
     */
    boolean canRollback(String importId);

    // ==================== History & Status ====================

    /**
     * Get import history for current tenant.
     *
     * @param limit max number of records to return
     * @return list of import history records
     */
    List<ImportHistoryDTO> getImportHistory(int limit);

    /**
     * Get import history for a specific plugin.
     *
     * @param pluginId plugin ID
     * @return list of import history records
     */
    List<ImportHistoryDTO> getPluginImportHistory(String pluginId);

    /**
     * Get import status by ID.
     *
     * @param importId import ID
     * @return import status or null if not found
     */
    ImportHistoryDTO getImportStatus(String importId);

    /**
     * Cancel an in-progress import.
     *
     * @param importId import ID
     * @return true if cancelled successfully
     */
    boolean cancelImport(String importId);

    // ==================== Validation ====================

    /**
     * Validate a manifest without importing.
     *
     * @param manifest the manifest to validate
     * @return list of validation errors (empty if valid)
     */
    List<String> validateManifest(PluginManifestExtended manifest);

    /**
     * Check for resource conflicts.
     *
     * @param manifest the manifest to check
     * @return list of conflicts
     */
    List<ImportPreviewResult.ResourceConflict> checkConflicts(PluginManifestExtended manifest);

    /**
     * Analyze dependencies.
     *
     * @param manifest the manifest to analyze
     * @return dependency analysis result
     */
    ImportPreviewResult.DependencyAnalysis analyzeDependencies(PluginManifestExtended manifest);

    // ==================== DTO ====================

    /**
     * Import history DTO for API responses.
     */
    record ImportHistoryDTO(
            String importId,
            String pluginPid,
            String pluginId,
            String namespace,
            String version,
            String status,
            String importType,
            String sourceType,
            String sourceName,
            java.time.Instant startedAt,
            java.time.Instant completedAt,
            String errorMessage,
            java.util.Map<String, Integer> resourceCounts
    ) {}
}
