package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.packages.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

/**
 * Service for unified plugin package management.
 *
 * <p>This service orchestrates the installation and uninstallation of unified plugin packages
 * that may contain multiple components:
 * <ul>
 *   <li>Config - DSL configuration resources (models, fields, pages, commands)</li>
 *   <li>Backend - PF4J JAR plugin for server-side extensions</li>
 *   <li>Frontend - Module Federation bundle for client-side components</li>
 * </ul>
 *
 * <p>Installation order: config → backend → frontend
 * <p>Uninstallation order: frontend → backend → config
 *
 * @see PackageManifest
 * @see PackageParseResult
 * @see PackageInstallResult
 */
public interface PluginPackageService {

    // ==================== Upload & Parse ====================

    /**
     * Upload and parse a unified plugin package.
     *
     * <p>Supported formats:
     * <ul>
     *   <li>ZIP file containing plugin.json and component directories</li>
     *   <li>JSON file containing only configuration manifest</li>
     * </ul>
     *
     * @param file the uploaded file
     * @return parse result with package ID and detected components
     */
    PackageParseResult parsePackage(MultipartFile file);

    /**
     * Parse a plugin package from a local path.
     *
     * @param path path to the package file or directory
     * @return parse result with package ID and detected components
     */
    PackageParseResult parsePackageFromPath(Path path);

    /**
     * Parse a plugin package from an input stream.
     * Used for BFF forwarding where file is streamed directly.
     *
     * @param inputStream input stream containing package data
     * @param filename original filename
     * @return parse result with package ID and detected components
     */
    PackageParseResult parsePackageFromStream(java.io.InputStream inputStream, String filename);

    /**
     * Parse a plugin package from a directory structure.
     *
     * @param directoryPath path to the package directory
     * @return parse result with package ID and detected components
     */
    PackageParseResult parsePackageFromDirectory(Path directoryPath);

    // ==================== Installation ====================

    /**
     * Install a previously parsed package.
     *
     * <p>Installation is performed in order:
     * <ol>
     *   <li>Config component - Import DSL resources</li>
     *   <li>Backend component - Load PF4J JAR plugin</li>
     *   <li>Frontend component - Deploy Module Federation assets</li>
     * </ol>
     *
     * <p>If any component fails, previous components are rolled back.
     *
     * @param packageId package ID from parse result
     * @param options installation options
     * @return installation result with component statuses
     */
    PackageInstallResult install(String packageId, PackageInstallOptions options);

    /**
     * Install a package directly from a file.
     * Combines parse and install in a single operation.
     *
     * @param file the uploaded file
     * @param options installation options
     * @return installation result
     */
    PackageInstallResult installFromFile(MultipartFile file, PackageInstallOptions options);

    /**
     * Install a package directly from a path.
     *
     * @param path path to the package
     * @param options installation options
     * @return installation result
     */
    PackageInstallResult installFromPath(Path path, PackageInstallOptions options);

    /**
     * Install a package directly from an input stream.
     * Used for BFF forwarding.
     *
     * @param inputStream input stream containing package data
     * @param filename original filename
     * @param options installation options
     * @return installation result
     */
    PackageInstallResult installFromStream(java.io.InputStream inputStream, String filename, PackageInstallOptions options);

    // ==================== Uninstallation ====================

    /**
     * Uninstall a plugin package.
     *
     * <p>Uninstallation is performed in reverse order:
     * <ol>
     *   <li>Frontend component - Remove deployed assets, notify clients</li>
     *   <li>Backend component - Stop and unload PF4J plugin</li>
     *   <li>Config component - Delete or detach resources</li>
     * </ol>
     *
     * @param pluginPid plugin PID to uninstall
     * @param options uninstallation options
     * @return uninstallation result
     */
    PackageUninstallResult uninstall(String pluginPid, PackageUninstallOptions options);

    /**
     * Get uninstallation preview.
     *
     * @param pluginPid plugin PID
     * @return preview of resources that would be affected
     */
    com.auraboot.framework.plugin.dto.uninstall.UninstallPreviewResult getUninstallPreview(String pluginPid);

    // ==================== Rollback ====================

    /**
     * Rollback a recent installation.
     *
     * @param packageId package history ID to rollback
     * @return uninstall result
     */
    PackageUninstallResult rollback(String packageId);

    /**
     * Check if an installation can be rolled back.
     *
     * @param packageId package history ID
     * @return true if rollback is possible
     */
    boolean canRollback(String packageId);

    // ==================== Status & History ====================

    /**
     * Get package status for a plugin.
     *
     * @param pluginPid plugin PID
     * @return package status with component details
     */
    PackageStatusDTO getStatus(String pluginPid);

    /**
     * Get package status by plugin ID.
     *
     * @param pluginId plugin ID
     * @return package status or null if not found
     */
    PackageStatusDTO getStatusByPluginId(String pluginId);

    /**
     * Get installation history.
     *
     * @param limit maximum number of records
     * @return list of history records
     */
    List<PackageHistoryDTO> getHistory(int limit);

    /**
     * Get installation history for a specific plugin.
     *
     * @param pluginId plugin ID
     * @return list of history records for the plugin
     */
    List<PackageHistoryDTO> getPluginHistory(String pluginId);

    /**
     * Get a specific history record.
     *
     * @param packageId package history ID
     * @return history record or null if not found
     */
    PackageHistoryDTO getHistoryRecord(String packageId);

    // ==================== Cleanup ====================

    /**
     * Clean up temporary files from incomplete installations.
     *
     * @return number of files cleaned
     */
    int cleanupTempFiles();

    /**
     * Cancel an in-progress installation.
     *
     * @param packageId package ID
     * @return true if cancelled successfully
     */
    boolean cancelInstallation(String packageId);
}
