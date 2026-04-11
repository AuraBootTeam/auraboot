package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.dto.packages.*;
import com.auraboot.framework.plugin.dto.uninstall.UninstallPreviewResult;
import com.auraboot.framework.plugin.entity.PluginPackageHistory;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.service.PluginSignatureVerifier;
import com.auraboot.framework.plugin.mapper.PluginPackageHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.service.PluginPackageService;
import com.auraboot.framework.plugin.service.PluginResourceService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Implementation of unified plugin package service.
 *
 * <p>This service orchestrates the installation and uninstallation of unified plugin packages
 * that may contain:
 * <ul>
 *   <li>Config - DSL configuration resources</li>
 *   <li>Backend - PF4J JAR plugin</li>
 *   <li>Frontend - Module Federation bundle</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginPackageServiceImpl implements PluginPackageService {

    private final PluginPackageHistoryMapper packageHistoryMapper;
    private final PluginRecordMapper pluginRecordMapper;
    private final PluginResourceMapper pluginResourceMapper;
    private final PluginImportService pluginImportService;
    private final PluginResourceService pluginResourceService;
    private final AuraPluginManager auraPluginManager;
    private final ExtensionRegistry extensionRegistry;
    private final PlatformTransactionManager transactionManager;
    private final PluginDirectoryLoader directoryLoader;
    private final PluginSignatureVerifier signatureVerifier;

    @Value("${aura.plugins.dir:plugins}")
    private String pluginsDir;

    @Value("${aura.plugins.frontend.dir:frontend-plugins}")
    private String frontendPluginsDir;

    @Value("${aura.plugins.temp.dir:${java.io.tmpdir}/aura-plugins}")
    private String tempDir;

    private final ObjectMapper objectMapper = createObjectMapper();

    // Cache for in-progress package operations
    private final Map<String, PackageContext> packageContextCache = new ConcurrentHashMap<>();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    // ==================== Upload & Parse ====================

    @Override
    public PackageParseResult parsePackage(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            fileName = "unknown.zip";
        }

        String packageId = UlidGenerator.generate();

        try {
            // Create temp directory for extraction
            Path extractPath = createTempDirectory(packageId);

            // Extract or save file
            if (fileName.toLowerCase().endsWith(".zip")) {
                extractZip(file.getInputStream(), extractPath);
            } else if (fileName.toLowerCase().endsWith(".json")) {
                // Single JSON manifest - create a config-only package structure
                Files.write(extractPath.resolve("plugin.json"), file.getBytes());
            } else {
                return PackageParseResult.failure(packageId, "Unsupported file format. Use .zip or .json");
            }

            return parsePackageFromDirectory(extractPath);

        } catch (IOException e) {
            log.error("Failed to parse package: {}", e.getMessage(), e);
            return PackageParseResult.failure(packageId, "Failed to parse package: " + e.getMessage());
        }
    }

    @Override
    public PackageParseResult parsePackageFromPath(Path path) {
        if (Files.isDirectory(path)) {
            return parsePackageFromDirectory(path);
        }

        String packageId = UlidGenerator.generate();

        try {
            Path extractPath = createTempDirectory(packageId);

            String fileName = path.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".zip")) {
                try (InputStream is = Files.newInputStream(path)) {
                    extractZip(is, extractPath);
                }
            } else if (fileName.endsWith(".json")) {
                Files.copy(path, extractPath.resolve("plugin.json"));
            } else {
                return PackageParseResult.failure(packageId, "Unsupported file format");
            }

            return parsePackageFromDirectory(extractPath);

        } catch (IOException e) {
            log.error("Failed to parse package from path: {}", e.getMessage(), e);
            return PackageParseResult.failure(packageId, "Failed to parse package: " + e.getMessage());
        }
    }

    @Override
    public PackageParseResult parsePackageFromStream(InputStream inputStream, String filename) {
        String packageId = UlidGenerator.generate();

        try {
            Path extractPath = createTempDirectory(packageId);

            String fileNameLower = filename != null ? filename.toLowerCase() : "";
            if (fileNameLower.endsWith(".zip")) {
                extractZip(inputStream, extractPath);
            } else if (fileNameLower.endsWith(".json")) {
                Files.copy(inputStream, extractPath.resolve("plugin.json"));
            } else {
                return PackageParseResult.failure(packageId, "Unsupported file format. Use .zip or .json");
            }

            return parsePackageFromDirectory(extractPath);

        } catch (IOException e) {
            log.error("Failed to parse package from stream: {}", e.getMessage(), e);
            return PackageParseResult.failure(packageId, "Failed to parse package: " + e.getMessage());
        }
    }

    @Override
    public PackageParseResult parsePackageFromDirectory(Path directoryPath) {
        String packageId = UlidGenerator.generate();
        Long tenantId = MetaContext.getCurrentTenantId();

        try {
            // Find and parse plugin.json
            Path manifestPath = directoryPath.resolve("plugin.json");
            if (!Files.exists(manifestPath)) {
                return PackageParseResult.failure(packageId, "Missing plugin.json manifest");
            }

            String manifestJson = Files.readString(manifestPath, StandardCharsets.UTF_8);
            PackageManifest manifest = objectMapper.readValue(manifestJson, PackageManifest.class);

            // Validate manifest
            List<String> validationErrors = validateManifest(manifest);
            if (!validationErrors.isEmpty()) {
                return PackageParseResult.validationFailure(packageId, validationErrors);
            }

            // Verify package signature (RSA-SHA256)
            signatureVerifier.verify(directoryPath);

            // Detect components
            PackageParseResult.DetectedComponents detected = detectComponents(directoryPath, manifest);

            // Check for conflicts
            List<PackageParseResult.ResourceConflict> conflicts = checkConflicts(manifest, directoryPath, tenantId);

            // Create history record
            PluginPackageHistory history = PluginPackageHistory.builder()
                    .pid(packageId)
                    .tenantId(tenantId)
                    .pluginId(manifest.getPluginId())
                    .namespace(manifest.getNamespace())
                    .version(manifest.getVersion())
                    .displayName(manifest.getDisplayName())
                    .sourceType("upload")
                    .sourceName(directoryPath.getFileName().toString())
                    .packagePath(directoryPath.toString())
                    .configEnabled(detected.isHasConfig())
                    .backendEnabled(detected.isHasBackend())
                    .frontendEnabled(detected.isHasFrontend())
                    .status(PluginPackageHistory.PackageStatus.PENDING.code())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .createdBy(MetaContext.getCurrentUserId())
                    .build();

            packageHistoryMapper.insert(history);

            // Build result
            PackageParseResult result = PackageParseResult.builder()
                    .packageId(packageId)
                    .success(true)
                    .manifest(manifest)
                    .extractedPath(directoryPath.toString())
                    .detectedComponents(detected)
                    .conflicts(conflicts)
                    .build();

            // Cache context for installation
            packageContextCache.put(packageId, new PackageContext(packageId, manifest, directoryPath, history, detected));

            log.info("Successfully parsed package: {} v{}", manifest.getPluginId(), manifest.getVersion());
            return result;

        } catch (Exception e) {
            log.error("Failed to parse package from directory: {}", e.getMessage(), e);
            return PackageParseResult.failure(packageId, "Failed to parse package: " + e.getMessage());
        }
    }

    // ==================== Installation ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PackageInstallResult install(String packageId, PackageInstallOptions options) {
        PackageContext context = packageContextCache.get(packageId);
        if (context == null) {
            return PackageInstallResult.failure(packageId, "Package not found. Please upload again.");
        }

        return doInstall(context, options);
    }

    @Override
    public PackageInstallResult installFromFile(MultipartFile file, PackageInstallOptions options) {
        PackageParseResult parseResult = parsePackage(file);
        if (!parseResult.isSuccess()) {
            return PackageInstallResult.failure(parseResult.getPackageId(), parseResult.getError());
        }
        return install(parseResult.getPackageId(), options);
    }

    @Override
    public PackageInstallResult installFromPath(Path path, PackageInstallOptions options) {
        PackageParseResult parseResult = parsePackageFromPath(path);
        if (!parseResult.isSuccess()) {
            return PackageInstallResult.failure(parseResult.getPackageId(), parseResult.getError());
        }
        return install(parseResult.getPackageId(), options);
    }

    @Override
    public PackageInstallResult installFromStream(InputStream inputStream, String filename, PackageInstallOptions options) {
        PackageParseResult parseResult = parsePackageFromStream(inputStream, filename);
        if (!parseResult.isSuccess()) {
            return PackageInstallResult.failure(parseResult.getPackageId(), parseResult.getError());
        }
        return install(parseResult.getPackageId(), options);
    }

    private PackageInstallResult doInstall(PackageContext context, PackageInstallOptions options) {
        String packageId = context.getPackageId();
        PackageManifest manifest = context.getManifest();
        Path extractPath = context.getExtractPath();
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant startTime = Instant.now();

        PackageInstallResult result = PackageInstallResult.builder()
                .packageId(packageId)
                .pluginId(manifest.getPluginId())
                .version(manifest.getVersion())
                .startedAt(startTime)
                .build();

        // Rollback data for recovery
        Map<String, Object> rollbackData = new HashMap<>();

        try {
            // Update status to parsing
            updatePackageStatus(packageId, PluginPackageHistory.PackageStatus.PARSING);

            // Dry run check
            if (options.isDryRun()) {
                result.setSuccess(true);
                result.setCompletedAt(Instant.now());
                return result;
            }

            // Create or update plugin record
            String pluginPid = createOrUpdatePluginRecord(manifest, tenantId, context.getDetected());
            result.setPluginPid(pluginPid);
            rollbackData.put("pluginPid", pluginPid);

            // 1. Install Config Component
            if (context.getDetected().isHasConfig() && !options.isSkipConfig()) {
                updatePackageStatus(packageId, PluginPackageHistory.PackageStatus.INSTALLING_CONFIG);
                PackageInstallResult.ComponentResult configResult = installConfigComponent(
                        context, options, pluginPid, tenantId);
                result.setConfigResult(configResult);
                rollbackData.put("createdResourcePids", configResult.getCreatedResourcePids());

                if (configResult.getStatus() == PackageInstallResult.ComponentStatus.FAILED) {
                    throw new PluginException("Config installation failed: " + configResult.getError());
                }
            } else {
                result.setConfigResult(createSkippedResult());
            }

            // 2. Install Backend Component
            if (context.getDetected().isHasBackend() && !options.isSkipBackend()) {
                updatePackageStatus(packageId, PluginPackageHistory.PackageStatus.INSTALLING_BACKEND);
                PackageInstallResult.ComponentResult backendResult = installBackendComponent(
                        context, options, pluginPid);
                result.setBackendResult(backendResult);
                rollbackData.put("backendPluginId", backendResult.getBackendPluginId());

                if (backendResult.getStatus() == PackageInstallResult.ComponentStatus.FAILED) {
                    // Rollback config if backend fails
                    rollbackConfig(rollbackData);
                    throw new PluginException("Backend installation failed: " + backendResult.getError());
                }
            } else {
                result.setBackendResult(createSkippedResult());
            }

            // 3. Install Frontend Component
            if (context.getDetected().isHasFrontend() && !options.isSkipFrontend()) {
                updatePackageStatus(packageId, PluginPackageHistory.PackageStatus.INSTALLING_FRONTEND);
                PackageInstallResult.ComponentResult frontendResult = installFrontendComponent(
                        context, options, pluginPid);
                result.setFrontendResult(frontendResult);
                rollbackData.put("frontendAssets", frontendResult.getDeployedAssets());

                if (frontendResult.getStatus() == PackageInstallResult.ComponentStatus.FAILED) {
                    // Rollback backend and config if frontend fails
                    rollbackBackend(rollbackData);
                    rollbackConfig(rollbackData);
                    throw new PluginException("Frontend installation failed: " + frontendResult.getError());
                }
            } else {
                result.setFrontendResult(createSkippedResult());
            }

            // Mark as success
            result.setSuccess(true);
            result.setCanRollback(true);
            result.setRollbackData(rollbackData);
            result.setCompletedAt(Instant.now());
            result.setDurationMs(result.getCompletedAt().toEpochMilli() - startTime.toEpochMilli());

            // Update history
            packageHistoryMapper.markSuccess(packageId, pluginPid);

            log.info("Successfully installed package: {} v{} (pid={})",
                    manifest.getPluginId(), manifest.getVersion(), pluginPid);

        } catch (Exception e) {
            log.error("Package installation failed: {}", e.getMessage(), e);

            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setCompletedAt(Instant.now());
            result.setDurationMs(result.getCompletedAt().toEpochMilli() - startTime.toEpochMilli());

            // Update history with failure
            updatePackageStatusWithError(packageId, PluginPackageHistory.PackageStatus.FAILED, e.getMessage());

        } finally {
            // Clean up cache
            packageContextCache.remove(packageId);
        }

        return result;
    }

    private PackageInstallResult.ComponentResult installConfigComponent(
            PackageContext context, PackageInstallOptions options, String pluginPid, Long tenantId) {

        PackageInstallResult.ComponentResult result = new PackageInstallResult.ComponentResult();

        try {
            PackageManifest manifest = context.getManifest();
            Path extractPath = context.getExtractPath();

            // Build extended manifest for config import
            PluginManifestExtended extendedManifest = buildExtendedManifest(manifest, extractPath);

            // Determine conflict strategy: manifest importOptions takes precedence over options
            ImportRequest.ConflictStrategy conflictStrategy = resolveConflictStrategy(extendedManifest, options);

            // Convert options — all autoPublish flags default to true for package installs
            ImportRequest importRequest = ImportRequest.builder()
                    .conflictStrategy(conflictStrategy)
                    .autoPublishModels(true)
                    .autoPublishFields(true)
                    .autoPublishCommands(true)
                    .autoPublishPages(true)
                    .autoDeployProcesses(true)
                    .build();

            // Execute import
            ImportExecuteResult importResult = pluginImportService.executeFromManifest(extendedManifest, importRequest);

            result.setStatus(importResult.isSuccess()
                    ? PackageInstallResult.ComponentStatus.SUCCESS
                    : PackageInstallResult.ComponentStatus.FAILED);
            result.setResourceCounts(importResult.getResourceCounts() != null ?
                    flattenStringResourceCounts(importResult.getResourceCounts()) : null);
            result.setCreatedResourcePids(importResult.getCreatedResources() != null ?
                    flattenStringCreatedResources(importResult.getCreatedResources()) : null);

            if (!importResult.isSuccess()) {
                result.setError(importResult.getErrorMessage());
                result.setStackTrace(importResult.getErrorDetail());
            }

            // Update plugin record with config status
            updatePluginConfigStatus(pluginPid, result.getStatus().code(), result.getError());

            // Update package history
            updatePackageConfigStatus(context.getPackageId(), result.getStatus().code(),
                    result.getError(), result.getResourceCounts());

        } catch (Exception e) {
            log.error("Config component installation failed", e);
            result.setStatus(PackageInstallResult.ComponentStatus.FAILED);
            result.setError(e.getMessage());
            result.setStackTrace(getStackTrace(e));
        }

        return result;
    }

    private PackageInstallResult.ComponentResult installBackendComponent(
            PackageContext context, PackageInstallOptions options, String pluginPid) {

        PackageInstallResult.ComponentResult result = new PackageInstallResult.ComponentResult();

        try {
            Path jarPath = Paths.get(context.getDetected().getBackendJarPath());
            if (!Files.exists(jarPath)) {
                throw new PluginException("Backend JAR not found: " + jarPath);
            }

            // Copy JAR to plugins directory
            Path targetPath = getPluginsPath().resolve(jarPath.getFileName());
            Files.copy(jarPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            result.setDeployedAssets(List.of(targetPath.toString()));

            // Hot-load the plugin
            String backendPluginId = auraPluginManager.hotLoadPlugin(targetPath);
            if (backendPluginId == null) {
                throw new PluginException("Failed to load backend plugin");
            }

            result.setBackendPluginId(backendPluginId);
            result.setBackendPluginState(auraPluginManager.getPluginState(backendPluginId).name());
            result.setStatus(PackageInstallResult.ComponentStatus.SUCCESS);

            // Refresh extension registry to discover new extensions
            extensionRegistry.refreshAllCaches();
            log.info("Extension registry refreshed after loading plugin: {}", backendPluginId);

            // Update plugin record
            updatePluginBackendStatus(pluginPid, backendPluginId, "started", null);

            // Update package history
            updatePackageBackendStatus(context.getPackageId(), "success", null, targetPath.toString());

        } catch (Exception e) {
            log.error("Backend component installation failed", e);
            result.setStatus(PackageInstallResult.ComponentStatus.FAILED);
            result.setError(e.getMessage());
            result.setStackTrace(getStackTrace(e));
        }

        return result;
    }

    private PackageInstallResult.ComponentResult installFrontendComponent(
            PackageContext context, PackageInstallOptions options, String pluginPid) {

        PackageInstallResult.ComponentResult result = new PackageInstallResult.ComponentResult();

        try {
            PackageManifest manifest = context.getManifest();
            Path frontendPath = Paths.get(context.getDetected().getFrontendPath());

            if (!Files.exists(frontendPath)) {
                throw new PluginException("Frontend path not found: " + frontendPath);
            }

            // Determine deployment path
            Path deployPath = getFrontendPluginsPath().resolve(manifest.getNamespace());
            Files.createDirectories(deployPath);

            // Copy frontend assets
            List<String> deployedAssets = new ArrayList<>();
            try (Stream<Path> files = Files.walk(frontendPath)) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    Path relative = frontendPath.relativize(source);
                    Path target = deployPath.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    deployedAssets.add(target.toString());
                }
            }

            // Build remote URL
            String remoteUrl = buildFrontendRemoteUrl(manifest.getNamespace());

            result.setFrontendRemoteUrl(remoteUrl);
            result.setDeployedAssets(deployedAssets);
            result.setStatus(PackageInstallResult.ComponentStatus.SUCCESS);

            // Update plugin record
            updatePluginFrontendStatus(pluginPid, remoteUrl, "deployed", null);

            // Update package history
            updatePackageFrontendStatus(context.getPackageId(), "success", null, remoteUrl);

        } catch (Exception e) {
            log.error("Frontend component installation failed", e);
            result.setStatus(PackageInstallResult.ComponentStatus.FAILED);
            result.setError(e.getMessage());
            result.setStackTrace(getStackTrace(e));
        }

        return result;
    }

    // ==================== Uninstallation ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PackageUninstallResult uninstall(String pluginPid, PackageUninstallOptions options) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant startTime = Instant.now();

        PluginRecord plugin = pluginRecordMapper.findByPid(pluginPid);
        if (plugin == null) {
            return PackageUninstallResult.failure(pluginPid, null, "Plugin not found");
        }

        PackageUninstallResult result = PackageUninstallResult.builder()
                .pluginPid(pluginPid)
                .pluginId(plugin.getPluginId())
                .startedAt(startTime)
                .build();

        try {
            // 1. Uninstall Frontend (reverse order)
            if (Boolean.TRUE.equals(plugin.getHasFrontend()) && !options.isSkipFrontend()) {
                PackageUninstallResult.ComponentUninstallResult frontendResult = uninstallFrontendComponent(plugin, options);
                result.setFrontendResult(frontendResult);
            }

            // 2. Uninstall Backend
            if (Boolean.TRUE.equals(plugin.getHasBackend()) && !options.isSkipBackend()) {
                PackageUninstallResult.ComponentUninstallResult backendResult = uninstallBackendComponent(plugin, options);
                result.setBackendResult(backendResult);
            }

            // 3. Uninstall Config
            if (Boolean.TRUE.equals(plugin.getHasConfig()) && !options.isSkipConfig()) {
                PackageUninstallResult.ComponentUninstallResult configResult = uninstallConfigComponent(plugin, options);
                result.setConfigResult(configResult);
            }

            // Mark plugin as deleted
            pluginRecordMapper.softDelete(pluginPid);

            result.setSuccess(true);
            result.setCompletedAt(Instant.now());
            result.setDurationMs(result.getCompletedAt().toEpochMilli() - startTime.toEpochMilli());

            log.info("Successfully uninstalled plugin: {} (pid={})", plugin.getPluginId(), pluginPid);

        } catch (Exception e) {
            log.error("Plugin uninstallation failed: {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setCompletedAt(Instant.now());
        }

        return result;
    }

    @Override
    public UninstallPreviewResult getUninstallPreview(String pluginPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return pluginResourceService.generateUninstallPreview(pluginPid, tenantId);
    }

    private PackageUninstallResult.ComponentUninstallResult uninstallFrontendComponent(
            PluginRecord plugin, PackageUninstallOptions options) {

        PackageUninstallResult.ComponentUninstallResult result =
                new PackageUninstallResult.ComponentUninstallResult();

        try {
            List<String> removedFiles = new ArrayList<>();

            if (options.isRemoveFrontendAssets() && plugin.getFrontendRemoteUrl() != null) {
                Path deployPath = getFrontendPluginsPath().resolve(plugin.getNamespace());
                if (Files.exists(deployPath)) {
                    try (Stream<Path> files = Files.walk(deployPath)) {
                        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(file);
                            removedFiles.add(file.toString());
                        }
                    }
                }
            }

            result.setStatus(PackageUninstallResult.ComponentStatus.SUCCESS);
            result.setRemovedFiles(removedFiles);

        } catch (Exception e) {
            log.error("Frontend uninstallation failed", e);
            result.setStatus(PackageUninstallResult.ComponentStatus.FAILED);
            result.setError(e.getMessage());
        }

        return result;
    }

    private PackageUninstallResult.ComponentUninstallResult uninstallBackendComponent(
            PluginRecord plugin, PackageUninstallOptions options) {

        PackageUninstallResult.ComponentUninstallResult result =
                new PackageUninstallResult.ComponentUninstallResult();

        try {
            List<String> removedFiles = new ArrayList<>();

            if (plugin.getBackendPluginId() != null) {
                // Unload plugin
                boolean unloaded = auraPluginManager.hotUnloadPlugin(plugin.getBackendPluginId());
                if (!unloaded) {
                    log.warn("Failed to unload backend plugin: {}", plugin.getBackendPluginId());
                }

                // Remove JAR file
                if (options.isRemoveBackendJar()) {
                    Path jarPath = getPluginsPath().resolve(plugin.getBackendPluginId() + ".jar");
                    if (Files.exists(jarPath)) {
                        Files.delete(jarPath);
                        removedFiles.add(jarPath.toString());
                    }
                }
            }

            result.setStatus(PackageUninstallResult.ComponentStatus.SUCCESS);
            result.setRemovedFiles(removedFiles);

        } catch (Exception e) {
            log.error("Backend uninstallation failed", e);
            result.setStatus(PackageUninstallResult.ComponentStatus.FAILED);
            result.setError(e.getMessage());
        }

        return result;
    }

    private PackageUninstallResult.ComponentUninstallResult uninstallConfigComponent(
            PluginRecord plugin, PackageUninstallOptions options) {

        PackageUninstallResult.ComponentUninstallResult result =
                new PackageUninstallResult.ComponentUninstallResult();

        try {
            Long tenantId = MetaContext.getCurrentTenantId();

            // Build uninstall request
            com.auraboot.framework.plugin.dto.uninstall.UninstallRequest request =
                    com.auraboot.framework.plugin.dto.uninstall.UninstallRequest.builder()
                            .removeData(options.isRemoveAllData())
                            .force(options.isForce())
                            .decisions(options.getResourceDecisions())
                            .build();

            com.auraboot.framework.plugin.dto.uninstall.UninstallResult uninstallResult =
                    pluginResourceService.executeUninstall(plugin.getPid(), tenantId, request);

            result.setStatus(uninstallResult.isSuccess()
                    ? PackageUninstallResult.ComponentStatus.SUCCESS
                    : PackageUninstallResult.ComponentStatus.FAILED);

            // Convert resource info from string lists to ResourceInfo objects
            List<PackageUninstallResult.ResourceInfo> deleted = new ArrayList<>();
            List<PackageUninstallResult.ResourceInfo> detached = new ArrayList<>();

            if (uninstallResult.getDeletedResources() != null) {
                for (String code : uninstallResult.getDeletedResources()) {
                    deleted.add(PackageUninstallResult.ResourceInfo.builder()
                            .code(code)
                            .build());
                }
            }
            if (uninstallResult.getDetachedResources() != null) {
                for (String code : uninstallResult.getDetachedResources()) {
                    detached.add(PackageUninstallResult.ResourceInfo.builder()
                            .code(code)
                            .build());
                }
            }

            result.setDeletedResources(deleted);
            result.setDetachedResources(detached);
            result.setKeptResources(new ArrayList<>());

            if (!uninstallResult.isSuccess()) {
                result.setError(uninstallResult.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("Config uninstallation failed", e);
            result.setStatus(PackageUninstallResult.ComponentStatus.FAILED);
            result.setError(e.getMessage());
        }

        return result;
    }

    // ==================== Rollback ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PackageUninstallResult rollback(String packageId) {
        PluginPackageHistory history = packageHistoryMapper.findByPid(packageId);
        if (history == null) {
            return PackageUninstallResult.failure(null, null, "Package history not found");
        }

        if (!history.isSuccess()) {
            return PackageUninstallResult.failure(history.getPluginPid(), history.getPluginId(),
                    "Can only rollback successful installations");
        }

        if (!Boolean.TRUE.equals(history.getCanRollback())) {
            return PackageUninstallResult.failure(history.getPluginPid(), history.getPluginId(),
                    "Rollback not available for this installation");
        }

        // Uninstall with full cleanup
        PackageUninstallOptions options = PackageUninstallOptions.builder()
                .removeAllData(true)
                .removeFrontendAssets(true)
                .removeBackendJar(true)
                .build();

        PackageUninstallResult result = uninstall(history.getPluginPid(), options);

        if (result.isSuccess()) {
            packageHistoryMapper.markRolledBack(packageId);
        }

        return result;
    }

    @Override
    public boolean canRollback(String packageId) {
        PluginPackageHistory history = packageHistoryMapper.findByPid(packageId);
        return history != null && history.isSuccess() && Boolean.TRUE.equals(history.getCanRollback());
    }

    // ==================== Status & History ====================

    @Override
    public PackageStatusDTO getStatus(String pluginPid) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pluginPid);
        if (plugin == null) {
            return null;
        }
        return buildStatusDTO(plugin);
    }

    @Override
    public PackageStatusDTO getStatusByPluginId(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        PluginRecord plugin = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        if (plugin == null) {
            return null;
        }
        return buildStatusDTO(plugin);
    }

    @Override
    public List<PackageHistoryDTO> getHistory(int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return packageHistoryMapper.findRecentByTenant(tenantId, limit)
                .stream()
                .map(this::buildHistoryDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<PackageHistoryDTO> getPluginHistory(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        PluginPackageHistory latest = packageHistoryMapper.findLatestByTenantAndPluginId(tenantId, pluginId);
        if (latest == null) {
            return Collections.emptyList();
        }
        return packageHistoryMapper.findByPluginPid(latest.getPluginPid())
                .stream()
                .map(this::buildHistoryDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PackageHistoryDTO getHistoryRecord(String packageId) {
        PluginPackageHistory history = packageHistoryMapper.findByPid(packageId);
        return history != null ? buildHistoryDTO(history) : null;
    }

    // ==================== Cleanup ====================

    @Override
    public int cleanupTempFiles() {
        int cleaned = 0;
        try {
            Path tempPath = Paths.get(tempDir);
            if (Files.exists(tempPath)) {
                try (Stream<Path> dirs = Files.list(tempPath)) {
                    for (Path dir : dirs.toList()) {
                        // Delete directories older than 1 hour
                        if (Files.isDirectory(dir)) {
                            long age = System.currentTimeMillis() - Files.getLastModifiedTime(dir).toMillis();
                            if (age > 3600000) { // 1 hour
                                deleteDirectory(dir);
                                cleaned++;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to cleanup temp files", e);
        }
        return cleaned;
    }

    @Override
    public boolean cancelInstallation(String packageId) {
        PackageContext context = packageContextCache.remove(packageId);
        if (context != null) {
            packageHistoryMapper.updateStatus(packageId, "cancelled");
            return true;
        }
        return false;
    }

    // ==================== Helper Methods ====================

    private Path createTempDirectory(String packageId) throws IOException {
        Path tempPath = Paths.get(tempDir, packageId);
        Files.createDirectories(tempPath);
        return tempPath;
    }

    private void extractZip(InputStream inputStream, Path targetPath) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetPath.resolve(entry.getName());

                // Security check - prevent zip slip attack
                if (!entryPath.normalize().startsWith(targetPath.normalize())) {
                    throw new IOException("Invalid zip entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private List<String> validateManifest(PackageManifest manifest) {
        List<String> errors = new ArrayList<>();

        if (manifest.getPluginId() == null || manifest.getPluginId().isBlank()) {
            errors.add("pluginId is required");
        }
        if (manifest.getNamespace() == null || manifest.getNamespace().isBlank()) {
            errors.add("namespace is required");
        }
        if (manifest.getVersion() == null || manifest.getVersion().isBlank()) {
            errors.add("version is required");
        }

        return errors;
    }

    private PackageParseResult.DetectedComponents detectComponents(Path directoryPath, PackageManifest manifest) {
        PackageParseResult.DetectedComponents detected = new PackageParseResult.DetectedComponents();

        // Check config component
        Path configPath = directoryPath.resolve("config");
        if (!Files.exists(configPath)) {
            configPath = directoryPath; // Config might be in root
        }

        // Check for resourceDirs (JSON files) or directory-based resources
        boolean hasConfigResources = manifest.hasAnyResources() ||
                (manifest.getResourceDirs() != null && !manifest.getResourceDirs().isEmpty()) ||
                Files.exists(configPath.resolve("models.json")) ||
                Files.exists(configPath.resolve("fields.json")) ||
                Files.exists(configPath.resolve("pages.json")) ||
                Files.exists(configPath.resolve("models")) ||
                Files.exists(configPath.resolve("fields")) ||
                Files.exists(configPath.resolve("pages"));

        if (hasConfigResources || (manifest.getComponents() != null &&
                manifest.getComponents().getConfig() != null &&
                Boolean.TRUE.equals(manifest.getComponents().getConfig().getEnabled()))) {
            detected.setHasConfig(true);
            detected.setConfigPath(configPath.toString());

            // Count resources
            Map<String, Integer> counts = countConfigResources(manifest, configPath);
            detected.setConfigResourceCounts(counts);
        }

        // Check backend component
        Path backendPath = directoryPath.resolve("backend");
        Path backendJar = null;
        if (Files.exists(backendPath)) {
            try (Stream<Path> files = Files.list(backendPath)) {
                backendJar = files.filter(f -> f.toString().endsWith(".jar")).findFirst().orElse(null);
            } catch (IOException e) {
                log.warn("Failed to scan backend directory", e);
            }
        }

        if (backendJar != null || (manifest.getComponents() != null &&
                manifest.getComponents().getBackend() != null &&
                Boolean.TRUE.equals(manifest.getComponents().getBackend().getEnabled()))) {
            detected.setHasBackend(true);
            if (backendJar != null) {
                detected.setBackendJarPath(backendJar.toString());
            } else if (manifest.getComponents() != null && manifest.getComponents().getBackend() != null) {
                detected.setBackendJarPath(directoryPath.resolve(
                        manifest.getComponents().getBackend().getPath()).toString());
            }
        }

        // Check frontend component
        Path frontendPath = directoryPath.resolve("frontend");
        boolean hasFrontend = Files.exists(frontendPath) && Files.exists(frontendPath.resolve("remoteEntry.js"));

        if (hasFrontend || (manifest.getComponents() != null &&
                manifest.getComponents().getFrontend() != null &&
                Boolean.TRUE.equals(manifest.getComponents().getFrontend().getEnabled()))) {
            detected.setHasFrontend(true);
            detected.setFrontendPath(frontendPath.toString());

            if (manifest.getComponents() != null && manifest.getComponents().getFrontend() != null) {
                var frontendConfig = manifest.getComponents().getFrontend();
                detected.setFrontendManifestInfo(PackageParseResult.FrontendManifestInfo.builder()
                        .remoteEntryPath(frontendConfig.getRemoteEntry())
                        .exposedModules(frontendConfig.getExposedModules())
                        .slotIds(frontendConfig.getSlots() != null ?
                                frontendConfig.getSlots().stream()
                                        .map(PackageComponentConfig.SlotContribution::getSlotId)
                                        .toList() : null)
                        .routePaths(frontendConfig.getRoutes() != null ?
                                frontendConfig.getRoutes().stream()
                                        .map(PackageComponentConfig.RouteContribution::getPath)
                                        .toList() : null)
                        .build());
            }
        }

        return detected;
    }

    private Map<String, Integer> countConfigResources(PackageManifest manifest, Path configPath) {
        Map<String, Integer> counts = new HashMap<>();

        if (manifest.getModels() != null) counts.put("models", manifest.getModels().size());
        if (manifest.getFields() != null) counts.put("fields", manifest.getFields().size());
        if (manifest.getCommands() != null) counts.put("commands", manifest.getCommands().size());
        if (manifest.getPermissions() != null) counts.put("permissions", manifest.getPermissions().size());
        if (manifest.getRoles() != null) counts.put("roles", manifest.getRoles().size());
        if (manifest.getMenus() != null) counts.put("menus", manifest.getMenus().size());
        if (manifest.getPages() != null) counts.put("pages", manifest.getPages().size());
        if (manifest.getDicts() != null) counts.put("dicts", manifest.getDicts().size());
        if (manifest.getProcesses() != null) counts.put("processes", manifest.getProcesses().size());

        return counts;
    }

    private List<PackageParseResult.ResourceConflict> checkConflicts(
            PackageManifest manifest, Path extractPath, Long tenantId) {
        try {
            PluginManifestExtended extendedManifest = buildExtendedManifest(manifest, extractPath);
            List<ImportPreviewResult.ResourceConflict> importConflicts =
                    pluginImportService.checkConflicts(extendedManifest);
            if (importConflicts == null || importConflicts.isEmpty()) {
                return Collections.emptyList();
            }

            return importConflicts.stream()
                    .map(conflict -> PackageParseResult.ResourceConflict.builder()
                            .resourceType(conflict.getResourceType() != null
                                    ? conflict.getResourceType().name() : null)
                            .resourceCode(conflict.getResourceCode())
                            .existingPluginId(conflict.getOwnerPluginId())
                            .description(conflict.getDescription())
                            .canUpgrade("version_mismatch".equalsIgnoreCase(conflict.getConflictType()))
                            .build())
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to check package conflicts for plugin {}: {}",
                    manifest != null ? manifest.getPluginId() : "unknown", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String createOrUpdatePluginRecord(PackageManifest manifest, Long tenantId,
                                               PackageParseResult.DetectedComponents detected) {
        PluginRecord existing = pluginRecordMapper.findByTenantAndPluginId(manifest.getPluginId());

        // Also check for soft-deleted records by namespace (unique constraint includes soft-deleted rows)
        if (existing == null) {
            existing = pluginRecordMapper.findByTenantAndNamespaceIncludeDeleted(manifest.getNamespace());
        }

        if (existing != null) {
            // Use resurrectPlugin to bypass @TableLogic filter for soft-deleted records
            pluginRecordMapper.resurrectPlugin(
                    existing.getPid(),
                    manifest.getPluginId(),
                    manifest.getNamespace(),
                    manifest.getVersion(),
                    manifest.getDisplayName(),
                    "installed",
                    detected.isHasConfig(),
                    detected.isHasBackend(),
                    detected.isHasFrontend()
            );
            return existing.getPid();
        } else {
            String pid = UlidGenerator.generate();
            // Convert PackageManifest to PluginManifest for storage
            PluginManifest pluginManifest = PluginManifest.builder()
                    .pluginId(manifest.getPluginId())
                    .namespace(manifest.getNamespace())
                    .version(manifest.getVersion())
                    .displayName(manifest.getDisplayName())
                    .description(manifest.getDescription())
                    .author(manifest.getAuthor())
                    .minPlatformVersion(manifest.getMinPlatformVersion())
                    .configSchema(manifest.getConfigSchema())
                    .defaultConfig(manifest.getDefaultConfig())
                    .metadata(manifest.getMetadata())
                    .build();
            PluginRecord record = PluginRecord.builder()
                    .pid(pid)
                    .tenantId(tenantId)
                    .pluginId(manifest.getPluginId())
                    .namespace(manifest.getNamespace())
                    .version(manifest.getVersion())
                    .displayName(manifest.getDisplayName())
                    .description(manifest.getDescription())
                    .author(manifest.getAuthor())
                    .manifest(pluginManifest)
                    .status(StatusConstants.INSTALLED)
                    .hasConfig(detected.isHasConfig())
                    .hasBackend(detected.isHasBackend())
                    .hasFrontend(detected.isHasFrontend())
                    .installedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            pluginRecordMapper.insert(record);
            return pid;
        }
    }

    private PluginManifestExtended buildExtendedManifest(PackageManifest manifest, Path extractPath) {
        // Use directoryLoader to properly load resources from resourceDirs
        return directoryLoader.loadFromDirectory(extractPath);
    }

    private ImportRequest.ConflictStrategy convertConflictStrategy(PackageInstallOptions.ConflictStrategy strategy) {
        if (strategy == null) {
            return ImportRequest.ConflictStrategy.ERROR;
        }
        return switch (strategy) {
            case SKIP -> ImportRequest.ConflictStrategy.SKIP;
            case OVERWRITE -> ImportRequest.ConflictStrategy.OVERWRITE;
            case FAIL -> ImportRequest.ConflictStrategy.ERROR;
        };
    }

    /**
     * Resolve conflict strategy: manifest importOptions takes precedence over install options.
     */
    private ImportRequest.ConflictStrategy resolveConflictStrategy(
            PluginManifestExtended manifest, PackageInstallOptions options) {
        // Check manifest importOptions first
        if (manifest != null && manifest.getImportOptions() != null) {
            String manifestStrategy = manifest.getImportOptions().getConflictStrategy();
            if (manifestStrategy != null && !manifestStrategy.isBlank()) {
                return switch (manifestStrategy.toUpperCase()) {
                    case "skip" -> ImportRequest.ConflictStrategy.SKIP;
                    case "overwrite" -> ImportRequest.ConflictStrategy.OVERWRITE;
                    default -> ImportRequest.ConflictStrategy.ERROR;
                };
            }
        }
        // Fall back to install options
        return convertConflictStrategy(options.getConflictStrategy());
    }

    private PackageInstallResult.ComponentResult createSkippedResult() {
        return PackageInstallResult.ComponentResult.builder()
                .status(PackageInstallResult.ComponentStatus.SKIPPED)
                .build();
    }

    private void rollbackConfig(Map<String, Object> rollbackData) {
        String pluginPid = (String) rollbackData.get("pluginPid");
        Object createdPidsObj = rollbackData.get("createdResourcePids");
        if (createdPidsObj == null) {
            createdPidsObj = rollbackData.get("configImportId");
        }

        List<String> createdResourcePids = toStringList(createdPidsObj);
        if (pluginPid == null || pluginPid.isBlank()) {
            log.warn("Skip config rollback: missing pluginPid in rollback data");
            return;
        }
        if (createdResourcePids.isEmpty()) {
            log.warn("Skip config rollback for plugin {}: no createdResourcePids recorded", pluginPid);
            return;
        }

        Set<String> createdPidSet = new HashSet<>(createdResourcePids);
        List<PluginResource> resources = pluginResourceService.findByPluginPid(pluginPid);
        if (resources.isEmpty()) {
            log.info("No plugin resources found for rollback: pluginPid={}", pluginPid);
            return;
        }

        List<PluginResource> rollbackTargets = resources.stream()
                .filter(r -> r.getResourcePid() != null && createdPidSet.contains(r.getResourcePid()))
                .sorted(Comparator.comparingInt(
                        (PluginResource r) -> r.getSequence() != null ? r.getSequence() : 0).reversed())
                .toList();

        int deleted = 0;
        for (PluginResource resource : rollbackTargets) {
            try {
                pluginResourceService.deleteResource(resource);
                pluginResourceMapper.deleteByPluginPidAndCode(pluginPid, resource.getResourceCode());
                deleted++;
            } catch (Exception e) {
                log.error("Failed to rollback resource: pluginPid={}, type={}, code={}, resourcePid={}",
                        pluginPid, resource.getResourceType(), resource.getResourceCode(),
                        resource.getResourcePid(), e);
            }
        }
        log.info("Rolled back config component: pluginPid={}, deletedResources={}", pluginPid, deleted);
    }

    private void rollbackBackend(Map<String, Object> rollbackData) {
        String backendPluginId = (String) rollbackData.get("backendPluginId");
        if (backendPluginId != null) {
            auraPluginManager.hotUnloadPlugin(backendPluginId);
        }
        log.info("Rolling back backend component");
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        }
        return Collections.emptyList();
    }

    private void updatePackageStatus(String packageId, PluginPackageHistory.PackageStatus status) {
        packageHistoryMapper.updateStatus(packageId, status.code());
    }

    private void updatePackageStatusWithError(String packageId, PluginPackageHistory.PackageStatus status, String error) {
        packageHistoryMapper.markFailed(packageId, error);
    }

    private void updatePackageConfigStatus(String packageId, String status, String error, Map<String, Integer> counts) {
        PluginPackageHistory history = packageHistoryMapper.findByPid(packageId);
        if (history != null) {
            history.setConfigStatus(status);
            history.setConfigError(error);
            if (counts != null) {
                Map<String, Object> countsMap = new HashMap<>(counts);
                history.setConfigResourceCounts(countsMap);
            }
            packageHistoryMapper.updateById(history);
        }
    }

    private void updatePackageBackendStatus(String packageId, String status, String error, String jarPath) {
        packageHistoryMapper.updateBackendStatus(packageId, status, error);
    }

    private void updatePackageFrontendStatus(String packageId, String status, String error, String remoteUrl) {
        packageHistoryMapper.updateFrontendStatus(packageId, status, error);
    }

    private void updatePluginConfigStatus(String pluginPid, String status, String error) {
        // Update via direct SQL or mapper method
    }

    private void updatePluginBackendStatus(String pluginPid, String backendPluginId, String status, String error) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pluginPid);
        if (plugin != null) {
            plugin.setBackendPluginId(backendPluginId);
            plugin.setBackendStatus(status);
            plugin.setBackendError(error);
            pluginRecordMapper.updateById(plugin);
        }
    }

    private void updatePluginFrontendStatus(String pluginPid, String remoteUrl, String status, String error) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pluginPid);
        if (plugin != null) {
            plugin.setFrontendRemoteUrl(remoteUrl);
            plugin.setFrontendStatus(status);
            plugin.setFrontendError(error);
            pluginRecordMapper.updateById(plugin);
        }
    }

    private Path getPluginsPath() {
        Path path = Paths.get(pluginsDir);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir"), pluginsDir);
        }
        return path;
    }

    private Path getFrontendPluginsPath() {
        Path path = Paths.get(frontendPluginsDir);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir"), frontendPluginsDir);
        }
        return path;
    }

    private String buildFrontendRemoteUrl(String namespace) {
        // In production, this would be configurable
        return "/plugins/" + namespace + "/remoteEntry.js";
    }

    private Map<String, Integer> flattenStringResourceCounts(Map<String, Map<String, Integer>> counts) {
        Map<String, Integer> flat = new HashMap<>();
        if (counts != null) {
            for (Map.Entry<String, Map<String, Integer>> entry : counts.entrySet()) {
                String typePrefix = entry.getKey().toLowerCase();
                for (Map.Entry<String, Integer> actionEntry : entry.getValue().entrySet()) {
                    flat.put(typePrefix + "_" + actionEntry.getKey().toLowerCase(),
                            actionEntry.getValue());
                }
            }
        }
        return flat;
    }

    private List<String> flattenStringCreatedResources(Map<String, List<String>> created) {
        List<String> flat = new ArrayList<>();
        if (created != null) {
            for (List<String> pids : created.values()) {
                flat.addAll(pids);
            }
        }
        return flat;
    }

    private PackageStatusDTO buildStatusDTO(PluginRecord plugin) {
        return PackageStatusDTO.builder()
                .pluginPid(plugin.getPid())
                .pluginId(plugin.getPluginId())
                .namespace(plugin.getNamespace())
                .version(plugin.getVersion())
                .displayName(plugin.getDisplayName())
                .status(plugin.getStatus())
                .hasConfig(Boolean.TRUE.equals(plugin.getHasConfig()))
                .hasBackend(Boolean.TRUE.equals(plugin.getHasBackend()))
                .backendStatus(plugin.getBackendStatus())
                .backendPluginId(plugin.getBackendPluginId())
                .backendError(plugin.getBackendError())
                .hasFrontend(Boolean.TRUE.equals(plugin.getHasFrontend()))
                .frontendStatus(plugin.getFrontendStatus())
                .frontendRemoteUrl(plugin.getFrontendRemoteUrl())
                .frontendError(plugin.getFrontendError())
                .installedAt(plugin.getInstalledAt())
                .enabledAt(plugin.getEnabledAt())
                .updatedAt(plugin.getUpdatedAt())
                .build();
    }

    private PackageHistoryDTO buildHistoryDTO(PluginPackageHistory history) {
        Map<String, Integer> configCounts = new HashMap<>();
        if (history.getConfigResourceCounts() != null) {
            history.getConfigResourceCounts().forEach((k, v) -> {
                if (v instanceof Number) {
                    configCounts.put(k, ((Number) v).intValue());
                }
            });
        }

        return PackageHistoryDTO.builder()
                .pid(history.getPid())
                .pluginPid(history.getPluginPid())
                .pluginId(history.getPluginId())
                .namespace(history.getNamespace())
                .version(history.getVersion())
                .displayName(history.getDisplayName())
                .sourceType(history.getSourceType())
                .sourceName(history.getSourceName())
                .configEnabled(Boolean.TRUE.equals(history.getConfigEnabled()))
                .configStatus(history.getConfigStatus())
                .configResourceCounts(configCounts)
                .backendEnabled(Boolean.TRUE.equals(history.getBackendEnabled()))
                .backendStatus(history.getBackendStatus())
                .frontendEnabled(Boolean.TRUE.equals(history.getFrontendEnabled()))
                .frontendStatus(history.getFrontendStatus())
                .frontendRemoteUrl(history.getFrontendRemoteUrl())
                .status(history.getStatus())
                .errorMessage(history.getErrorMessage())
                .canRollback(Boolean.TRUE.equals(history.getCanRollback()))
                .startedAt(history.getStartedAt())
                .completedAt(history.getCompletedAt())
                .createdAt(history.getCreatedAt())
                .createdBy(history.getCreatedBy())
                .build();
    }

    private void deleteDirectory(Path path) throws IOException {
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Context for in-progress package operations.
     */
    @Data
    private static class PackageContext {
        private final String packageId;
        private final PackageManifest manifest;
        private final Path extractPath;
        private final PluginPackageHistory history;
        private final PackageParseResult.DetectedComponents detected;
    }
}
