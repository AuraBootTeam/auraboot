package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.auraboot.framework.plugin.config.PlatformProperties;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.util.SemverMatcher;
import com.auraboot.framework.plugin.validation.PluginValidationContext;
import com.auraboot.framework.plugin.validation.PluginQualityScorer;
import com.auraboot.framework.plugin.validation.PluginValidationPipeline;
import com.auraboot.framework.plugin.validation.PluginValidationResult;
import com.auraboot.framework.plugin.entity.PluginImportHistory;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.lock.DistributedLock;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.plugin.mapper.PluginImportHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.SchemaSyncOptions;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.auraboot.framework.common.constant.StatusConstants;
import io.micrometer.observation.annotation.Observed;

/**
 * Implementation of plugin import service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginImportServiceImpl implements PluginImportService {
    private static final int DEFAULT_IMPORT_LOCK_LEASE_MINUTES = 20;
    private static final int IMPORT_LOCK_LEASE_MINUTES = parseEnvInt(
            "plugin_import_lock_lease_minutes", DEFAULT_IMPORT_LOCK_LEASE_MINUTES);

    private final PluginImportHistoryMapper importHistoryMapper;
    private final PluginRecordMapper pluginRecordMapper;
    private final PluginResourceMapper pluginResourceMapper;
    private final PluginResourceImporter resourceImporter;
    private final PlatformTransactionManager transactionManager;
    private final PluginDirectoryLoader directoryLoader;
    private final MenuMapper menuMapper;
    private final MetaModelService metaModelService;
    private final com.auraboot.framework.meta.service.MetaFieldService metaFieldService;
    private final com.auraboot.framework.meta.service.CommandService commandService;
    private final SchemaManagementService schemaManagementService;
    private final PermissionService permissionService;
    private final UserPermissionService userPermissionService;
    private final RoleService roleService;
    private final RolePermissionMapper rolePermissionMapper;
    private final DistributedLock distributedLock;
    private final I18nResourceService i18nResourceService;
    private final I18nCompiler i18nCompiler;
    private final PlatformProperties platformProperties;
    private final com.auraboot.framework.plugin.service.PlatformVersionChecker platformVersionChecker;
    private final PluginValidationPipeline validationPipeline;
    private final PluginQualityScorer qualityScorer;
    private final SavedViewMapper savedViewMapper;
    private final AutoPermissionAssignmentService autoPermissionAssignmentService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final com.auraboot.framework.meta.template.generator.DocumentCommandGenerator documentCommandGenerator;
    private final com.auraboot.framework.bpm.rule.DroolsRuleService droolsRuleService;
    private final com.auraboot.framework.bpm.service.SlaConfigService slaConfigService;

    private final ObjectMapper objectMapper = createObjectMapper();

    // Cache for in-progress imports (importId -> manifest)
    private final Map<String, ImportContext> importContextCache = new ConcurrentHashMap<>();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static int parseEnvInt(String name, int defaultValue) {
        try {
            String raw = System.getenv(name);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    // ==================== Upload & Parse ====================

    @Override
    public ImportPreviewResult upload(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            fileName = "unknown";
        }

        try (InputStream is = file.getInputStream()) {
            return parse(is, fileName);
        } catch (IOException e) {
            throw new PluginException("Failed to read uploaded file: " + e.getMessage());
        }
    }

    @Override
    public ImportPreviewResult parseJson(String jsonContent, String sourceName) {
        try {
            PluginManifestExtended manifest = objectMapper.readValue(jsonContent, PluginManifestExtended.class);
            return createPreviewFromManifest(manifest, sourceName, "json");
        } catch (JsonProcessingException e) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("Invalid JSON format: " + e.getMessage());
            return result;
        }
    }

    @Override
    public ImportPreviewResult parseDirectory(String directoryPath) {
        java.nio.file.Path pluginDir = java.nio.file.Paths.get(directoryPath);

        if (!java.nio.file.Files.isDirectory(pluginDir)) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("Path is not a directory: " + directoryPath);
            return result;
        }

        if (!directoryLoader.isValidPluginDirectory(pluginDir)) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("Directory does not contain plugin.json: " + directoryPath);
            return result;
        }

        try {
            PluginManifestExtended manifest = directoryLoader.loadFromDirectory(pluginDir);
            return createPreviewFromManifest(manifest, directoryPath, "directory");
        } catch (PluginException e) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("Failed to load plugin from directory: " + e.getMessage());
            return result;
        }
    }

    @Override
    public ImportPreviewResult parseSource(com.auraboot.framework.plugin.source.PluginSource source) {
        if (!source.isValidPlugin()) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("Source does not contain plugin.json: " + source.getSourceId());
            return result;
        }

        try {
            PluginManifestExtended manifest = directoryLoader.loadFromSource(source);
            return createPreviewFromManifest(manifest, source.getSourceId(), "source");
        } catch (PluginException e) {
            ImportPreviewResult result = new ImportPreviewResult();
            result.setValid(false);
            result.addError("Failed to load plugin from source: " + e.getMessage());
            return result;
        }
    }

    @Override
    public ImportPreviewResult parse(InputStream inputStream, String fileName) {
        String sourceType = detectSourceType(fileName);

        if ("zip".equals(sourceType)) {
            return parseZip(inputStream, fileName);
        } else {
            try {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                return parseJson(content, fileName);
            } catch (IOException e) {
                throw new PluginException("Failed to read file: " + e.getMessage());
            }
        }
    }

    private static final long MAX_ZIP_ENTRY_SIZE = 10 * 1024 * 1024; // 10 MB per entry
    private static final int MAX_ZIP_ENTRIES = 1000;
    private static final long MAX_ZIP_TOTAL_SIZE = 100 * 1024 * 1024; // 100 MB total

    private ImportPreviewResult parseZip(InputStream inputStream, String fileName) {
        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            String manifestJson = null;
            Map<String, byte[]> files = new HashMap<>();
            int entryCount = 0;
            long totalSize = 0;

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new PluginException("ZIP file exceeds maximum entry count: " + MAX_ZIP_ENTRIES);
                }

                String entryName = entry.getName();

                // Zip Slip protection: reject entries with path traversal sequences
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    throw new PluginException("Invalid ZIP entry path (path traversal detected): " + entryName);
                }

                // Read with size limit per entry
                byte[] content = zis.readNBytes((int) MAX_ZIP_ENTRY_SIZE + 1);
                if (content.length > MAX_ZIP_ENTRY_SIZE) {
                    throw new PluginException("ZIP entry exceeds maximum size (" + MAX_ZIP_ENTRY_SIZE / (1024 * 1024) + " MB): " + entryName);
                }

                totalSize += content.length;
                if (totalSize > MAX_ZIP_TOTAL_SIZE) {
                    throw new PluginException("ZIP total uncompressed size exceeds maximum (" + MAX_ZIP_TOTAL_SIZE / (1024 * 1024) + " MB)");
                }

                if (entryName.equals("plugin.json") || entryName.equals("manifest.json")) {
                    manifestJson = new String(content, StandardCharsets.UTF_8);
                } else {
                    files.put(entryName, content);
                }
                zis.closeEntry();
            }

            if (manifestJson == null) {
                ImportPreviewResult result = new ImportPreviewResult();
                result.setValid(false);
                result.addError("ZIP file must contain plugin.json or manifest.json");
                return result;
            }

            PluginManifestExtended manifest = objectMapper.readValue(manifestJson, PluginManifestExtended.class);

            // Load resources from resourceDirs configuration in ZIP
            loadResourcesFromZipFiles(manifest, files);

            // Process BPMN files from ZIP
            if (manifest.getProcesses() != null) {
                for (ProcessDefinitionDTO process : manifest.getProcesses()) {
                    if (process.getBpmnFile() != null && process.getBpmnContent() == null) {
                        byte[] bpmnContent = files.get(process.getBpmnFile());
                        if (bpmnContent != null) {
                            process.setBpmnContent(new String(bpmnContent, StandardCharsets.UTF_8));
                        }
                    }
                }
            }

            ImportPreviewResult result = createPreviewFromManifest(manifest, fileName, "zip");

            // Store additional files in context for later use
            ImportContext context = importContextCache.get(result.getImportId());
            if (context != null) {
                context.setAdditionalFiles(files);
            }

            return result;

        } catch (IOException e) {
            throw new PluginException("Failed to read ZIP file: " + e.getMessage());
        }
    }

    /**
     * Load resources from ZIP files based on resourceDirs configuration.
     * This mirrors PluginDirectoryLoader.loadResourcesFromDirs but works with in-memory byte arrays.
     */
    private void loadResourcesFromZipFiles(PluginManifestExtended manifest, Map<String, byte[]> files) {
        Map<String, String> resourceDirs = manifest.getResourceDirs();
        if (resourceDirs == null || resourceDirs.isEmpty()) {
            return;
        }

        try {
            // Load models
            if (resourceDirs.containsKey("models")) {
                List<ModelDefinitionDTO> models = loadResourceListFromZip(files, resourceDirs.get("models"), ModelDefinitionDTO.class);
                if (!models.isEmpty()) {
                    manifest.setModels(mergeResourceList(manifest.getModels(), models));
                }
            }

            // Load fields
            if (resourceDirs.containsKey("fields")) {
                List<FieldDefinitionDTO> fields = loadResourceListFromZip(files, resourceDirs.get("fields"), FieldDefinitionDTO.class);
                if (!fields.isEmpty()) {
                    manifest.setFields(mergeResourceList(manifest.getFields(), fields));
                }
            }

            // Load bindings (modelFieldBindings key)
            String bindingsKey = resourceDirs.containsKey("modelFieldBindings") ? "modelFieldBindings" : "bindings";
            if (resourceDirs.containsKey(bindingsKey)) {
                List<ModelFieldBindingDTO> bindings = loadResourceListFromZip(files, resourceDirs.get(bindingsKey), ModelFieldBindingDTO.class);
                if (!bindings.isEmpty()) {
                    manifest.setModelFieldBindings(mergeResourceList(manifest.getModelFieldBindings(), bindings));
                }
            }

            // Load dicts
            if (resourceDirs.containsKey("dicts")) {
                List<DictDefinitionDTO> dicts = loadResourceListFromZip(files, resourceDirs.get("dicts"), DictDefinitionDTO.class);
                if (!dicts.isEmpty()) {
                    manifest.setDicts(mergeResourceList(manifest.getDicts(), dicts));
                }
            }

            // Load commands
            if (resourceDirs.containsKey("commands")) {
                List<CommandDefinitionDTO> commands = loadResourceListFromZip(files, resourceDirs.get("commands"), CommandDefinitionDTO.class);
                if (!commands.isEmpty()) {
                    manifest.setCommands(mergeResourceList(manifest.getCommands(), commands));
                }
            }

            // Load menus
            if (resourceDirs.containsKey("menus")) {
                List<MenuDefinitionDTO> menus = loadResourceListFromZip(files, resourceDirs.get("menus"), MenuDefinitionDTO.class);
                if (!menus.isEmpty()) {
                    manifest.setMenus(mergeResourceList(manifest.getMenus(), menus));
                    log.debug("Loaded {} menus from ZIP: {}", menus.size(), resourceDirs.get("menus"));
                }
            }

            // Load permissions
            if (resourceDirs.containsKey("permissions")) {
                List<PermissionDefinitionDTO> permissions = loadResourceListFromZip(files, resourceDirs.get("permissions"), PermissionDefinitionDTO.class);
                if (!permissions.isEmpty()) {
                    manifest.setPermissions(mergeResourceList(manifest.getPermissions(), permissions));
                }
            }

            // Load roles
            if (resourceDirs.containsKey("roles")) {
                List<RoleDefinitionDTO> roles = loadResourceListFromZip(files, resourceDirs.get("roles"), RoleDefinitionDTO.class);
                if (!roles.isEmpty()) {
                    manifest.setRoles(mergeResourceList(manifest.getRoles(), roles));
                }
            }

            // Load pages
            if (resourceDirs.containsKey("pages")) {
                List<PageSchemaDTO> pages = loadResourceListFromZip(files, resourceDirs.get("pages"), PageSchemaDTO.class);
                if (!pages.isEmpty()) {
                    manifest.setPages(mergeResourceList(manifest.getPages(), pages));
                }
            }

            // Load named queries
            if (resourceDirs.containsKey("namedQueries")) {
                List<NamedQueryDefinitionDTO> namedQueries = loadResourceListFromZip(
                        files, resourceDirs.get("namedQueries"), NamedQueryDefinitionDTO.class);
                if (!namedQueries.isEmpty()) {
                    manifest.setNamedQueries(mergeResourceList(manifest.getNamedQueries(), namedQueries));
                }
            }

            // Load saved views
            if (resourceDirs.containsKey("savedViews")) {
                List<SavedViewDefinitionDTO> savedViews = loadResourceListFromZip(
                        files, resourceDirs.get("savedViews"), SavedViewDefinitionDTO.class);
                if (!savedViews.isEmpty()) {
                    manifest.setSavedViews(mergeResourceList(manifest.getSavedViews(), savedViews));
                }
            }

            // Load processes
            if (resourceDirs.containsKey("processes")) {
                List<ProcessDefinitionDTO> processes = loadResourceListFromZip(files, resourceDirs.get("processes"), ProcessDefinitionDTO.class);
                if (!processes.isEmpty()) {
                    manifest.setProcesses(mergeResourceList(manifest.getProcesses(), processes));
                }
            }

            // Load dashboards (first-class contract: config/dashboards/*.json)
            if (resourceDirs.containsKey("dashboards")) {
                List<com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO> dashboards =
                        loadResourceListFromZip(files, resourceDirs.get("dashboards"),
                                com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO.class);
                if (!dashboards.isEmpty()) {
                    manifest.setDashboards(mergeResourceList(manifest.getDashboards(), dashboards));
                }
            }

            log.info("Loaded resources from ZIP resourceDirs: {}", manifest.getResourceCounts());

        } catch (Exception e) {
            log.warn("Failed to load some resources from ZIP: {}", e.getMessage());
        }
    }

    /**
     * Load a list of resources from ZIP files map.
     */
    private <T> List<T> loadResourceListFromZip(Map<String, byte[]> files, String path, Class<T> clazz) {
        byte[] content = files.get(path);
        if (content == null) {
            log.debug("Resource file not found in ZIP: {}", path);
            return List.of();
        }

        try {
            String jsonContent = new String(content, StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, clazz);
            return objectMapper.readValue(jsonContent, listType);
        } catch (Exception e) {
            log.warn("Failed to parse resource file {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * Merge two resource lists.
     */
    private <T> List<T> mergeResourceList(List<T> existing, List<T> newItems) {
        if (existing == null || existing.isEmpty()) {
            return new ArrayList<>(newItems);
        }
        List<T> result = new ArrayList<>(existing);
        result.addAll(newItems);
        return result;
    }

    private ImportPreviewResult createPreviewFromManifest(PluginManifestExtended manifest, String sourceName, String sourceType) {
        String importId = UlidGenerator.generate();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Create import history record
        PluginImportHistory history = PluginImportHistory.builder()
                .importId(importId)
                .tenantId(tenantId)
                .pluginId(manifest.getPluginId())
                .namespace(manifest.getNamespace())
                .version(manifest.getVersion())
                .status(ImportStatus.PARSING.code())
                .importType("install")
                .sourceType(sourceType)
                .sourceName(sourceName)
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Check if this is an upgrade
        PluginRecord existing = pluginRecordMapper.findByTenantAndPluginId(manifest.getPluginId());
        if (existing != null) {
            history.setImportType("upgrade");
        }

        importHistoryMapper.insert(history);

        // Remove JSON comment objects (entries with only _-prefixed fields) before validation
        manifest.sanitize();

        // Validate manifest — separate [WARN]-prefixed soft warnings from hard errors
        List<String> allValidationMessages = validateManifest(manifest);
        List<String> validationErrors = new ArrayList<>();
        List<String> validationWarnings = new ArrayList<>(manifest.getValidationWarnings());
        for (String msg : allValidationMessages) {
            if (msg.startsWith("[WARN] ")) {
                validationWarnings.add(msg.substring(7));
            } else {
                validationErrors.add(msg);
            }
        }

        // Create preview result
        ImportPreviewResult result = ImportPreviewResult.builder()
                .importId(importId)
                .pluginId(manifest.getPluginId())
                .namespace(manifest.getNamespace())
                .version(manifest.getVersion())
                .displayName(manifest.getEffectiveDisplayName())
                .isUpgrade(existing != null)
                .previousVersion(existing != null ? existing.getVersion() : null)
                .valid(validationErrors.isEmpty())
                .errors(validationErrors)
                .warnings(validationWarnings)
                .changes(new HashMap<>())
                .actionCounts(new HashMap<>())
                .build();

        // Check conflicts
        List<ImportPreviewResult.ResourceConflict> conflicts = checkConflicts(manifest);
        result.setConflicts(conflicts);

        // Analyze dependencies
        ImportPreviewResult.DependencyAnalysis depAnalysis = analyzeDependencies(manifest);
        result.setDependencyAnalysis(depAnalysis);

        if (!depAnalysis.isSatisfied()) {
            for (String missing : depAnalysis.getMissingDependencies()) {
                result.addError("Missing dependency: " + missing);
            }
        }

        // Run extended validation pipeline (semantic + governance)
        if (result.isValid()) {
            try {
                PluginValidationResult validationResult = runValidationPipeline(manifest);
                result.setValidationResult(validationResult);
                // Promote validation errors to preview errors
                validationResult.getMessages().stream()
                        .filter(m -> m.isError())
                        .forEach(m -> result.addError("[" + m.getCode() + "] " + m.getMessage()));
                // Add validation warnings to preview warnings
                validationResult.getMessages().stream()
                        .filter(m -> m.isWarning())
                        .forEach(m -> result.addWarning("[" + m.getCode() + "] " + m.getMessage()));
            } catch (Exception e) {
                log.warn("Validation pipeline error (non-blocking): {}", e.getMessage());
                result.addWarning("Validation pipeline error: " + e.getMessage());
            }
        }

        // Generate change preview
        generateChangePreview(manifest, result, existing);

        // Summarize user-modified resources that will be overwritten
        summarizeUserModifiedConflicts(result);

        // Update history status
        if (result.isValid()) {
            importHistoryMapper.updateStatus(importId, ImportStatus.PREVIEWING.code());
        } else {
            String errorSummary = String.join("; ", result.getErrors());
            importHistoryMapper.markFailed(importId, errorSummary, null);
        }

        // Cache the context
        importContextCache.put(importId, new ImportContext(manifest, history, result));

        return result;
    }

    private void generateChangePreview(PluginManifestExtended manifest, ImportPreviewResult result, PluginRecord existing) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Preview models
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO model : manifest.getModels()) {
                ResourceAction action = resourceImporter.checkModelExists(tenantId, model.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.MODEL, enrichWithUserModified(tenantId, ResourceType.MODEL, model.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.MODEL)
                        .resourceCode(model.getCode())
                        .resourceName(model.getEffectiveDisplayName())
                        .action(action)
                        .build()));
            }
        }

        // Preview fields
        if (manifest.getFields() != null) {
            for (FieldDefinitionDTO field : manifest.getFields()) {
                ResourceAction action = resourceImporter.checkFieldExists(tenantId, field.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.FIELD, enrichWithUserModified(tenantId, ResourceType.FIELD, field.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.FIELD)
                        .resourceCode(field.getCode())
                        .resourceName(field.getEffectiveDisplayName())
                        .action(action)
                        .build()));
            }
        }

        // Preview commands
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO command : manifest.getCommands()) {
                ResourceAction action = resourceImporter.checkCommandExists(tenantId, command.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.COMMAND, enrichWithUserModified(tenantId, ResourceType.COMMAND, command.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.COMMAND)
                        .resourceCode(command.getCode())
                        .resourceName(command.getEffectiveDisplayName())
                        .action(action)
                        .build()));
            }
        }

        // Preview permissions
        if (manifest.getPermissions() != null) {
            for (PermissionDefinitionDTO permission : manifest.getPermissions()) {
                ResourceAction action = resourceImporter.checkPermissionExists(tenantId, permission.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.PERMISSION, enrichWithUserModified(tenantId, ResourceType.PERMISSION, permission.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.PERMISSION)
                        .resourceCode(permission.getCode())
                        .resourceName(permission.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview roles
        if (manifest.getRoles() != null) {
            for (RoleDefinitionDTO role : manifest.getRoles()) {
                ResourceAction action = resourceImporter.checkRoleExists(tenantId, role.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.ROLE, enrichWithUserModified(tenantId, ResourceType.ROLE, role.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.ROLE)
                        .resourceCode(role.getCode())
                        .resourceName(role.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview menus
        if (manifest.getMenus() != null) {
            for (MenuDefinitionDTO menu : manifest.getMenus()) {
                ResourceAction action = resourceImporter.checkMenuExists(tenantId, menu.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.MENU, enrichWithUserModified(tenantId, ResourceType.MENU, menu.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.MENU)
                        .resourceCode(menu.getCode())
                        .resourceName(menu.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview processes
        if (manifest.getProcesses() != null) {
            for (ProcessDefinitionDTO process : manifest.getProcesses()) {
                ResourceAction action = resourceImporter.checkProcessExists(tenantId, process.getKey())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.PROCESS, enrichWithUserModified(tenantId, ResourceType.PROCESS, process.getKey(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.PROCESS)
                        .resourceCode(process.getKey())
                        .resourceName(process.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview pages
        if (manifest.getPages() != null) {
            for (PageSchemaDTO page : manifest.getPages()) {
                ResourceAction action = resourceImporter.checkPageExists(tenantId, page.getPageKey())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.PAGE, enrichWithUserModified(tenantId, ResourceType.PAGE, page.getPageKey(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.PAGE)
                        .resourceCode(page.getPageKey())
                        .resourceName(page.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview dicts
        if (manifest.getDicts() != null) {
            for (DictDefinitionDTO dict : manifest.getDicts()) {
                ResourceAction action = resourceImporter.checkDictExists(tenantId, dict.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.DICT, enrichWithUserModified(tenantId, ResourceType.DICT, dict.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.DICT)
                        .resourceCode(dict.getCode())
                        .resourceName(dict.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview named queries
        if (manifest.getNamedQueries() != null) {
            for (NamedQueryDefinitionDTO namedQuery : manifest.getNamedQueries()) {
                ResourceAction action = resourceImporter.checkNamedQueryExists(tenantId, namedQuery.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.NAMED_QUERY, enrichWithUserModified(
                        tenantId, ResourceType.NAMED_QUERY, namedQuery.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                                .resourceType(ResourceType.NAMED_QUERY)
                                .resourceCode(namedQuery.getCode())
                                .resourceName(namedQuery.getEffectiveTitle())
                                .action(action)
                                .build()));
            }
        }

        // Preview saved views
        if (manifest.getSavedViews() != null) {
            for (SavedViewDefinitionDTO savedView : manifest.getSavedViews()) {
                List<SavedView> existingViews = savedViewMapper.findGlobalViews(savedView.getModelCode(), savedView.getPageKey());
                boolean exists = existingViews.stream()
                        .anyMatch(v -> v.getName().equals(savedView.getName()) && v.getViewType().equals(savedView.getViewType()));
                result.addChange(ResourceType.SAVED_VIEW, ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.SAVED_VIEW)
                        .resourceCode(savedView.getUniqueKey())
                        .resourceName(savedView.getName() + " (" + savedView.getViewType() + ")")
                        .action(exists ? ResourceAction.UPDATE : ResourceAction.CREATE)
                        .build());
            }
        }
    }

    /**
     * Enrich a ResourceChange with user-modified status from ab_plugin_resource.
     * Only meaningful for UPDATE actions (new resources can't be user-modified).
     */
    private ImportPreviewResult.ResourceChange enrichWithUserModified(
            Long tenantId, ResourceType type, String resourceCode,
            ImportPreviewResult.ResourceChange change) {
        if (change.getAction() == ResourceAction.UPDATE) {
            try {
                PluginResource pr = pluginResourceMapper.findByTypeAndCode(
                        tenantId, type.name(), resourceCode);
                if (pr != null && Boolean.TRUE.equals(pr.getUserModified())) {
                    change.setUserModified(true);
                    change.setUserModifiedAt(pr.getUserModifiedAt());
                }
            } catch (Exception e) {
                log.debug("Failed to check user-modified status for {} {}: {}",
                        type, resourceCode, e.getMessage());
            }
        }
        return change;
    }

    /**
     * Summarize user-modified resources and add warnings to the preview result.
     */
    private void summarizeUserModifiedConflicts(ImportPreviewResult result) {
        List<String> modifiedResources = new ArrayList<>();
        if (result.getChanges() != null) {
            for (List<ImportPreviewResult.ResourceChange> changes : result.getChanges().values()) {
                for (ImportPreviewResult.ResourceChange change : changes) {
                    if (change.isUserModified() && change.getAction() == ResourceAction.UPDATE) {
                        modifiedResources.add(change.getResourceType() + " " + change.getResourceCode());
                    }
                }
            }
        }
        if (!modifiedResources.isEmpty()) {
            result.addWarning("The following " + modifiedResources.size() +
                    " resource(s) have been manually modified and will be overwritten: " +
                    String.join(", ", modifiedResources));
        }
    }

    // ==================== Preview ====================

    @Override
    public ImportPreviewResult preview(String importId, ImportRequest request) {
        ImportContext context = importContextCache.get(importId);
        if (context == null) {
            throw new PluginException("Import not found: " + importId);
        }

        // Re-generate preview with updated options
        return context.getPreviewResult();
    }

    @Override
    public ImportPreviewResult getPreview(String importId) {
        ImportContext context = importContextCache.get(importId);
        return context != null ? context.getPreviewResult() : null;
    }

    // ==================== Execute ====================

    @Override
    @Observed(name = "plugin.import.execute", contextualName = "plugin-import-execute")
    public ImportExecuteResult execute(String importId, ImportRequest request) {
        ImportContext context = importContextCache.get(importId);
        if (context == null) {
            throw new PluginException("Import not found: " + importId);
        }

        return executeWithLock(context, request);
    }

    @Override
    public ImportPreviewResult previewFromManifest(PluginManifestExtended manifest) {
        // createPreviewFromManifest already does: validateManifest, checkConflicts,
        // analyzeDependencies, generateChangePreview (resource counts via addChange)
        ImportPreviewResult preview = createPreviewFromManifest(manifest, "preview", "json");
        // Remove the cached ImportContext since we won't execute
        importContextCache.remove(preview.getImportId());
        return preview;
    }

    public ImportExecuteResult executeFromManifest(PluginManifestExtended manifest, ImportRequest request) {
        // Create a temporary context (includes validation)
        ImportPreviewResult preview = createPreviewFromManifest(manifest, "direct", "json");

        // Block execution if validation failed
        if (!preview.isValid()) {
            String errorSummary = String.join("; ", preview.getErrors());
            throw new PluginException("Plugin manifest validation failed: " + errorSummary);
        }

        ImportContext context = importContextCache.get(preview.getImportId());
        return executeWithLock(context, request);
    }

    /**
     * Execute import with distributed lock and programmatic transaction.
     * Lock is acquired OUTSIDE the transaction to ensure visibility to concurrent threads.
     * Uses DatabaseDistributedLock (row-level INSERT ON CONFLICT) which requires committed
     * rows to be visible — hence the lock must not be inside @Transactional scope.
     */
    private ImportExecuteResult executeWithLock(ImportContext context, ImportRequest request) {
        // Merge manifest importOptions as defaults before applying hard defaults.
        // Manifest options only take effect when the request field is null (i.e., not explicitly set by caller).
        mergeManifestImportOptions(context.getManifest(), request);

        // Apply defaults for any null fields (Jackson + Lombok @ConstructorProperties pitfall)
        request.applyDefaults();
        String pluginId = context.getManifest().getPluginId();
        String importId = context.getHistory().getImportId();
        Long tenantId = MetaContext.getCurrentTenantId();
        String lockKey = "plugin-import:" + tenantId + ":" + pluginId;

        // Guard: clean up stale IMPORTING records (>10 min old)
        cleanupStaleImports(pluginId);

        // Acquire distributed lock (lease is configurable via PLUGIN_IMPORT_LOCK_LEASE_MINUTES).
        boolean locked = distributedLock.tryLock(lockKey, IMPORT_LOCK_LEASE_MINUTES, TimeUnit.MINUTES);
        if (!locked) {
            PluginException lockException = new PluginException(
                    "Plugin '" + pluginId + "' is being imported by another process. Please try again later.");
            markImportFailedInNewTransaction(importId, lockException);
            throw lockException;
        }

        try {
            // Run import in a programmatic transaction
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            return txTemplate.execute(status -> doExecute(context, request));
        } catch (PluginException e) {
            markImportFailedInNewTransaction(importId, e);
            throw e;
        } catch (UnexpectedRollbackException e) {
            log.error("Plugin import rolled back unexpectedly: importId={}, pluginId={}, message={}",
                    importId, pluginId, e.getMessage(), e);
            markImportFailedInNewTransaction(importId, e);
            throw new PluginException("Import failed: " + rootErrorMessage(e), e);
        } catch (RuntimeException e) {
            markImportFailedInNewTransaction(importId, e);
            throw new PluginException("Import failed: " + rootErrorMessage(e), e);
        } finally {
            distributedLock.unlock(lockKey);
        }
    }

    /**
     * Merge importOptions from plugin.json manifest into ImportRequest as defaults.
     * Only sets request fields that are still null (caller-provided values take precedence).
     */
    private void mergeManifestImportOptions(PluginManifestExtended manifest, ImportRequest request) {
        if (manifest == null || manifest.getImportOptions() == null) {
            return;
        }
        PluginManifestExtended.ImportOptions opts = manifest.getImportOptions();
        if (request.getAutoPublishModels() == null && opts.getAutoPublishModels() != null) {
            request.setAutoPublishModels(opts.getAutoPublishModels());
        }
        if (request.getAutoPublishFields() == null && opts.getAutoPublishFields() != null) {
            request.setAutoPublishFields(opts.getAutoPublishFields());
        }
        if (request.getAutoPublishCommands() == null && opts.getAutoPublishCommands() != null) {
            request.setAutoPublishCommands(opts.getAutoPublishCommands());
        }
        if (request.getAutoPublishPages() == null && opts.getAutoPublishPages() != null) {
            request.setAutoPublishPages(opts.getAutoPublishPages());
        }
        if (request.getAutoDeployProcesses() == null && opts.getAutoDeployProcesses() != null) {
            request.setAutoDeployProcesses(opts.getAutoDeployProcesses());
        }
    }

    /**
     * Clean up stale import records that are stuck in IMPORTING status for over configured lease window.
     * This prevents permanently blocked imports caused by crashed processes or network failures.
     */
    private void cleanupStaleImports(String pluginId) {
        // Clean up both IMPORTING and PREVIEWING stale records.
        // PREVIEWING can become orphaned when parseDirectory succeeds but execute fails
        // (e.g., lock contention), leaving history records stuck in a non-terminal state.
        List<PluginImportHistory> staleImports = importHistoryMapper.selectList(
                new LambdaQueryWrapper<PluginImportHistory>()
                        .eq(PluginImportHistory::getPluginId, pluginId)
                        .in(PluginImportHistory::getStatus,
                                ImportStatus.IMPORTING.code(),
                                ImportStatus.PREVIEWING.code(),
                                ImportStatus.PARSING.code(),
                                ImportStatus.VALIDATING.code())
        );

        Instant staleThreshold = Instant.now().minus(Duration.ofMinutes(IMPORT_LOCK_LEASE_MINUTES));
        for (PluginImportHistory stale : staleImports) {
            if (stale.getUpdatedAt() != null && stale.getUpdatedAt().isBefore(staleThreshold)) {
                log.warn("Marking stale import as FAILED: importId={}, pluginId={}, status={}, updatedAt={}",
                        stale.getImportId(), pluginId, stale.getStatus(), stale.getUpdatedAt());
                importHistoryMapper.markFailed(stale.getImportId(),
                        "Import timed out (stale record in " + stale.getStatus() + " status)",
                        null);
            } else if (ImportStatus.IMPORTING.code().equals(stale.getStatus())) {
                // Only IMPORTING status indicates an active import in progress.
                // PREVIEWING/PARSING/VALIDATING are pre-execution states that don't hold
                // the distributed lock, so they should not block new imports.
                throw new PluginException(
                        "Plugin '" + pluginId + "' has an active import in progress (importId: "
                                + stale.getImportId() + "). Please wait for it to complete.");
            }
            // For non-IMPORTING in-progress states (PREVIEWING, PARSING, VALIDATING)
            // that are not yet stale: they don't hold the distributed lock, so we
            // allow the new import to proceed. The old preview context will be
            // overwritten or garbage-collected from the cache.
        }
    }

    private ImportExecuteResult doExecute(ImportContext context, ImportRequest request) {
        PluginManifestExtended manifest = context.getManifest();
        String importId = context.getHistory().getImportId();
        Long tenantId = MetaContext.getCurrentTenantId();
        Instant startTime = Instant.now();

        ImportExecuteResult result = ImportExecuteResult.builder()
                .importId(importId)
                .pluginId(manifest.getPluginId())
                .namespace(manifest.getNamespace())
                .version(manifest.getVersion())
                .startedAt(startTime)
                .build();

        try {
            // Update status to importing
            importHistoryMapper.updateStatus(importId, ImportStatus.IMPORTING.code());

            // Create or update plugin record
            String pluginPid = createOrUpdatePlugin(manifest, tenantId);
            result.setPluginPid(pluginPid);

            // Import resources in dependency order
            importResources(context, request, result, pluginPid);

            // Mark as success
            result.setSuccess(true);
            result.setStatus(ImportStatus.SUCCESS);
            result.setCompletedAt(Instant.now());
            result.calculateDuration();

            // Update history
            PluginImportHistory history = context.getHistory();
            history.setPluginPid(pluginPid);
            history.setStatus(ImportStatus.SUCCESS.code());
            history.setCompletedAt(Instant.now());
            // Convert Map<String, Map<String, Integer>> to Map<String, Object>
            Map<String, Object> summary = new HashMap<>();
            result.getResourceCounts().forEach((k, v) -> summary.put(k, v));
            history.setResourceSummary(summary);

            // Compute plugin quality score
            try {
                var validationCtx = PluginValidationContext.builder()
                        .pluginId(manifest.getPluginId())
                        .namespace(manifest.getNamespace())
                        .manifest(manifest)
                        .build();
                var validationResult = validationPipeline.validate(validationCtx);
                history.setQualityScore(qualityScorer.computeScore(manifest, validationResult));
            } catch (Exception ex) {
                log.warn("Failed to compute quality score for plugin {}: {}", manifest.getPluginId(), ex.getMessage());
            }

            importHistoryMapper.updateById(history);

            log.info("Plugin import successful: {} v{}", manifest.getPluginId(), manifest.getVersion());

            applicationEventPublisher.publishEvent(
                new PluginImportCompletedEvent(this, tenantId, manifest.getNamespace())
            );

        } catch (Exception e) {
            log.error("Plugin import failed: {}", e.getMessage(), e);

            result.setSuccess(false);
            result.setStatus(ImportStatus.FAILED);
            result.setErrorMessage(e.getMessage());
            result.setErrorDetail(getStackTrace(e));
            result.setCompletedAt(Instant.now());
            result.calculateDuration();

            throw new PluginException("Import failed: " + e.getMessage(), e);
        } finally {
            // Clean up cache
            importContextCache.remove(importId);
        }

        return result;
    }

    private void markImportFailedInNewTransaction(String importId, Throwable throwable) {
        try {
            String errorMsg = rootErrorMessage(throwable);
            String errorDetail = getStackTrace(throwable);
            TransactionTemplate newTxTemplate = new TransactionTemplate(transactionManager);
            newTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            newTxTemplate.executeWithoutResult(status ->
                    importHistoryMapper.markFailed(importId, errorMsg, errorDetail));
        } catch (Exception markEx) {
            log.error("Failed to mark import history as FAILED: importId={}, error={}",
                    importId, markEx.getMessage(), markEx);
        }
    }

    private String rootErrorMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return (message == null || message.isBlank()) ? throwable.toString() : message;
    }

    private String createOrUpdatePlugin(PluginManifestExtended manifest, Long tenantId) {
        PluginRecord existing = pluginRecordMapper.findByTenantAndPluginId(manifest.getPluginId());

        // Fallback: also check by namespace (unique constraint is on tenant_id+namespace)
        if (existing == null) {
            existing = pluginRecordMapper.findByTenantAndNamespace(manifest.getNamespace());
        }

        // Check for soft-deleted record that may still occupy the unique constraint slot
        if (existing == null) {
            PluginRecord softDeleted = pluginRecordMapper.findByTenantAndNamespaceIncludeDeleted(manifest.getNamespace());
            if (softDeleted != null) {
                // Resurrect the soft-deleted record
                pluginRecordMapper.resurrectPlugin(
                        softDeleted.getPid(),
                        manifest.getPluginId(),
                        manifest.getNamespace(),
                        manifest.getVersion(),
                        manifest.getEffectiveDisplayName(),
                        "installed",
                        false, false, false);
                // Update additional fields
                softDeleted.setDescription(manifest.getDescription());
                softDeleted.setAuthor(manifest.getAuthor());
                softDeleted.setManifest(convertToPluginManifest(manifest));
                pluginRecordMapper.updateById(softDeleted);
                return softDeleted.getPid();
            }
        }

        if (existing != null) {
            // Update existing
            existing.setPluginId(manifest.getPluginId());
            existing.setVersion(manifest.getVersion());
            existing.setDisplayName(manifest.getEffectiveDisplayName());
            existing.setDescription(manifest.getDescription());
            existing.setManifest(convertToPluginManifest(manifest));
            existing.setUpdatedAt(Instant.now());
            pluginRecordMapper.updateById(existing);
            return existing.getPid();
        } else {
            // Create new
            String pid = UlidGenerator.generate();
            PluginRecord record = PluginRecord.builder()
                    .pid(pid)
                    .tenantId(tenantId)
                    .pluginId(manifest.getPluginId())
                    .namespace(manifest.getNamespace())
                    .version(manifest.getVersion())
                    .displayName(manifest.getEffectiveDisplayName())
                    .description(manifest.getDescription())
                    .author(manifest.getAuthor())
                    .status(StatusConstants.INSTALLED)
                    .manifest(convertToPluginManifest(manifest))
                    .installedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            pluginRecordMapper.insert(record);
            return pid;
        }
    }

    private PluginManifest convertToPluginManifest(PluginManifestExtended extended) {
        return PluginManifest.builder()
                .pluginId(extended.getPluginId())
                .namespace(extended.getNamespace())
                .version(extended.getVersion())
                .dslVersion(extended.getDslVersion())
                .pluginType(extended.getPluginType())
                .displayName(extended.getEffectiveDisplayName())
                .description(extended.getDescription())
                .author(extended.getAuthor())
                .homepage(extended.getHomepage())
                .minPlatformVersion(extended.getMinPlatformVersion())
                .dependencySpecs(extended.getEffectiveDependencySpecs())
                .configSchema(extended.getConfigSchema())
                .defaultConfig(extended.getDefaultConfig())
                .requiredPermissions(extended.getRequiredPermissions())
                .providedModels(extended.getProvidedModels())
                .providedCommands(extended.getProvidedCommands())
                .entryPoint(extended.getEntryPoint())
                .metadata(extended.getMetadata())
                .build();
    }

    private void importResources(ImportContext context, ImportRequest request,
                                  ImportExecuteResult result, String pluginPid) {
        PluginManifestExtended manifest = context.getManifest();
        Long tenantId = MetaContext.getCurrentTenantId();
        String importId = context.getHistory().getImportId();

        // Collect model codes for post-processing (publish + sync)
        List<String> importedModelCodes = new ArrayList<>();

        // Import in dependency order using ResourceType's import order
        List<ResourceType> orderedTypes = Arrays.stream(ResourceType.values())
                .sorted(Comparator.comparingInt(ResourceType::getImportOrder))
                .toList();

        for (ResourceType type : orderedTypes) {
            long typeStartNanos = System.nanoTime();
            switch (type) {
                case DICT -> importDicts(manifest, request, result, pluginPid, importId, tenantId);
                case FIELD -> importFields(manifest, request, result, pluginPid, importId, tenantId);
                case MODEL -> importedModelCodes.addAll(
                        importModels(manifest, request, result, pluginPid, importId, tenantId));
                case MODEL_FIELD_BINDING -> importModelFieldBindings(manifest, request, result, pluginPid, importId, tenantId);
                case PERMISSION -> importPermissions(manifest, request, result, pluginPid, importId, tenantId);
                case ROLE -> importRoles(manifest, request, result, pluginPid, importId, tenantId);
                case ROLE_PERMISSION -> importRolePermissions(manifest, request, result, pluginPid, importId, tenantId);
                case MENU -> importMenus(manifest, request, result, pluginPid, importId, tenantId);
                case COMMAND -> {
                    // Document Template: auto-generate missing commands for DOCUMENT models
                    generateDocumentTemplateCommands(manifest);
                    importCommands(manifest, request, result, pluginPid, importId, tenantId);
                }
                case BINDING_RULE -> importBindingRules(manifest, request, result, pluginPid, importId, tenantId);
                case NAMED_QUERY -> importNamedQueries(manifest, request, result, pluginPid, importId, tenantId);
                case PAGE -> {
                    importPages(manifest, request, result, pluginPid, importId, tenantId);
                    // Also import first-class dashboards (config/dashboards/*.json, Plan #8).
                    // These are keyed to the PAGE import stage so they run after models/queries are in place.
                    importDashboards(manifest, request, result, pluginPid, importId, tenantId);
                }
                case SAVED_VIEW -> importSavedViews(manifest, result, tenantId);
                case PROCESS -> importProcesses(manifest, request, result, pluginPid, importId, tenantId);
                case I18N -> importI18nResources(manifest, result, tenantId);
                default -> {} // Skip DICT_ITEM as it's handled with DICT
            }
            long elapsedMs = (System.nanoTime() - typeStartNanos) / 1_000_000;
            log.info("Plugin import stage completed: pluginId={}, type={}, elapsedMs={}",
                    manifest.getPluginId(), type, elapsedMs);
        }

        // Import Drools rules and SLA configs (extension resources — not tracked via
        // PluginResource / ResourceType to avoid enum/DB check-constraint churn).
        importRules(manifest);
        importSlaConfigs(manifest);

        // Post-processing: Auto-publish DRAFT models and sync PUBLISHED models
        autoPublishAndSyncModels(importedModelCodes, request, manifest.getNamespace(), tenantId);

        // Post-processing: Auto-publish DRAFT fields and commands for newly published models.
        // Fields are imported BEFORE models (importOrder FIELD=20 < MODEL=30), so field autoPublish
        // at create time skips fields whose model is still draft. Publish them now.
        if (Boolean.TRUE.equals(request.getAutoPublishFields())) {
            int fieldCount = 0;
            for (String modelCode : importedModelCodes) {
                for (var field : metaFieldService.findByStatus("draft")) {
                    if (field.getCode() != null && field.getCode().startsWith(manifest.getNamespace() + "_")) {
                        try {
                            metaFieldService.publishVersion(field.getPid());
                            fieldCount++;
                        } catch (Exception e) {
                            log.warn("Failed to auto-publish field {}: {}", field.getCode(), e.getMessage());
                        }
                    }
                }
                break; // Only need one iteration to get all namespace fields
            }
            if (fieldCount > 0) log.info("Post-import: auto-published {} draft fields", fieldCount);
        }

        if (Boolean.TRUE.equals(request.getAutoPublishCommands())) {
            int cmdCount = 0;
            for (String modelCode : importedModelCodes) {
                for (var cmd : commandService.listByModelCode(modelCode)) {
                    if ("draft".equalsIgnoreCase(cmd.getStatus())) {
                        try {
                            commandService.publish(cmd.getPid());
                            cmdCount++;
                        } catch (Exception e) {
                            log.warn("Failed to auto-publish command {}: {}", cmd.getCode(), e.getMessage());
                        }
                    }
                }
            }
            if (cmdCount > 0) log.info("Post-import: auto-published {} draft commands", cmdCount);
        }

        // Post-processing: Auto-link menus to pages by pageKey
        linkMenusToPages(pluginPid, tenantId);
    }

    /**
     * Auto-publish DRAFT models and sync schema for already-PUBLISHED ENTITY models.
     * Called after all field bindings are imported, so models have their full field set.
     *
     * For DRAFT models: publish.
     * For PUBLISHED ENTITY models: sync schema (adds any new columns from new field bindings).
     */
    private void autoPublishAndSyncModels(
            List<String> modelCodes, ImportRequest request, String pluginNamespace, Long tenantId) {
        if (modelCodes.isEmpty() || !Boolean.TRUE.equals(request.getAutoPublishModels())) {
            return;
        }

        for (String modelCode : modelCodes) {
            MetaModelDTO model = metaModelService.findByCode(modelCode);
            if (model == null) {
                log.warn("Model not found for post-processing: {}", modelCode);
                continue;
            }

            if (model.isDraft()) {
                // Publish DRAFT model. VIEW models are also published here to enable runtime permissions/routes.
                log.info("Auto-publishing DRAFT model: {}", modelCode);
                metaModelService.publish(model.getPid(), "Auto-published during plugin import");
            } else if (model.isPublished() && !"view".equals(model.getModelType())) {
                // Sync schema for PUBLISHED ENTITY model → adds any new columns
                log.info("Syncing schema for PUBLISHED model: {}", modelCode);
                SchemaOperationResult syncResult = schemaManagementService.syncModelToTable(
                        modelCode, SchemaSyncOptions.builder()
                                .syncMode(SchemaSyncOptions.SyncMode.SAFE)
                                .createIndexes(true)
                                .build());
                if (syncResult.isSuccess()) {
                    log.info("Schema sync for {}: {}", modelCode, syncResult.getMessage());
                } else {
                    log.warn("Schema sync failed for {}: {}", modelCode, syncResult.getErrorMessage());
                }

                // Ensure hierarchical permissions exist (idempotent — skips if already created)
                autoPermissionAssignmentService.autoAssignPermissions(modelCode, pluginNamespace, tenantId);
            }
        }
    }

    /**
     * Auto-link menus to pages after all resources are imported.
     * Matches menus that have pageKey in extension with pages that have matching page_key.
     */
    private void linkMenusToPages(String pluginPid, Long tenantId) {
        int linkedCount = menuMapper.linkMenusToPagesByPageKey(tenantId, pluginPid);
        if (linkedCount > 0) {
            log.info("Auto-linked {} menus to pages by pageKey for plugin {}", linkedCount, pluginPid);
        }
    }

    private void importDicts(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getDicts() == null) return;

        for (DictDefinitionDTO dict : manifest.getDicts()) {
            if (!dict.isValid()) {
                log.warn("Skipping invalid dict entry (missing code): index={}", manifest.getDicts().indexOf(dict));
                continue;
            }
            PluginResource resource = resourceImporter.importDict(dict, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, dict);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.DICT, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.DICT, resource.getResourcePid());
                }
            }
        }
    }

    private void importFields(PluginManifestExtended manifest, ImportRequest request,
                              ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getFields() == null) return;

        for (FieldDefinitionDTO field : manifest.getFields()) {
            if (!field.isValid()) {
                log.warn("Skipping invalid field entry (missing code/dataType): index={}", manifest.getFields().indexOf(field));
                continue;
            }
            PluginResource resource = resourceImporter.importField(field, pluginPid, importId, tenantId,
                    request.getConflictStrategy(), request.getAutoPublishFields());
            if (resource != null) {
                captureImportSnapshot(resource, field);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.FIELD, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.FIELD, resource.getResourcePid());
                }
            }
        }
    }

    /**
     * Import models and return list of model codes that were created or updated (not skipped).
     */
    private List<String> importModels(PluginManifestExtended manifest, ImportRequest request,
                              ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        List<String> importedModelCodes = new ArrayList<>();
        if (manifest.getModels() == null) return importedModelCodes;

        for (ModelDefinitionDTO model : manifest.getModels()) {
            if (!model.isValid()) {
                log.warn("Skipping invalid model entry (missing code): index={}", manifest.getModels().indexOf(model));
                continue;
            }
            PluginResource resource = resourceImporter.importModel(model, pluginPid, importId, tenantId,
                    request.getConflictStrategy(), request.getAutoPublishModels());
            if (resource != null) {
                captureImportSnapshot(resource, model);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.MODEL, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.MODEL, resource.getResourcePid());
                }
                // Track model codes for post-processing (publish/sync)
                if (resource.getActionEnum() != ResourceAction.SKIP) {
                    importedModelCodes.add(model.getCode());
                }
            }
        }
        return importedModelCodes;
    }

    private void importModelFieldBindings(PluginManifestExtended manifest, ImportRequest request,
                                          ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getModelFieldBindings() == null) return;

        for (ModelFieldBindingDTO binding : manifest.getModelFieldBindings()) {
            if (!binding.isValid()) {
                log.warn("Skipping invalid model-field binding (missing modelCode/fieldCode): index={}", manifest.getModelFieldBindings().indexOf(binding));
                continue;
            }
            PluginResource resource = resourceImporter.importModelFieldBinding(binding, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, binding);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.MODEL_FIELD_BINDING, resource.getActionEnum());
            }
        }
    }

    private void importPermissions(PluginManifestExtended manifest, ImportRequest request,
                                   ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getPermissions() == null) return;

        for (PermissionDefinitionDTO permission : manifest.getPermissions()) {
            if (!permission.isValid()) {
                log.warn("Skipping invalid permission entry (missing code): index={}", manifest.getPermissions().indexOf(permission));
                continue;
            }
            PluginResource resource = resourceImporter.importPermission(permission, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, permission);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.PERMISSION, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.PERMISSION, resource.getResourcePid());
                }
            }
        }

        bindImportedPermissionsToTenantAdmin(manifest.getPermissions(), tenantId);
    }

    private void bindImportedPermissionsToTenantAdmin(List<PermissionDefinitionDTO> permissions, Long tenantId) {
        if (permissions == null || permissions.isEmpty()) return;
        if (tenantId == null) return;

        Role tenantAdminRole = roleService.findByTenantId(tenantId).stream()
                .filter(role -> "tenant_admin".equals(role.getCode()))
                .findFirst()
                .orElse(null);
        if (tenantAdminRole == null) {
            log.warn("tenant_admin role not found, skip binding imported permissions: tenantId={}", tenantId);
            return;
        }

        Set<Long> boundPermissionIds = permissionService.findRolePermissions(tenantAdminRole.getId()).stream()
                .map(PermissionDTO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (PermissionDefinitionDTO permission : permissions) {
            try {
                PermissionDTO permissionDTO = permissionService.findByCode(permission.getCode());
                if (permissionDTO == null) {
                    log.warn("Imported permission not found after import: code={}", permission.getCode());
                    continue;
                }

                //todo check logic
                if (boundPermissionIds.contains(permissionDTO.getId())) {
                    log.warn("Duplicated permission  found: code={}", permission.getCode());

                    continue;
                }
                RolePermission binding = new RolePermission();
                binding.setPid(UniqueIdGenerator.generate());
                binding.setTenantId(tenantId);
                binding.setRoleId(tenantAdminRole.getId());
                binding.setPermissionId(permissionDTO.getId());
                binding.setGrantType(StatusConstants.GRANT);
                binding.setPriority(0);
                binding.setStatus(StatusConstants.ACTIVE);
                binding.setDeletedFlag(false);
                binding.setCreatedAt(Instant.now());
                binding.setUpdatedAt(Instant.now());
                rolePermissionMapper.insert(binding);
                boundPermissionIds.add(permissionDTO.getId());
            } catch (Exception e) {
                // Duplicate bind and stale edge cases should not fail plugin import.
                log.debug("Skip binding permission to tenant_admin: code={}, reason={}",
                        permission.getCode(), e.getMessage());
            }
        }
        userPermissionService.evictRoleUsers(tenantAdminRole.getId());
    }

    private void importRoles(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getRoles() == null) return;

        for (RoleDefinitionDTO role : manifest.getRoles()) {
            if (!role.isValid()) {
                log.warn("Skipping invalid role entry (missing code): index={}", manifest.getRoles().indexOf(role));
                continue;
            }
            PluginResource resource = resourceImporter.importRole(role, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, role);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.ROLE, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.ROLE, resource.getResourcePid());
                }
            }
        }
    }

    private void importRolePermissions(PluginManifestExtended manifest, ImportRequest request,
                                       ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        // Role-permission bindings are handled within importRoles based on role.permissions list
    }

    private void importMenus(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getMenus() == null) return;

        // Clear menu code→id map before processing this batch
        if (resourceImporter instanceof PluginResourceImporterImpl impl) {
            impl.clearMenuCodeMap();
        }

        // Topological sort: ensure parent menus are imported before children
        List<MenuDefinitionDTO> sorted = topologicalSortMenus(manifest.getMenus());

        for (MenuDefinitionDTO menu : sorted) {
            if (!menu.isValid()) {
                log.warn("Skipping invalid menu entry (missing code): index={}", sorted.indexOf(menu));
                continue;
            }
            PluginResource resource = resourceImporter.importMenu(menu, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, menu);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.MENU, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.MENU, resource.getResourcePid());
                }
            }
        }

        // Auto-generate menu i18n records from name:zh-CN / name:en fields
        generateMenuI18nRecords(sorted, tenantId);
    }

    /**
     * Auto-generate i18n records for menus from their localized name fields.
     * Key format: menu.{CODE} (matching frontend auto-derivation in transformMenuForUI).
     */
    private void generateMenuI18nRecords(List<MenuDefinitionDTO> menus, Long tenantId) {
        List<I18nResource> resources = new ArrayList<>();
        for (MenuDefinitionDTO menu : menus) {
            if (menu.getCode() == null || menu.getCode().isBlank()) continue;
            String i18nKey = "menu." + menu.getCode();

            for (Map.Entry<String, String> entry : menu.getAllLocalizedNames().entrySet()) {
                I18nResource res = new I18nResource();
                res.setI18nKey(i18nKey);
                res.setLang(entry.getKey());
                res.setValue(entry.getValue());
                res.setSource(I18nResource.SOURCE_IMPORT);
                res.setRefType("menu");
                res.setStatus(I18nResource.STATUS_APPROVED);
                resources.add(res);
            }
        }

        if (!resources.isEmpty()) {
            int count = i18nResourceService.batchUpsert(resources);
            log.info("Auto-generated {} menu i18n records from localized names", count);
        }
    }

    /**
     * Topological sort: parents before children within the same menu list.
     * Menus whose parentCode references an entry in the same list are placed after that parent.
     * Menus with no parentCode or with an external parentCode keep their original relative order.
     */
    private List<MenuDefinitionDTO> topologicalSortMenus(List<MenuDefinitionDTO> menus) {
        Set<String> codesInList = new HashSet<>();
        for (MenuDefinitionDTO m : menus) {
            codesInList.add(m.getCode());
        }

        // Build adjacency: parentCode → children codes (only for in-list references)
        Map<String, List<MenuDefinitionDTO>> childrenOf = new LinkedHashMap<>();
        List<MenuDefinitionDTO> roots = new ArrayList<>();
        for (MenuDefinitionDTO m : menus) {
            String pc = m.getParentCode();
            if (pc != null && codesInList.contains(pc)) {
                childrenOf.computeIfAbsent(pc, k -> new ArrayList<>()).add(m);
            } else {
                roots.add(m);
            }
        }

        // BFS from roots
        List<MenuDefinitionDTO> sorted = new ArrayList<>(menus.size());
        Deque<MenuDefinitionDTO> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            MenuDefinitionDTO current = queue.poll();
            sorted.add(current);
            List<MenuDefinitionDTO> children = childrenOf.get(current.getCode());
            if (children != null) {
                queue.addAll(children);
            }
        }

        // Safety: if any menus were missed (circular refs), append them
        if (sorted.size() < menus.size()) {
            Set<String> sortedCodes = new HashSet<>();
            for (MenuDefinitionDTO m : sorted) {
                sortedCodes.add(m.getCode());
            }
            for (MenuDefinitionDTO m : menus) {
                if (!sortedCodes.contains(m.getCode())) {
                    sorted.add(m);
                }
            }
        }

        return sorted;
    }

    /**
     * Auto-generate standard commands for DOCUMENT models that have documentConfig.
     * Generated commands are appended to manifest.commands so they go through the normal import pipeline.
     * Plugin-defined commands take precedence (generated commands with duplicate codes are skipped).
     */
    private void generateDocumentTemplateCommands(PluginManifestExtended manifest) {
        if (manifest.getModels() == null) return;

        // Collect existing command codes for dedup
        Set<String> existingCodes = new HashSet<>();
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO cmd : manifest.getCommands()) {
                if (cmd.getCode() != null) existingCodes.add(cmd.getCode());
            }
        }

        List<CommandDefinitionDTO> generated = new ArrayList<>();
        for (ModelDefinitionDTO model : manifest.getModels()) {
            if (!"document".equals(model.getModelCategory())) continue;

            var docConfig = com.auraboot.framework.meta.template.dto.DocumentConfig.fromExtension(model.getExtension());
            if (docConfig == null) continue;

            List<CommandDefinitionDTO> modelCommands = documentCommandGenerator.generateCommands(model, docConfig);
            for (CommandDefinitionDTO cmd : modelCommands) {
                if (!existingCodes.contains(cmd.getCode())) {
                    generated.add(cmd);
                    existingCodes.add(cmd.getCode());
                    log.debug("Document template generated command: {}", cmd.getCode());
                } else {
                    log.debug("Document template skipped (plugin-defined): {}", cmd.getCode());
                }
            }
        }

        if (!generated.isEmpty()) {
            log.info("Document template generated {} commands for {} plugin",
                    generated.size(), manifest.getPluginId());
            if (manifest.getCommands() == null) {
                manifest.setCommands(new ArrayList<>(generated));
            } else {
                manifest.getCommands().addAll(generated);
            }
        }
    }

    private void importCommands(PluginManifestExtended manifest, ImportRequest request,
                                ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getCommands() == null) return;

        for (CommandDefinitionDTO command : manifest.getCommands()) {
            if (!command.isValid()) {
                log.warn("Skipping invalid command entry (missing code/modelCode): index={}", manifest.getCommands().indexOf(command));
                continue;
            }
            PluginResource resource = resourceImporter.importCommand(command, pluginPid, importId, tenantId, request.getConflictStrategy(), request.getAutoPublishCommands());
            if (resource != null) {
                captureImportSnapshot(resource, command);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.COMMAND, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.COMMAND, resource.getResourcePid());
                }
            }
        }
    }

    private void importBindingRules(PluginManifestExtended manifest, ImportRequest request,
                                    ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        // Binding rules are imported with their commands
        if (manifest.getBindingRules() == null) return;

        for (BindingRuleDTO rule : manifest.getBindingRules()) {
            if (!rule.isValid()) {
                log.warn("Skipping invalid binding rule (missing commandCode): index={}", manifest.getBindingRules().indexOf(rule));
                continue;
            }
            PluginResource resource = resourceImporter.importBindingRule(rule, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, rule);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.BINDING_RULE, resource.getActionEnum());
            }
        }
    }

    private void importPages(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getPages() == null) return;

        for (PageSchemaDTO page : manifest.getPages()) {
            if (!page.isValid()) {
                String pageKey = page != null && page.getPageKey() != null ? page.getPageKey() : "<unknown>";
                throw new PluginException("Invalid page '" + pageKey + "': page JSON must use the latest V2 flat " +
                        "format with top-level kind/layout/blocks, and layout/blocks cannot be empty.");
            }
            PluginResource resource = resourceImporter.importPage(page, pluginPid, importId, tenantId,
                    request.getConflictStrategy(), request.getAutoPublishPages());
            if (resource != null) {
                captureImportSnapshot(resource, page);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.PAGE, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.PAGE, resource.getResourcePid());
                }
            }
        }
    }

    /**
     * Import first-class dashboard definitions from {@code config/dashboards/*.json} (Plan #8).
     * Dashboards are emitted as PAGE resource records so the existing resource-tracking
     * infrastructure (PluginResource, rollback) is reused without adding a new ResourceType.
     */
    private void importDashboards(PluginManifestExtended manifest, ImportRequest request,
                                  ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getDashboards() == null || manifest.getDashboards().isEmpty()) return;

        for (com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO dto : manifest.getDashboards()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid dashboard definition: code={}", dto.getCode());
                continue;
            }
            PluginResource resource = resourceImporter.importDashboard(dto, pluginPid, importId, tenantId,
                    request.getConflictStrategy());
            if (resource != null) {
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.PAGE, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.PAGE, resource.getResourcePid());
                }
            }
        }
    }

    private void importProcesses(PluginManifestExtended manifest, ImportRequest request,
                                 ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getProcesses() == null) return;

        for (ProcessDefinitionDTO process : manifest.getProcesses()) {
            if (!process.isValid()) {
                log.warn("Skipping invalid process entry (missing key): index={}", manifest.getProcesses().indexOf(process));
                continue;
            }
            PluginResource resource = resourceImporter.importProcess(process, pluginPid, importId, tenantId,
                    request.getConflictStrategy(), request.getAutoDeployProcesses());
            if (resource != null) {
                captureImportSnapshot(resource, process);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.PROCESS, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.PROCESS, resource.getResourcePid());
                    if (Boolean.TRUE.equals(request.getAutoDeployProcesses())) {
                        result.getDeployedProcesses().add(process.getKey());
                    }
                }
            }
        }
    }

    private void importNamedQueries(PluginManifestExtended manifest, ImportRequest request,
                                    ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        if (manifest.getNamedQueries() == null) return;

        for (NamedQueryDefinitionDTO namedQuery : manifest.getNamedQueries()) {
            if (!namedQuery.isValid()) {
                log.warn("Skipping invalid named query entry (missing code/fromSql): index={}", manifest.getNamedQueries().indexOf(namedQuery));
                continue;
            }
            // Plugin-imported NQs default to PUBLISHED (same rationale as autoPublishModels).
            // JSON without explicit status deserializes to null (Jackson ignores @Builder.Default),
            // and normalizeNamedQueryStatus maps null → "draft". Override to PUBLISHED.
            if (namedQuery.getStatus() == null || "draft".equalsIgnoreCase(namedQuery.getStatus())) {
                namedQuery.setStatus(StatusConstants.PUBLISHED);
            }
            PluginResource resource = resourceImporter.importNamedQuery(
                    namedQuery, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, namedQuery);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.NAMED_QUERY, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.NAMED_QUERY, resource.getResourcePid());
                }
            }
        }
    }

    private void importRules(PluginManifestExtended manifest) {
        if (manifest.getRules() == null || manifest.getRules().isEmpty()) return;
        int created = 0;
        for (BpmRuleDefinitionDTO dto : manifest.getRules()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid rule (missing ruleCode): index={}",
                        manifest.getRules().indexOf(dto));
                continue;
            }
            if (dto.getRuleContent() == null || dto.getRuleContent().isBlank()) {
                log.warn("Skipping rule '{}' — no ruleContent or resolved ruleContentFile",
                        dto.getRuleCode());
                continue;
            }
            droolsRuleService.importRule(dto);
            created++;
        }
        if (created > 0) {
            log.info("Imported {} Drools rule(s) for plugin {}", created, manifest.getPluginId());
        }
    }

    private void importSlaConfigs(PluginManifestExtended manifest) {
        if (manifest.getSlaConfigs() == null || manifest.getSlaConfigs().isEmpty()) return;
        int created = 0;
        for (SlaConfigDefinitionDTO dto : manifest.getSlaConfigs()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid SLA config (missing name): index={}",
                        manifest.getSlaConfigs().indexOf(dto));
                continue;
            }
            slaConfigService.importSlaConfig(dto);
            created++;
        }
        if (created > 0) {
            log.info("Imported {} SLA config(s) for plugin {}", created, manifest.getPluginId());
        }
    }

    private void importI18nResources(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        if (manifest.getI18nResources() == null || manifest.getI18nResources().isEmpty()) return;

        List<I18nResource> resources = new ArrayList<>();
        for (I18nDefinitionDTO dto : manifest.getI18nResources()) {
            if (!dto.isValid()) continue;

            for (Map.Entry<String, String> entry : dto.getAllTranslations().entrySet()) {
                I18nResource resource = new I18nResource();
                resource.setI18nKey(dto.getKey());
                resource.setLang(entry.getKey());
                resource.setValue(entry.getValue());
                resource.setSource(dto.getSource() != null ? dto.getSource() : I18nResource.SOURCE_IMPORT);
                resource.setRefType(dto.getRefType());
                resource.setStatus(I18nResource.STATUS_APPROVED);
                resources.add(resource);
            }
        }

        if (!resources.isEmpty()) {
            int count = i18nResourceService.batchUpsert(resources);
            for (int i = 0; i < count; i++) {
                result.incrementResourceCount(ResourceType.I18N, ResourceAction.CREATE);
            }
            log.info("Imported {} i18n resources ({} translations)", manifest.getI18nResources().size(), count);

            // Auto-compile i18n JSON after import
            i18nCompiler.compileAll();
            log.info("i18n compilation completed after plugin import");
        }
    }

    private void importSavedViews(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        if (manifest.getSavedViews() == null || manifest.getSavedViews().isEmpty()) return;

        for (SavedViewDefinitionDTO dto : manifest.getSavedViews()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid saved view: {}", dto.getName());
                continue;
            }

            String scope = dto.getScope() != null ? dto.getScope() : "global";
            String pageKey = dto.getPageKey();

            // Check if a view with same name+modelCode+viewType+scope already exists
            List<SavedView> existing = savedViewMapper.findGlobalViews(dto.getModelCode(), pageKey);
            SavedView existingView = existing.stream()
                    .filter(v -> v.getName().equals(dto.getName()) && v.getViewType().equals(dto.getViewType()))
                    .findFirst()
                    .orElse(null);

            if (existingView != null) {
                // Update existing view config
                ViewConfig viewConfig = objectMapper.convertValue(dto.getViewConfig(), ViewConfig.class);
                existingView.setViewConfig(viewConfig);
                existingView.setDescription(dto.getDescription());
                existingView.setUpdatedAt(Instant.now());
                if (dto.getIsDefault() != null) existingView.setIsDefault(dto.getIsDefault());
                if (dto.getSortOrder() != null) existingView.setSortOrder(dto.getSortOrder());
                savedViewMapper.updateSavedView(existingView);
                result.incrementResourceCount(ResourceType.SAVED_VIEW, ResourceAction.UPDATE);
                log.info("Updated saved view: {} ({})", dto.getName(), dto.getViewType());
            } else {
                // Create new saved view
                SavedView savedView = new SavedView();
                savedView.setPid(UlidGenerator.generate());
                savedView.setTenantId(tenantId);
                savedView.setName(dto.getName());
                savedView.setDescription(dto.getDescription());
                savedView.setModelCode(dto.getModelCode());
                savedView.setPageKey(pageKey);
                savedView.setScope(scope);
                savedView.setViewType(dto.getViewType());
                ViewConfig viewConfig = objectMapper.convertValue(dto.getViewConfig(), ViewConfig.class);
                savedView.setViewConfig(viewConfig);
                savedView.setIsDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false);
                savedView.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
                savedView.setDeletedFlag(false);
                savedView.setCreatedAt(Instant.now());
                savedView.setUpdatedAt(Instant.now());
                savedViewMapper.insertSavedView(savedView);
                result.incrementResourceCount(ResourceType.SAVED_VIEW, ResourceAction.CREATE);
                log.info("Created saved view: {} ({})", dto.getName(), dto.getViewType());
            }
        }
    }

    // ==================== Rollback ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportExecuteResult rollback(String importId) {
        PluginImportHistory history = importHistoryMapper.findByImportId(importId);
        if (history == null) {
            throw new PluginException("Import not found: " + importId);
        }

        if (!ImportStatus.SUCCESS.code().equals(history.getStatus())) {
            throw new PluginException("Can only rollback successful imports");
        }

        ImportExecuteResult result = ImportExecuteResult.builder()
                .importId(importId)
                .pluginId(history.getPluginId())
                .namespace(history.getNamespace())
                .version(history.getVersion())
                .startedAt(Instant.now())
                .build();

        try {
            // Get resources in reverse order
            List<PluginResource> createdResources = pluginResourceMapper.findCreatedResourcesForRollback(history.getPluginPid());

            // Delete created resources in reverse order
            for (PluginResource resource : createdResources) {
                resourceImporter.rollbackResource(resource);
            }

            // Restore updated resources
            List<PluginResource> updatedResources = pluginResourceMapper.findUpdatedResourcesForRollback(history.getPluginPid());
            for (PluginResource resource : updatedResources) {
                resourceImporter.restoreResource(resource);
            }

            // Clean up resource tracking
            pluginResourceMapper.deleteByPluginPid(history.getPluginPid());

            // Update plugin status or delete if it was a fresh install
            if ("install".equals(history.getImportType())) {
                pluginRecordMapper.softDelete(history.getPluginPid());
            }

            // Update history
            importHistoryMapper.updateStatus(importId, ImportStatus.ROLLED_BACK.code());

            result.setSuccess(true);
            result.setStatus(ImportStatus.ROLLED_BACK);
            result.setCompletedAt(Instant.now());
            result.calculateDuration();

        } catch (Exception e) {
            result.setSuccess(false);
            result.setStatus(ImportStatus.FAILED);
            result.setErrorMessage("Rollback failed: " + e.getMessage());
            throw new PluginException("Rollback failed: " + e.getMessage(), e);
        }

        return result;
    }

    @Override
    public boolean canRollback(String importId) {
        PluginImportHistory history = importHistoryMapper.findByImportId(importId);
        return history != null && ImportStatus.SUCCESS.code().equals(history.getStatus());
    }

    // ==================== History & Status ====================

    @Override
    public List<ImportHistoryDTO> getImportHistory(int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        // Use MyBatis-Plus query with limit
        return importHistoryMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PluginImportHistory>()
                                .eq("tenant_id", tenantId)
                                .orderByDesc("created_at")
                                .last("LIMIT " + limit))
                .stream()
                .map(this::toHistoryDTO)
                .toList();
    }

    @Override
    public List<ImportHistoryDTO> getPluginImportHistory(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return importHistoryMapper.findByTenantAndPluginId(tenantId, pluginId)
                .stream()
                .map(this::toHistoryDTO)
                .toList();
    }

    @Override
    public ImportHistoryDTO getImportStatus(String importId) {
        PluginImportHistory history = importHistoryMapper.findByImportId(importId);
        return history != null ? toHistoryDTO(history) : null;
    }

    @Override
    public boolean cancelImport(String importId) {
        ImportContext context = importContextCache.remove(importId);
        if (context != null) {
            importHistoryMapper.updateStatus(importId, ImportStatus.CANCELLED.code());
            return true;
        }
        return false;
    }

    private ImportHistoryDTO toHistoryDTO(PluginImportHistory history) {
        Map<String, Integer> counts = new HashMap<>();
        if (history.getResourceSummary() != null) {
            history.getResourceSummary().forEach((k, v) -> {
                if (v instanceof Number) {
                    counts.put(k, ((Number) v).intValue());
                }
            });
        }
        return new ImportHistoryDTO(
                history.getImportId(),
                history.getPluginPid(),
                history.getPluginId(),
                history.getNamespace(),
                history.getVersion(),
                history.getStatus(),
                history.getImportType(),
                history.getSourceType(),
                history.getSourceName(),
                history.getStartedAt(),
                history.getCompletedAt(),
                history.getErrorMessage(),
                counts
        );
    }

    // ==================== Validation ====================

    @Override
    public List<String> validateManifest(PluginManifestExtended manifest) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (manifest == null) {
            errors.add("Manifest is null");
            return errors;
        }

        // Sanitize: remove JSON comment objects before validation
        manifest.sanitize();

        if (manifest.getValidationErrors() != null) {
            errors.addAll(manifest.getValidationErrors());
        }

        if (isBlank(manifest.getPluginId())) {
            errors.add("pluginId is required");
        }
        if (isBlank(manifest.getNamespace())) {
            errors.add("namespace is required");
        }
        if (isBlank(manifest.getVersion())) {
            errors.add("version is required");
        }

        // Validate version is valid semver
        if (!isBlank(manifest.getVersion()) && !SemverMatcher.isValid(manifest.getVersion())) {
            errors.add("version '" + manifest.getVersion() + "' is not valid semver format");
        }

        // Validate platform version compatibility (min + max)
        if (!isBlank(manifest.getMinPlatformVersion()) || !isBlank(manifest.getMaxPlatformVersion())) {
            com.auraboot.framework.plugin.service.PlatformVersionChecker.CompatibilityResult versionCheck =
                    platformVersionChecker.check(manifest.getMinPlatformVersion(), manifest.getMaxPlatformVersion());
            switch (versionCheck.status()) {
                case INCOMPATIBLE:
                    errors.add(versionCheck.message());
                    break;
                case WARN_OLDER:
                    // Hard check: treat as validation error (platform too old)
                    errors.add(versionCheck.message());
                    break;
                case WARN_NEWER:
                    // Soft warning: prefix with [WARN] so callers can distinguish from errors
                    errors.add("[WARN] " + versionCheck.message());
                    break;
                case COMPATIBLE:
                    // No action needed
                    break;
            }
        }

        // Validate pluginType
        String pluginType = manifest.getEffectivePluginType();
        if (!List.of("config", "hybrid", "solution").contains(pluginType)) {
            errors.add("Invalid pluginType '" + pluginType + "', must be one of: config, hybrid, solution");
        }

        // Validate dslVersion
        int dslVersion = manifest.getEffectiveDslVersion();
        if (dslVersion < 1) {
            errors.add("dslVersion must be >= 1, got " + dslVersion);
        }

        Set<String> manifestModelCodes = validateCodeList(manifest.getModels(), ModelDefinitionDTO::getCode,
                "Model", errors, true);
        Set<String> manifestFieldCodes = validateCodeList(manifest.getFields(), FieldDefinitionDTO::getCode,
                "Field", errors, true);
        Set<String> manifestDictCodes = validateCodeList(manifest.getDicts(), DictDefinitionDTO::getCode,
                "Dictionary", errors, true);
        Set<String> manifestCommandCodes = validateCodeList(manifest.getCommands(), CommandDefinitionDTO::getCode,
                "Command", errors, true);
        Set<String> manifestPermissionCodes = validateCodeList(manifest.getPermissions(), PermissionDefinitionDTO::getCode,
                "Permission", errors, true);
        Set<String> manifestRoleCodes = validateCodeList(manifest.getRoles(), RoleDefinitionDTO::getCode,
                "Role", errors, true);
        Set<String> manifestMenuCodes = validateCodeList(manifest.getMenus(), MenuDefinitionDTO::getCode,
                "Menu", errors, true);
        validateCodeList(manifest.getPages(), PageSchemaDTO::getPageKey, "Page", errors, false);
        validateCodeList(manifest.getNamedQueries(), NamedQueryDefinitionDTO::getCode,
                "NamedQuery", errors, true);

        validateBasicFields(manifest, errors);

        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Boolean> modelExistsCache = new HashMap<>();
        Map<String, Boolean> fieldExistsCache = new HashMap<>();
        Map<String, Boolean> dictExistsCache = new HashMap<>();
        Map<String, Boolean> permissionExistsCache = new HashMap<>();
        Map<String, Boolean> menuExistsCache = new HashMap<>();

        // Validate field->dict references. Dict can come from current plugin dicts or existing tenant dicts.
        if (manifest.getFields() != null) {
            for (FieldDefinitionDTO field : manifest.getFields()) {
                if (field == null || field.getCode() == null || field.getCode().isBlank()) {
                    continue;
                }
                String dictCode = field.getDictCode();
                if (dictCode == null || dictCode.isBlank()) {
                    continue;
                }
                if (manifestDictCodes.contains(dictCode)) {
                    continue;
                }

                boolean dictExistsInTenant = existsInTenant(tenantId, dictCode, dictExistsCache,
                        code -> resourceImporter.checkDictExists(tenantId, code));
                if (!dictExistsInTenant) {
                    errors.add("Field '" + field.getCode() + "' references missing dictionary: " + dictCode);
                }
            }
        }

        if (manifest.getModelFieldBindings() != null) {
            for (ModelFieldBindingDTO binding : manifest.getModelFieldBindings()) {
                if (binding == null) {
                    continue;
                }
                if (isBlank(binding.getModelCode())) {
                    errors.add("Binding has missing modelCode");
                } else if (!manifestModelCodes.contains(binding.getModelCode())
                        && !existsInTenant(tenantId, binding.getModelCode(), modelExistsCache,
                        code -> resourceImporter.checkModelExists(tenantId, code))) {
                    errors.add("Binding references missing model: " + binding.getModelCode());
                }

                if (isBlank(binding.getFieldCode())) {
                    errors.add("Binding has missing fieldCode");
                } else if (!manifestFieldCodes.contains(binding.getFieldCode())
                        && !existsInTenant(tenantId, binding.getFieldCode(), fieldExistsCache,
                        code -> resourceImporter.checkFieldExists(tenantId, code))) {
                    errors.add("Binding references missing field: " + binding.getFieldCode());
                }
            }
        }

        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO command : manifest.getCommands()) {
                if (command == null || isBlank(command.getCode())) {
                    continue;
                }
                String modelCode = command.getModelCode();
                if (isBlank(modelCode)) {
                    errors.add("Command '" + command.getCode() + "' has missing modelCode");
                } else if (!manifestModelCodes.contains(modelCode)
                        && !existsInTenant(tenantId, modelCode, modelExistsCache,
                        code -> resourceImporter.checkModelExists(tenantId, code))) {
                    errors.add("Command '" + command.getCode() + "' references missing model: " + modelCode);
                }
            }
        }

        if (manifest.getRoles() != null) {
            for (RoleDefinitionDTO role : manifest.getRoles()) {
                if (role == null || isBlank(role.getCode()) || role.getPermissions() == null) {
                    continue;
                }
                for (String permissionCode : role.getPermissions()) {
                    if (isBlank(permissionCode)) {
                        continue;
                    }
                    if (!manifestPermissionCodes.contains(permissionCode)
                            && !existsInTenant(tenantId, permissionCode, permissionExistsCache,
                            code -> resourceImporter.checkPermissionExists(tenantId, code))) {
                        errors.add("Role '" + role.getCode() + "' references missing permission: " + permissionCode);
                    }
                }
            }
        }

        if (manifest.getMenus() != null) {
            for (MenuDefinitionDTO menu : manifest.getMenus()) {
                if (menu == null || isBlank(menu.getCode())) {
                    continue;
                }

                if (!isBlank(menu.getParentCode())
                        && !manifestMenuCodes.contains(menu.getParentCode())
                        && !existsInTenant(tenantId, menu.getParentCode(), menuExistsCache,
                        code -> resourceImporter.checkMenuExists(tenantId, code))) {
                    errors.add("Menu '" + menu.getCode() + "' references missing parent menu: " + menu.getParentCode());
                }

                if (!isBlank(menu.getPermissionCode())
                        && !manifestPermissionCodes.contains(menu.getPermissionCode())
                        && !existsInTenant(tenantId, menu.getPermissionCode(), permissionExistsCache,
                        code -> resourceImporter.checkPermissionExists(tenantId, code))) {
                    errors.add("Menu '" + menu.getCode() + "' references missing permission: " + menu.getPermissionCode());
                }

                if (!isBlank(menu.getModelCode())
                        && !manifestModelCodes.contains(menu.getModelCode())
                        && !existsInTenant(tenantId, menu.getModelCode(), modelExistsCache,
                        code -> resourceImporter.checkModelExists(tenantId, code))) {
                    errors.add("Menu '" + menu.getCode() + "' references missing model: " + menu.getModelCode());
                }
            }
        }

        if (manifest.getPages() != null) {
            // Keys that must live under "extension": silently dropping them has bitten us before.
            Set<String> mustBeInExtension = Set.of(
                    "options", "views", "kanbanConfig", "galleryConfig", "calendarConfig",
                    "enableMultiView", "defaultFilters", "columns", "dataSource", "relatedPages");
            for (PageSchemaDTO page : manifest.getPages()) {
                if (page == null || isBlank(page.getPageKey())) {
                    continue;
                }
                if (isBlank(page.getKind())) {
                    errors.add("Page '" + page.getPageKey() + "' has missing kind");
                }
                Map<String, Object> unknown = page.getUnknownFields();
                if (unknown != null) {
                    for (String key : unknown.keySet()) {
                        if (mustBeInExtension.contains(key)) {
                            errors.add("Page '" + page.getPageKey() + "' has top-level '" + key
                                    + "' which is not recognized — move it under \"extension\": { \"" + key + "\": ... }");
                        }
                    }
                }
            }
        }

        return errors;
    }

    /**
     * Validate required fields beyond just code/key for all DSL resource types.
     * This catches cases where a resource has a code but is missing other mandatory fields
     * that would cause DB constraint violations or runtime failures.
     */
    private void validateBasicFields(PluginManifestExtended manifest, List<String> errors) {
        // Fields: require dataType
        if (manifest.getFields() != null) {
            for (FieldDefinitionDTO field : manifest.getFields()) {
                if (field == null || isBlank(field.getCode())) continue;
                if (isBlank(field.getDataType())) {
                    errors.add("Field '" + field.getCode() + "' has missing dataType");
                }
            }
        }

        // Menus: require at least one name source (localized, legacy, or code fallback already handled,
        // but type must be valid)
        if (manifest.getMenus() != null) {
            for (MenuDefinitionDTO menu : manifest.getMenus()) {
                if (menu == null || isBlank(menu.getCode())) continue;
                if (menu.getType() != null && menu.getType() < 0 || menu.getType() != null && menu.getType() > 2) {
                    errors.add("Menu '" + menu.getCode() + "' has invalid type: " + menu.getType() + " (must be 0=directory, 1=menu, 2=button)");
                }
                // Validate parentCode references within manifest
                if (!isBlank(menu.getParentCode())) {
                    boolean parentInManifest = manifest.getMenus().stream()
                            .anyMatch(m -> menu.getParentCode().equals(m.getCode()));
                    if (!parentInManifest) {
                        // Parent might exist in DB — just a warning, not an error
                        // (will be resolved at import time)
                    }
                }
            }
        }

        // Pages: require pageKey (already covered by validateCodeList) + kind for DSL pages
        if (manifest.getPages() != null) {
            for (PageSchemaDTO page : manifest.getPages()) {
                if (page == null || isBlank(page.getPageKey())) continue;
                // kind is required in V2 format
            }
        }

        // Models: require modelType
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO model : manifest.getModels()) {
                if (model == null || isBlank(model.getCode())) continue;
                if (isBlank(model.getModelType())) {
                    errors.add("Model '" + model.getCode() + "' has missing modelType");
                }
            }
        }

        // Commands: require modelCode (already covered above) + type
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO command : manifest.getCommands()) {
                if (command == null || isBlank(command.getCode())) continue;
                if (isBlank(command.getType())) {
                    errors.add("Command '" + command.getCode() + "' has missing type");
                }
            }
        }

        // NamedQueries: require fromSql
        if (manifest.getNamedQueries() != null) {
            for (NamedQueryDefinitionDTO nq : manifest.getNamedQueries()) {
                if (nq == null || isBlank(nq.getCode())) continue;
                if (isBlank(nq.getFromSql())) {
                    errors.add("NamedQuery '" + nq.getCode() + "' has missing fromSql");
                }
            }
        }

        // Dicts: require at least code (name defaults to code via getEffectiveName)
        // Items validation: each item should have value
        if (manifest.getDicts() != null) {
            for (DictDefinitionDTO dict : manifest.getDicts()) {
                if (dict == null || isBlank(dict.getCode())) continue;
                if (dict.getItems() != null) {
                    for (int i = 0; i < dict.getItems().size(); i++) {
                        var item = dict.getItems().get(i);
                        if (item == null || isBlank(item.getValue())) {
                            errors.add("Dict '" + dict.getCode() + "' item[" + i + "] has missing value");
                        }
                    }
                }
            }
        }

        // Processes: require key + bpmn content
        if (manifest.getProcesses() != null) {
            for (ProcessDefinitionDTO process : manifest.getProcesses()) {
                if (process == null || isBlank(process.getKey())) continue;
                if (process.getBpmnFile() == null && process.getBpmnContent() == null && process.getDesignerJson() == null) {
                    errors.add("Process '" + process.getKey() + "' has no bpmnFile, bpmnContent, or designerJson");
                }
            }
        }

        // SavedViews: require modelCode + viewType + name
        if (manifest.getSavedViews() != null) {
            for (SavedViewDefinitionDTO sv : manifest.getSavedViews()) {
                if (sv == null) continue;
                if (isBlank(sv.getModelCode())) {
                    errors.add("SavedView '" + sv.getName() + "' has missing modelCode");
                }
                if (isBlank(sv.getViewType())) {
                    errors.add("SavedView '" + sv.getName() + "' has missing viewType");
                }
                if (isBlank(sv.getName())) {
                    errors.add("SavedView has missing name");
                }
            }
        }
    }

    private <T> Set<String> validateCodeList(List<T> list, Function<T, String> codeExtractor,
                                             String resourceName, List<String> errors, boolean useCodeLabel) {
        if (list == null || list.isEmpty()) {
            return Set.of();
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (T item : list) {
            if (item == null) {
                continue;
            }
            String code = normalizeCode(codeExtractor.apply(item));
            if (code == null) {
                errors.add(resourceName + " has missing " + (useCodeLabel ? "code" : "pageKey"));
                continue;
            }
            if (!seen.add(code)) {
                duplicated.add(code);
            }
        }
        for (String dup : duplicated) {
            errors.add("Duplicate " + resourceName + " " + (useCodeLabel ? "code" : "pageKey") + ": " + dup);
        }
        return seen;
    }

    private boolean existsInTenant(Long tenantId, String key, Map<String, Boolean> cache, Function<String, Boolean> loader) {
        if (tenantId == null || isBlank(key)) {
            return false;
        }
        return cache.computeIfAbsent(key, k -> {
            try {
                return Boolean.TRUE.equals(loader.apply(k));
            } catch (Exception ex) {
                log.debug("Tenant existence check failed: key={}, message={}", k, ex.getMessage());
                return false;
            }
        });
    }

    private String normalizeCode(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Build validation context and run the pre-flight pipeline.
     */
    private PluginValidationResult runValidationPipeline(PluginManifestExtended manifest) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Collect installed plugin dependencies for cycle detection
        Map<String, List<String>> installedPluginDeps = new HashMap<>();
        Set<String> installedPluginIds = new HashSet<>();
        try {
            List<PluginRecord> allPlugins = pluginRecordMapper.selectList(
                    new LambdaQueryWrapper<PluginRecord>().eq(PluginRecord::getTenantId, tenantId));
            for (PluginRecord p : allPlugins) {
                installedPluginIds.add(p.getPluginId());
                if (p.getManifest() != null && p.getManifest().getDependencies() != null) {
                    installedPluginDeps.put(p.getPluginId(), p.getManifest().getDependencies());
                }
            }
        } catch (Exception e) {
            log.debug("Could not load installed plugins for validation: {}", e.getMessage());
        }

        // Load installed resource codes from DB for cross-plugin reference validation
        Set<String> installedModelCodes = new HashSet<>();
        Set<String> installedFieldCodes = new HashSet<>();
        Set<String> installedPermissionCodes = new HashSet<>();
        Set<String> installedCommandCodes = new HashSet<>();
        Set<String> installedNamedQueryCodes = new HashSet<>();
        try {
            // Collect all model codes and field codes from the manifest's referenced models
            // that exist in the tenant (checking via resourceImporter)
            Set<String> referencedModels = new HashSet<>();
            Set<String> referencedFields = new HashSet<>();
            if (manifest.getCommands() != null) {
                manifest.getCommands().forEach(cmd -> {
                    if (cmd != null && cmd.getModelCode() != null) referencedModels.add(cmd.getModelCode());
                });
            }
            if (manifest.getModelFieldBindings() != null) {
                manifest.getModelFieldBindings().forEach(b -> {
                    if (b != null) {
                        if (b.getModelCode() != null) referencedModels.add(b.getModelCode());
                        if (b.getFieldCode() != null) referencedFields.add(b.getFieldCode());
                    }
                });
            }
            // Only check external references (not in manifest's own models/fields)
            Set<String> manifestModelCodes = new HashSet<>();
            if (manifest.getModels() != null) {
                manifest.getModels().forEach(m -> { if (m != null && m.getCode() != null) manifestModelCodes.add(m.getCode()); });
            }
            Set<String> manifestFieldCodes = new HashSet<>();
            if (manifest.getFields() != null) {
                manifest.getFields().forEach(f -> { if (f != null && f.getCode() != null) manifestFieldCodes.add(f.getCode()); });
            }
            for (String modelCode : referencedModels) {
                if (!manifestModelCodes.contains(modelCode) && resourceImporter.checkModelExists(tenantId, modelCode)) {
                    installedModelCodes.add(modelCode);
                }
            }
            for (String fieldCode : referencedFields) {
                if (!manifestFieldCodes.contains(fieldCode) && resourceImporter.checkFieldExists(tenantId, fieldCode)) {
                    installedFieldCodes.add(fieldCode);
                }
            }
            // Collect installed command/NQ codes for capability dependency validation
            if (manifest.getRequires() != null) {
                for (var req : manifest.getRequires()) {
                    if (req == null || req.getCode() == null) continue;
                    if ("model".equals(req.getType()) && resourceImporter.checkModelExists(tenantId, req.getCode())) {
                        installedModelCodes.add(req.getCode());
                    } else if ("command".equals(req.getType()) && resourceImporter.checkCommandExists(tenantId, req.getCode())) {
                        installedCommandCodes.add(req.getCode());
                    } else if ("query".equals(req.getType()) && resourceImporter.checkNamedQueryExists(tenantId, req.getCode())) {
                        installedNamedQueryCodes.add(req.getCode());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not load installed resources for validation: {}", e.getMessage());
        }

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId(manifest.getPluginId())
                .namespace(manifest.getNamespace())
                .manifest(manifest)
                .installedModelCodes(installedModelCodes)
                .installedFieldCodes(installedFieldCodes)
                .installedPermissionCodes(installedPermissionCodes)
                .installedCommandCodes(installedCommandCodes)
                .installedNamedQueryCodes(installedNamedQueryCodes)
                .installedPluginIds(installedPluginIds)
                .installedPluginDependencies(installedPluginDeps)
                .build();

        return validationPipeline.validate(ctx);
    }

    @Override
    public List<ImportPreviewResult.ResourceConflict> checkConflicts(PluginManifestExtended manifest) {
        List<ImportPreviewResult.ResourceConflict> conflicts = new ArrayList<>();
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null || manifest == null) {
            return conflicts;
        }

        String importingPluginId = manifest.getPluginId();
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.MODEL, manifest.getModels(),
                ModelDefinitionDTO::getCode, "Model");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.FIELD, manifest.getFields(),
                FieldDefinitionDTO::getCode, "Field");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.COMMAND, manifest.getCommands(),
                CommandDefinitionDTO::getCode, "Command");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.PERMISSION, manifest.getPermissions(),
                PermissionDefinitionDTO::getCode, "Permission");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.ROLE, manifest.getRoles(),
                RoleDefinitionDTO::getCode, "Role");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.MENU, manifest.getMenus(),
                MenuDefinitionDTO::getCode, "Menu");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.PROCESS, manifest.getProcesses(),
                ProcessDefinitionDTO::getKey, "Process");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.PAGE, manifest.getPages(),
                PageSchemaDTO::getPageKey, "Page");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.DICT, manifest.getDicts(),
                DictDefinitionDTO::getCode, "Dictionary");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                manifest.getModelFieldBindings(), b -> b.getModelCode() + "." + b.getFieldCode(), "ModelFieldBinding");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.I18N, manifest.getI18nResources(),
                I18nDefinitionDTO::getKey, "I18n");

        return conflicts;
    }

    private <T> void collectConflicts(
            List<ImportPreviewResult.ResourceConflict> conflicts,
            String importingPluginId,
            Long tenantId,
            ResourceType resourceType,
            List<T> resources,
            Function<T, String> codeExtractor,
            String label) {
        if (resources == null || resources.isEmpty()) {
            return;
        }

        for (T resource : resources) {
            String code = codeExtractor.apply(resource);
            if (code == null || code.isBlank()) {
                continue;
            }

            PluginResource existing;
            try {
                existing = pluginResourceMapper.findByTypeAndCode(
                        tenantId, resourceType.name(), code);
            } catch (Exception ex) {
                // Conflict preview must be best-effort; duplicated historical rows should not block import.
                log.warn("Skip conflict check for {} {} due to lookup error: {}",
                        resourceType, code, ex.getMessage());
                continue;
            }
            if (existing == null) {
                continue;
            }

            String ownerPluginId = resolveOwnerPluginId(existing.getPluginPid());
            if (ownerPluginId != null && ownerPluginId.equals(importingPluginId)) {
                continue;
            }

            conflicts.add(ImportPreviewResult.ResourceConflict.builder()
                    .resourceType(resourceType)
                    .resourceCode(code)
                    .conflictType("different_plugin")
                    .ownerPluginId(ownerPluginId != null ? ownerPluginId : existing.getPluginPid())
                    .description(label + " owned by different plugin")
                    .build());
        }
    }

    private String resolveOwnerPluginId(String pluginPid) {
        if (pluginPid == null || pluginPid.isBlank()) {
            return null;
        }
        PluginRecord ownerRecord = pluginRecordMapper.findByPid(pluginPid);
        if (ownerRecord != null && ownerRecord.getPluginId() != null && !ownerRecord.getPluginId().isBlank()) {
            return ownerRecord.getPluginId();
        }
        return pluginPid;
    }

    @Override
    public ImportPreviewResult.DependencyAnalysis analyzeDependencies(PluginManifestExtended manifest) {
        List<String> missingDependencies = new ArrayList<>();
        List<ImportPreviewResult.PluginDependency> pluginDeps = new ArrayList<>();

        Long tenantId = MetaContext.getCurrentTenantId();

        // Use structured dependency specs (supports version constraints)
        List<PluginManifest.PluginDependencySpec> specs = manifest.getEffectiveDependencySpecs();
        for (PluginManifest.PluginDependencySpec spec : specs) {
            String depPluginId = spec.getPluginId();
            String requiredRange = spec.getVersionRange();

            PluginRecord dep = pluginRecordMapper.findByTenantAndPluginId(depPluginId);
            if (dep == null) {
                missingDependencies.add("Plugin: " + depPluginId
                        + (!"*".equals(requiredRange) ? " " + requiredRange : ""));
                pluginDeps.add(ImportPreviewResult.PluginDependency.builder()
                        .pluginId(depPluginId)
                        .requiredVersion(requiredRange)
                        .satisfied(false)
                        .build());
            } else {
                boolean versionSatisfied = SemverMatcher.matches(dep.getVersion(), requiredRange);
                if (!versionSatisfied) {
                    missingDependencies.add("Plugin: " + depPluginId
                            + " requires " + requiredRange + ", installed: " + dep.getVersion());
                }
                pluginDeps.add(ImportPreviewResult.PluginDependency.builder()
                        .pluginId(depPluginId)
                        .requiredVersion(requiredRange)
                        .installedVersion(dep.getVersion())
                        .satisfied(versionSatisfied)
                        .build());
            }
        }

        return ImportPreviewResult.DependencyAnalysis.builder()
                .pluginDependencies(pluginDeps)
                .missingDependencies(missingDependencies)
                .satisfied(missingDependencies.isEmpty())
                .build();
    }

    // ==================== Helper Methods ====================

    private String detectSourceType(String fileName) {
        if (fileName == null) {
            return "json";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".zip")) {
            return "zip";
        }
        return "json";
    }

    private String getStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Save or update a plugin resource record.
     * If a resource with the same (tenant, plugin, type, code) already exists, update it.
     * Otherwise, insert a new record.
     */
    private void saveOrUpdatePluginResource(PluginResource resource, Long tenantId) {
        PluginResource existing = pluginResourceMapper.findByTenantPluginAndResource(
                tenantId, resource.getPluginPid(), resource.getResourceType(), resource.getResourceCode());

        // Also check across plugin PIDs (resource may have been tracked under a different plugin PID)
        if (existing == null) {
            existing = pluginResourceMapper.findByTypeAndCode(
                    tenantId, resource.getResourceType(), resource.getResourceCode());
        }

        if (existing != null) {
            // Update existing record
            existing.setPluginPid(resource.getPluginPid());
            existing.setImportId(resource.getImportId());
            existing.setResourcePid(resource.getResourcePid());
            existing.setResourceId(resource.getResourceId());
            existing.setResourceName(resource.getResourceName());
            existing.setAction(resource.getAction());
            existing.setPreviousState(resource.getPreviousState());
            existing.setCurrentState(resource.getCurrentState());
            // Sync import snapshot and reset user modification tracking
            existing.setImportSnapshot(resource.getImportSnapshot());
            existing.setUserModified(false);
            existing.setUserModifiedAt(null);
            existing.setUpdatedAt(Instant.now());
            pluginResourceMapper.updateById(existing);
        } else {
            // Insert new record
            pluginResourceMapper.insert(resource);
        }
    }

    /**
     * Capture the manifest DTO as an import snapshot on the resource.
     * This snapshot serves as the baseline for detecting user modifications.
     * Skipped resources don't get snapshots since nothing was imported.
     */
    private void captureImportSnapshot(PluginResource resource, Object manifestDto) {
        if (resource != null && resource.getActionEnum() != ResourceAction.SKIP && manifestDto != null) {
            try {
                Map<String, Object> snapshot = objectMapper.convertValue(manifestDto,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                resource.setImportSnapshot(snapshot);
            } catch (Exception e) {
                log.warn("Failed to capture import snapshot for {}: {}", resource.getResourceCode(), e.getMessage());
            }
        }
    }

    /**
     * Context for in-progress imports.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ImportContext {
        private PluginManifestExtended manifest;
        private PluginImportHistory history;
        private ImportPreviewResult previewResult;
        private Map<String, byte[]> additionalFiles;

        ImportContext(PluginManifestExtended manifest, PluginImportHistory history, ImportPreviewResult previewResult) {
            this.manifest = manifest;
            this.history = history;
            this.previewResult = previewResult;
            this.additionalFiles = new HashMap<>();
        }
    }
}
