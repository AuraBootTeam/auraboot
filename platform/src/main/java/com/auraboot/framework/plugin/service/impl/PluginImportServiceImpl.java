package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentCreateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentVersionCreateRequest;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.service.ConditionFragmentService;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationTemplateMapper;
import com.auraboot.framework.meta.contribution.PageSchemaContributionImportService;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.view.entity.SavedView;
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
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.CommandActionDeriver;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import com.auraboot.framework.semantic.exception.SemanticYamlInvalidException;
import com.auraboot.framework.semantic.service.SemanticPublishService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final com.auraboot.framework.meta.service.FieldMaskService fieldMaskService;
    private final com.auraboot.framework.permission.capability.CapabilityRegistryService capabilityRegistryService;
    private final SchemaManagementService schemaManagementService;
    private final UserPermissionService userPermissionService;
    private final DistributedLock distributedLock;
    private final I18nResourceService i18nResourceService;
    private final I18nService i18nService;
    private final I18nCompiler i18nCompiler;
    private final PlatformProperties platformProperties;
    private final com.auraboot.framework.plugin.service.PlatformVersionChecker platformVersionChecker;
    private final PluginValidationPipeline validationPipeline;
    private final PluginQualityScorer qualityScorer;
    private final com.auraboot.framework.plugin.validation.PageSchemaImportGate pageSchemaImportGate;
    private final SavedViewMapper savedViewMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final PageSchemaContributionImportService pageSchemaContributionImportService;
    private final NotificationTemplateMapper notificationTemplateMapper;
    private final AutoPermissionAssignmentService autoPermissionAssignmentService;
    private final CommandActionDeriver commandActionDeriver;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final com.auraboot.framework.meta.template.generator.DocumentCommandGenerator documentCommandGenerator;
    private final AutomationService automationService;
    private final DrtDefinitionService drtDefinitionService;
    private final DecisionVersionService decisionVersionService;
    private final ConditionFragmentService conditionFragmentService;
    private final EventPolicyDefinitionService eventPolicyDefinitionService;
    private final EventPolicyVersionService eventPolicyVersionService;
    private final SemanticPublishService semanticPublishService;
    private final JdbcTemplate jdbcTemplate;
    private final IdentifierMappingDataMigrator identifierMappingDataMigrator;
    /** Used by {@link #verifyImportReferenceIntegrity()} to enumerate the tenant's commands. */
    private final com.auraboot.framework.meta.mapper.CommandDefinitionMapper commandDefinitionMapper;

    private final ObjectMapper objectMapper = createObjectMapper();
    private final AtomicBoolean pluginResourceTypeConstraintChecked = new AtomicBoolean(false);

    // Cache for in-progress imports (importId -> manifest)
    private final Map<String, ImportContext> importContextCache = new ConcurrentHashMap<>();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
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
        return parseDirectory(directoryPath, false);
    }

    @Override
    public ImportPreviewResult parseDirectory(String directoryPath, boolean deferReferenceValidation) {
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
            return createPreviewFromManifest(manifest, directoryPath, "directory", deferReferenceValidation);
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
            new PluginZipResourceReader(objectMapper).loadResourcesFromZipFiles(manifest, files);

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

    private ImportPreviewResult createPreviewFromManifest(PluginManifestExtended manifest, String sourceName, String sourceType) {
        return createPreviewFromManifest(manifest, sourceName, sourceType, false);
    }

    private ImportPreviewResult createPreviewFromManifest(PluginManifestExtended manifest, String sourceName,
                                                          String sourceType, boolean deferReferenceValidation) {
        // Fail-fast preview when the manifest is missing pluginId. The downstream
        // ab_plugin_import_history.plugin_id column is NOT NULL; without this guard a
        // malformed manifest (e.g. a plugin.json that uses `code` instead of `pluginId`,
        // or `resources` instead of `resourceDirs`) hit a PSQL constraint error and
        // surfaced to the caller as the generic "Internal system error" — burning hours
        // of operator triage time. Returning a structured preview keeps the response
        // shape consistent with other validation failures (controller wraps `errors`
        // into `errorMessage`).
        if (manifest.getPluginId() == null || manifest.getPluginId().isBlank()) {
            return ImportPreviewResult.builder()
                    .importId(UlidGenerator.generate())
                    .valid(false)
                    .errors(List.of("plugin manifest missing required field `pluginId` "
                            + "(source=" + sourceName + ", type=" + sourceType + "). "
                            + "Top-level keys must include `pluginId` — not `code` or `id`."))
                    .warnings(new ArrayList<>())
                    .changes(new HashMap<>())
                    .actionCounts(new HashMap<>())
                    .build();
        }

        String importId = UlidGenerator.generate();
        Long tenantId = requireTenantContextForImport();

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

        try {
            // Remove JSON comment objects (entries with only _-prefixed fields) before validation
            manifest.sanitize();

        // Validate manifest — separate [WARN]-prefixed soft warnings from hard errors.
        // deferReferenceValidation downgrades cross-plugin command/binding → model refs to warnings
        // for batch cyclic cold-reset imports (re-enforced later by verifyImportReferenceIntegrity).
        List<String> allValidationMessages = validateManifest(manifest, deferReferenceValidation);
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

        boolean validateReferences = shouldValidateReferences(manifest);
        if (!validateReferences) {
            result.addWarning("Reference validation disabled by importOptions.validateReferences=false");
        }

        // Analyze dependencies
        ImportPreviewResult.DependencyAnalysis depAnalysis = analyzeDependencies(manifest);
        result.setDependencyAnalysis(depAnalysis);

        if (validateReferences && !depAnalysis.isSatisfied()) {
            for (String missing : depAnalysis.getMissingDependencies()) {
                result.addError("Missing dependency: " + missing);
            }
        }

        // Run extended validation pipeline (semantic + governance)
        if (result.isValid()) {
            try {
                PluginValidationResult validationResult = runValidationPipeline(manifest, validateReferences, deferReferenceValidation);
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
                // DR-20260715-A-005: fail closed. If the whole validation pipeline crashes,
                // the plugin's safety is entirely unknown — block the import (preview error →
                // executeFromManifest aborts on !isValid()) rather than pass it with an
                // advisory warning.
                log.error("Validation pipeline crashed (blocking import): {}", logSafe(e.getMessage()), e);
                result.addError("Validation pipeline error: " + e.getMessage());
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
        } catch (Exception e) {
            importHistoryMapper.markFailed(importId, rootErrorMessage(e), getStackTrace(e));
            throw e;
        }
    }

    private Long requireTenantContextForImport() {
        if (!MetaContext.exists()) {
            throw new PluginException("Tenant context is required for plugin import");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new PluginException("Tenant context is required for plugin import");
        }
        return tenantId;
    }

    private void generateChangePreview(PluginManifestExtended manifest, ImportPreviewResult result, PluginRecord existing) {
        pluginImportChangePreview().generateChangePreview(manifest,result,existing);
    }

    /**
     * Enrich a ResourceChange with user-modified status from ab_plugin_resource.
     * Only meaningful for UPDATE actions (new resources can't be user-modified).
     */
    private ImportPreviewResult.ResourceChange enrichWithUserModified(
            Long tenantId, ResourceType type, String resourceCode,
            ImportPreviewResult.ResourceChange change) {
        return pluginImportChangePreview().enrichWithUserModified(tenantId,type,resourceCode,change);
    }

    /**
     * Summarize user-modified resources and add warnings to the preview result.
     */
    private void summarizeUserModifiedConflicts(ImportPreviewResult result) {
        pluginImportChangePreview().summarizeUserModifiedConflicts(result);
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
                    logSafe(importId), logSafe(pluginId), logSafe(e.getMessage()), e);
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
        if (request.getValidateReferences() == null && opts.getValidateReferences() != null) {
            request.setValidateReferences(opts.getValidateReferences());
        }
        if (request.getCreateResourcePermissions() == null && opts.getCreateResourcePermissions() != null) {
            request.setCreateResourcePermissions(opts.getCreateResourcePermissions());
        }
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

    private boolean shouldValidateReferences(PluginManifestExtended manifest) {
        if (manifest == null || manifest.getImportOptions() == null) {
            return true;
        }
        return !Boolean.FALSE.equals(manifest.getImportOptions().getValidateReferences());
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
                        logSafe(stale.getImportId()), logSafe(pluginId), logSafe(stale.getStatus()), stale.getUpdatedAt());
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

            // DSL V4 Phase B: hard-fail BEFORE any resource is persisted if a declared page
            // violates the v4 import contract (version/kind/layout/blockType/grid/JSON-schema).
            // Advisory page findings (label/i18n/field-ref/...) do not block here; they continue
            // to flow to the post-import quality score below.
            pageSchemaImportGate.enforce(manifest);

            // Create or update plugin record
            String pluginPid = createOrUpdatePlugin(manifest, tenantId);
            result.setPluginPid(pluginPid);

            // Import resources in dependency order
            importResources(context, request, result, pluginPid);

            // Plugin rename upgrade contract: when the request carries an
            // identifier mapping, migrate legacy-identifier physical-table rows
            // into the freshly imported identifiers before the import commits.
            // A migration failure fails the whole import so an upgrade is never
            // half-applied silently.
            applyIdentifierDataMigration(request, result, tenantId);

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
                boolean validateReferences = shouldValidateReferences(manifest);
                var validationCtx = PluginValidationContext.builder()
                        .pluginId(manifest.getPluginId())
                        .namespace(manifest.getNamespace())
                        .manifest(manifest)
                        .validateReferences(validateReferences)
                        .build();
                var validationResult = validationPipeline.validate(validationCtx);
                history.setQualityScore(qualityScorer.computeScore(manifest, validationResult));
            } catch (Exception ex) {
                log.warn("Failed to compute quality score for plugin {}: {}", logSafe(manifest.getPluginId()), logSafe(ex.getMessage()));
            }

            importHistoryMapper.updateById(history);

            log.info("Plugin import successful: {} v{}", logSafe(manifest.getPluginId()), logSafe(manifest.getVersion()));

            applicationEventPublisher.publishEvent(
                new PluginImportCompletedEvent(this, tenantId, manifest.getNamespace())
            );

        } catch (Exception e) {
            log.error("Plugin import failed: {}", logSafe(e.getMessage()), e);

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

    /**
     * Plugin rename upgrade contract: run the identifier-mapping data migration
     * when the request declares one, attaching per-model receipts to the result.
     * Runs inside the import transaction — any failure rolls the whole import back.
     */
    private void applyIdentifierDataMigration(ImportRequest request, ImportExecuteResult result, Long tenantId) {
        String mappingPath = request.getIdentifierMappingPath();
        if (mappingPath == null || mappingPath.isBlank()) {
            return;
        }
        log.info("Running identifier-mapping data migration for plugin {}: {}",
                logSafe(result.getPluginId()), logSafe(mappingPath));
        List<IdentifierMappingDataMigrator.ModelDataMigration> migrations =
                identifierMappingDataMigrator.migrate(java.nio.file.Path.of(mappingPath), tenantId);
        if (result.getDataMigrations() == null) {
            result.setDataMigrations(new ArrayList<>());
        }
        for (IdentifierMappingDataMigrator.ModelDataMigration migration : migrations) {
            result.getDataMigrations().add(ImportExecuteResult.IdentifierDataMigration.builder()
                    .fromModel(migration.getFromModel())
                    .toModel(migration.getToModel())
                    .sourceTable(migration.getSourceTable())
                    .targetTable(migration.getTargetTable())
                    .rowsMigrated(migration.getRowsMigrated())
                    .rowsAlreadyPresent(migration.getRowsAlreadyPresent())
                    .columnsRenamed(migration.getColumnsRenamed())
                    .droppedSourceColumns(migration.getDroppedSourceColumns())
                    .unmappedNewColumns(migration.getUnmappedNewColumns())
                    .build());
        }
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
                    logSafe(importId), logSafe(markEx.getMessage()), markEx);
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

        // Namespace is unique per tenant, but it must not alias a different plugin.
        // Otherwise importing plugin B with plugin A's namespace silently rewrites A's
        // plugin_id and breaks downstream dependency checks.
        PluginRecord namespaceOwner = pluginRecordMapper.findByTenantAndNamespace(manifest.getNamespace());
        if (namespaceOwner != null
                && (existing == null || !Objects.equals(namespaceOwner.getPid(), existing.getPid()))
                && !Objects.equals(namespaceOwner.getPluginId(), manifest.getPluginId())) {
            throw new PluginException("Plugin namespace '" + manifest.getNamespace()
                    + "' is already used by plugin " + namespaceOwner.getPluginId()
                    + "; " + manifest.getPluginId() + " must use a unique namespace");
        }

        // Fallback: same plugin may be found by namespace when plugin_id is unchanged
        // but lookup by plugin_id missed an older record.
        if (existing == null) {
            existing = namespaceOwner;
        }

        // Check for soft-deleted record that may still occupy the unique constraint slot
        if (existing == null) {
            PluginRecord softDeleted = pluginRecordMapper.findByTenantAndNamespaceIncludeDeleted(manifest.getNamespace());
            if (softDeleted != null) {
                if (!Objects.equals(softDeleted.getPluginId(), manifest.getPluginId())) {
                    throw new PluginException("Plugin namespace '" + manifest.getNamespace()
                            + "' is occupied by soft-deleted plugin " + softDeleted.getPluginId()
                            + "; " + manifest.getPluginId() + " must use a unique namespace");
                }
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
            existing.setNamespace(manifest.getNamespace());
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
                case AGENT_DEFINITION -> importAgentDefinitions(manifest, request, result, pluginPid, importId, tenantId);
                case PAGE -> {
                    importPages(manifest, request, result, pluginPid, importId, tenantId);
                    // Also import first-class dashboards (config/dashboards/*.json, Plan #8).
                    // These are keyed to the PAGE import stage so they run after models/queries are in place.
                    importDashboards(manifest, request, result, pluginPid, importId, tenantId);
                }
                case SAVED_VIEW -> importSavedViews(manifest, result, tenantId);
                case NOTIFICATION_TEMPLATE -> importNotificationTemplates(manifest, result, tenantId);
                case I18N -> importI18nResources(manifest, result, tenantId);
                default -> {} // Skip DICT_ITEM as it's handled with DICT
            }
            long elapsedMs = (System.nanoTime() - typeStartNanos) / 1_000_000;
            log.info("Plugin import stage completed: pluginId={}, type={}, elapsedMs={}",
                    logSafe(manifest.getPluginId()), type, elapsedMs);
        }

        // Contributions target the final imported page shape, so validate/replace only after
        // the PAGE stage. The contribution importer never updates the base page row.
        pageSchemaContributionImportService.replaceForPlugin(
                pluginPid, manifest.getVersion(), tenantId, manifest.getPageContributions());

        importDecisionDefinitions(manifest);
        importConditionFragments(manifest);
        importEventPolicies(manifest);
        importAutomations(manifest);
        importFieldMasks(manifest);
        importCapabilities(manifest);

        // Post-processing: Auto-publish DRAFT models and sync PUBLISHED models
        autoPublishAndSyncModels(importedModelCodes, request, manifest.getNamespace(), tenantId);

        // Generated model actions (model.<code>.create/update/... plus command verbs)
        // only exist after the post-processing above, which runs AFTER the ROLE import
        // stage. Role declarations referencing them were silently skipped at that stage
        // ("Permission not found for role binding"), so plugin business roles ended up
        // without model write access on first import. Reconcile now that every generated
        // action resolves; binding is idempotent.
        pluginAccessResourceImporter().reconcileRolePermissionBindings(manifest, tenantId);

        // Semantic resources reference imported model/field codes, so publication
        // must run only after model auto-publish and schema synchronization.
        publishSemanticResources(manifest, result, tenantId);

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
                            log.warn("Failed to auto-publish field {}: {}", logSafe(field.getCode()), logSafe(e.getMessage()));
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
                            log.warn("Failed to auto-publish command {}: {}", logSafe(cmd.getCode()), logSafe(e.getMessage()));
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
                log.warn("Model not found for post-processing: {}", logSafe(modelCode));
                continue;
            }

            if (model.isDraft()) {
                // Publish DRAFT model. VIEW models are also published here to enable runtime permissions/routes.
                log.info("Auto-publishing DRAFT model: {}", logSafe(modelCode));
                String acknowledgementNote = "Acknowledged by trusted plugin import"
                        + (pluginNamespace == null || pluginNamespace.isBlank() ? "" : ": " + pluginNamespace);
                metaModelService.publish(model.getPid(), "Auto-published during plugin import",
                        true, acknowledgementNote);
            } else if (model.isPublished() && !"view".equals(model.getModelType())) {
                // Sync schema for PUBLISHED ENTITY model → adds any new columns
                log.info("Syncing schema for PUBLISHED model: {}", logSafe(modelCode));
                SchemaOperationResult syncResult = schemaManagementService.syncModelToTable(
                        modelCode, SchemaSyncOptions.builder()
                                .syncMode(SchemaSyncOptions.SyncMode.SAFE)
                                .createIndexes(true)
                                .build());
                if (syncResult.isSuccess()) {
                    log.info("Schema sync for {}: {}", logSafe(modelCode), logSafe(syncResult.getMessage()));
                } else {
                    log.warn("Schema sync failed for {}: {}", logSafe(modelCode), logSafe(syncResult.getErrorMessage()));
                }

                // Ensure hierarchical permissions exist (idempotent — skips if already created)
                autoPermissionAssignmentService.registerPermissions(modelCode, pluginNamespace, tenantId);
            }
        }
    }

    @Override
    @Transactional
    public int reconcileDirectoryRolePermissions(String directoryPath) {
        return resourceImporter.reconcileDirectoryRolePermissions(directoryPath, directoryLoader, pluginRecordMapper);
    }

    /**
     * Publish semantic sources after the models they reference are ready.
     *
     * <p>Parser/schema and semantic business-rule failures are isolated to the
     * offending file so a valid sibling can still publish. Persistence and
     * infrastructure failures deliberately escape: swallowing those exceptions
     * would turn a rolled-back/partial import into a false success.
     */
    void publishSemanticResources(PluginManifestExtended manifest,
                                  ImportExecuteResult result,
                                  Long tenantId) {
        List<PluginManifestExtended.SemanticResource> resources =
                manifest.getSemanticResources();
        if (resources == null || resources.isEmpty()) {
            return;
        }
        for (PluginManifestExtended.SemanticResource resource : resources) {
            try {
                String pid = semanticPublishService.publishFromYaml(
                        resource.content(), manifest.getNamespace(), tenantId,
                        MetaContext.getCurrentUserId());
                result.incrementResourceCount("SEMANTIC", ResourceAction.CREATE);
                result.addCreatedResource("SEMANTIC", pid);
            } catch (SemanticYamlInvalidException | SemanticValidationException e) {
                String warning = "Skipped semantic resource " + resource.path()
                        + ": " + e.getMessage();
                result.addWarning(warning);
                result.incrementResourceCount("SEMANTIC", ResourceAction.SKIP);
                log.warn("{} (pluginId={})", logSafe(warning),
                        logSafe(manifest.getPluginId()));
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
            log.info("Auto-linked {} menus to pages by pageKey for plugin {}", linkedCount, logSafe(pluginPid));
        }
    }

    private void importDicts(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importDicts(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importFields(PluginManifestExtended manifest, ImportRequest request,
                              ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importFields(manifest,request,result,pluginPid,importId,tenantId);
    }

    /**
     * Import models and return list of model codes that were created or updated (not skipped).
     */
    private List<String> importModels(PluginManifestExtended manifest, ImportRequest request,
                              ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        return pluginDefinitionResourceImporter().importModels(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importModelFieldBindings(PluginManifestExtended manifest, ImportRequest request,
                                          ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importModelFieldBindings(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importPermissions(PluginManifestExtended manifest, ImportRequest request,
                                   ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginAccessResourceImporter().importPermissions(manifest,request,result,pluginPid,importId,tenantId);
    }


    private void importRoles(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginAccessResourceImporter().importRoles(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importRolePermissions(PluginManifestExtended manifest, ImportRequest request,
                                       ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginAccessResourceImporter().importRolePermissions(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importMenus(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginAccessResourceImporter().importMenus(manifest,request,result,pluginPid,importId,tenantId);
    }

    /**
     * Auto-generate i18n records for menus from their localized name fields.
     * Key format: menu.{CODE} (matching frontend auto-derivation in transformMenuForUI).
     */
    private void generateMenuI18nRecords(List<MenuDefinitionDTO> menus, Long tenantId) {
        communicationResourceImporter().generateMenuI18nRecords(menus,tenantId);
    }

    /**
     * Auto-generate i18n records for permissions from their localized name/description fields.
     * Key format: permission.{CODE} (name) and permission.{CODE}.description (description),
     * matching frontend consumption in routes/enterprise/permission (PermissionTree/PermissionTab).
     */
    private void generatePermissionI18nRecords(List<PermissionDefinitionDTO> permissions, Long tenantId) {
        communicationResourceImporter().generatePermissionI18nRecords(permissions,tenantId);
    }

    private I18nResource buildImportI18nResource(String i18nKey, String lang, String value, String refType) {
        return communicationResourceImporter().buildImportI18nResource(i18nKey,lang,value,refType);
    }

    /**
     * Topological sort: parents before children within the same menu list.
     * Menus whose parentCode references an entry in the same list are placed after that parent.
     * Menus with no parentCode or with an external parentCode keep their original relative order.
     */
    private List<MenuDefinitionDTO> topologicalSortMenus(List<MenuDefinitionDTO> menus) {
        return pluginAccessResourceImporter().topologicalSortMenus(menus);
    }

    /**
     * Auto-generate standard commands for DOCUMENT models that have documentConfig.
     * Generated commands are appended to manifest.commands so they go through the normal import pipeline.
     * Plugin-defined commands take precedence (generated commands with duplicate codes are skipped).
     */
    private void generateDocumentTemplateCommands(PluginManifestExtended manifest) {
        pluginDefinitionResourceImporter().generateDocumentTemplateCommands(manifest);
    }

    private void importCommands(PluginManifestExtended manifest, ImportRequest request,
                                ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importCommands(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importBindingRules(PluginManifestExtended manifest, ImportRequest request,
                                    ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importBindingRules(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importPages(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importPages(manifest,request,result,pluginPid,importId,tenantId);
    }

    /**
     * Import first-class dashboard definitions from {@code config/dashboards/*.json} (Plan #8).
     * Dashboards are emitted as PAGE resource records so the existing resource-tracking
     * infrastructure (PluginResource, rollback) is reused without adding a new ResourceType.
     */
    private void importDashboards(PluginManifestExtended manifest, ImportRequest request,
                                  ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importDashboards(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importNamedQueries(PluginManifestExtended manifest, ImportRequest request,
                                    ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importNamedQueries(manifest,request,result,pluginPid,importId,tenantId);
    }

    private void importAgentDefinitions(PluginManifestExtended manifest, ImportRequest request,
                                        ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        pluginDefinitionResourceImporter().importAgentDefinitions(manifest,request,result,pluginPid,importId,tenantId);
    }

    private PluginRuleSeedImporter ruleSeedImporter() {
        return new PluginRuleSeedImporter(drtDefinitionService, decisionVersionService,
                conditionFragmentService, eventPolicyDefinitionService, eventPolicyVersionService);
    }

    private void importDecisionDefinitions(PluginManifestExtended manifest) {
        ruleSeedImporter().importDecisionDefinitions(manifest);
    }

    private void importConditionFragments(PluginManifestExtended manifest) {
        ruleSeedImporter().importConditionFragments(manifest);
    }

    private void importEventPolicies(PluginManifestExtended manifest) {
        ruleSeedImporter().importEventPolicies(manifest);
    }

    private void importAutomations(PluginManifestExtended manifest) {
        pluginDefinitionResourceImporter().importAutomations(manifest);
    }

    private void importAutomation(AutomationDefinitionDTO dto) {
        pluginDefinitionResourceImporter().importAutomation(dto);
    }

    private AutomationDTO findExistingAutomation(AutomationDefinitionDTO dto) {
        return pluginDefinitionResourceImporter().findExistingAutomation(dto);
    }

    private AutomationCreateRequest toAutomationCreateRequest(AutomationDefinitionDTO dto) {
        return pluginDefinitionResourceImporter().toAutomationCreateRequest(dto);
    }

    /**
     * Import field-mask declarations (config/fieldMasks.json) into ab_field_mask_config. Upserts by
     * (tenant, model, field) via FieldMaskService.saveConfig, so re-import is additive/idempotent.
     */
    private void importFieldMasks(PluginManifestExtended manifest) {
        pluginAccessResourceImporter().importFieldMasks(manifest);
    }

    /**
     * Import capability declarations (config/capabilities.json) into ab_capability via the
     * capability registry. Upserts by (tenant, code), so re-import is additive/idempotent.
     */
    private void importCapabilities(PluginManifestExtended manifest) {
        pluginAccessResourceImporter().importCapabilities(manifest);
    }

    private void importI18nResources(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        communicationResourceImporter().importI18nResources(manifest,result,tenantId);
    }

    private PluginSavedViewImporter savedViewImporter() {
        return new PluginSavedViewImporter(savedViewMapper, pageSchemaMapper, objectMapper);
    }

    private void importSavedViews(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        savedViewImporter().importSavedViews(manifest, result, tenantId);
    }

    private boolean matchesPluginSavedView(SavedViewDefinitionDTO dto, SavedView savedView) {
        return savedViewImporter().matchesPluginSavedView(dto, savedView);
    }

    /**
     * Import notification templates (table {@code ab_notification_template}), upserted by
     * {@code (tenant_id, code)}. Mirrors {@link #importSavedViews}: tenant-scoped data, no
     * {@code PluginResource} record (avoids enum/CHECK-constraint churn). Lets a plugin deliver
     * its own BPMN/automation notifications instead of {@code NotificationService.send()} logging
     * "template not found, skipping".
     */
    private void importNotificationTemplates(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        communicationResourceImporter().importNotificationTemplates(manifest,result,tenantId);
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

    private PluginManifestValidator manifestValidator() {
        return new PluginManifestValidator(resourceImporter, platformVersionChecker, commandActionDeriver);
    }

    @Override
    public List<String> validateManifest(PluginManifestExtended manifest) {
        return manifestValidator().validateManifest(manifest);
    }

    public List<String> validateManifest(PluginManifestExtended manifest, boolean deferReferenceValidation) {
        return manifestValidator().validateManifest(manifest, deferReferenceValidation);
    }

    public List<String> findDanglingCommandModelRefs(List<CommandDefinitionDTO> commands, Set<String> providedModels) {
        return manifestValidator().findDanglingCommandModelRefs(commands, providedModels);
    }

    public List<String> findDanglingMenuParentRefs(List<MenuDefinitionDTO> menus, Set<String> providedMenus) {
        return manifestValidator().findDanglingMenuParentRefs(menus, providedMenus);
    }

    public List<String> findDanglingPermissionRefs(List<MenuDefinitionDTO> menus,
            List<RoleDefinitionDTO> roles, Set<String> providedPermissions) {
        return manifestValidator().findDanglingPermissionRefs(menus, roles, providedPermissions);
    }

    /**
     * Reconstruct every currently-imported resource of the given type for a tenant from its
     * {@code ab_plugin_resource.import_snapshot}, deduplicated by resource code (last import wins).
     * This is how the closing sweep recovers manifest-only fields (e.g. menu {@code parentCode})
     * that the persisted entity itself does not retain (only the resolved {@code parentId}, which
     * a not-yet-resolved cross-plugin reference would otherwise leave permanently unrecoverable).
     */
    private <T> List<T> loadImportedResourceSnapshots(Long tenantId, ResourceType resourceType, Class<T> dtoClass) {
        return pluginImportAssessment().loadImportedResourceSnapshots(tenantId,resourceType,dtoClass);
    }

    /**
     * Closing reference-integrity sweep over the whole tenant. Run after a batch cold-reset that
     * imported a cyclic plugin set with {@code deferReferenceValidation=true}: by now every plugin
     * has been imported, so a command whose {@code modelCode}, a menu whose {@code parentCode} /
     * {@code permissionCode}, or a role whose permission reference still resolves to nothing is a
     * genuinely dangling reference (typo / removed resource) rather than a not-yet-imported one.
     *
     * <p>The per-plugin imports already committed (multi-tx, shell-orchestrated), so this can only
     * <em>report</em> dangling references — the caller (reset/init orchestration) decides to fail.
     *
     * @return human-readable messages for every command→model, menu→parent-menu, menu→permission
     *         and role→permission reference that resolves to nothing (empty when the tenant's
     *         deferred cross-plugin references are all intact)
     */
    @Override
    public List<String> verifyImportReferenceIntegrity() {
        return pluginImportAssessment().verifyImportReferenceIntegrity();
    }

    /**
     * Validate required fields beyond just code/key for all DSL resource types.
     * This catches cases where a resource has a code but is missing other mandatory fields
     * that would cause DB constraint violations or runtime failures.
     */
    private String normalizeCode(String value) {
        return pluginImportAssessment().normalizeCode(value);
    }

    private boolean isBlank(String value) {
        return pluginImportAssessment().isBlank(value);
    }

    /**
     * Build validation context and run the pre-flight pipeline.
     */
    private PluginValidationResult runValidationPipeline(PluginManifestExtended manifest, boolean validateReferences) {
        return pluginImportAssessment().runValidationPipeline(manifest,validateReferences);
    }

    private PluginValidationResult runValidationPipeline(PluginManifestExtended manifest, boolean validateReferences,
                                                         boolean deferReferenceValidation) {
        return pluginImportAssessment().runValidationPipeline(manifest,validateReferences,deferReferenceValidation);
    }

    @Override
    public List<ImportPreviewResult.ResourceConflict> checkConflicts(PluginManifestExtended manifest) {
        return pluginImportAssessment().checkConflicts(manifest);
    }

    private <T> void collectConflicts(
            List<ImportPreviewResult.ResourceConflict> conflicts,
            String importingPluginId,
            Long tenantId,
            ResourceType resourceType,
            List<T> resources,
            Function<T, String> codeExtractor,
            String label) {
        pluginImportAssessment().collectConflicts(conflicts,importingPluginId,tenantId,resourceType,resources,codeExtractor,label);
    }



    @Override
    public ImportPreviewResult.DependencyAnalysis analyzeDependencies(PluginManifestExtended manifest) {
        return pluginImportAssessment().analyzeDependencies(manifest);
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
        if (ResourceType.AGENT_DEFINITION.code().equals(resource.getResourceType())) {
            ensureAgentDefinitionResourceTypeAllowed();
        }

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

    private void ensureAgentDefinitionResourceTypeAllowed() {
        if (!pluginResourceTypeConstraintChecked.compareAndSet(false, true)) {
            return;
        }
        jdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1
                        FROM pg_constraint
                        WHERE conname = 'chk_resource_type'
                          AND conrelid = 'ab_plugin_resource'::regclass
                    ) THEN
                        ALTER TABLE ab_plugin_resource DROP CONSTRAINT chk_resource_type;
                    END IF;

                    ALTER TABLE ab_plugin_resource
                        ADD CONSTRAINT chk_resource_type CHECK (resource_type IN (
                            'model', 'field', 'model_field_binding', 'command', 'binding_rule',
                            'permission', 'role', 'role_permission', 'menu', 'process', 'page',
                            'dict', 'dict_item', 'named_query', 'agent_definition', 'i18n'
                        ));
                EXCEPTION
                    WHEN duplicate_object THEN NULL;
                END $$;
                """);
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
                log.warn("Failed to capture import snapshot for {}: {}", logSafe(resource.getResourceCode()), logSafe(e.getMessage()));
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

    private PluginCommunicationResourceImporter communicationResourceImporter() {
        return new PluginCommunicationResourceImporter(i18nCompiler, i18nResourceService, i18nService, notificationTemplateMapper);
    }

    private PluginAccessResourceImporter pluginAccessResourceImporter() {
        return new PluginAccessResourceImporter(resourceImporter, fieldMaskService, capabilityRegistryService, userPermissionService, this::generateMenuI18nRecords, this::generatePermissionI18nRecords, this::saveOrUpdatePluginResource, this::captureImportSnapshot);
    }

    private PluginDefinitionResourceImporter pluginDefinitionResourceImporter() {
        return new PluginDefinitionResourceImporter(resourceImporter, documentCommandGenerator, automationService, this::saveOrUpdatePluginResource, this::captureImportSnapshot);
    }

    private PluginImportAssessment pluginImportAssessment() {
        return new PluginImportAssessment(pluginRecordMapper, pluginResourceMapper, resourceImporter, validationPipeline, commandDefinitionMapper, objectMapper, this::findDanglingCommandModelRefs, this::findDanglingMenuParentRefs, this::findDanglingPermissionRefs);
    }

    private PluginImportChangePreview pluginImportChangePreview() {
        return new PluginImportChangePreview(pluginResourceMapper, resourceImporter, savedViewMapper, notificationTemplateMapper, this::matchesPluginSavedView);
    }
}
