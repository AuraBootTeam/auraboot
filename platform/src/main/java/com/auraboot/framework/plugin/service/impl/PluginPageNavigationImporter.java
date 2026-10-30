package com.auraboot.framework.plugin.service.impl;

import static com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.dto.DictCreateRequest;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.DictUpdateRequest;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.dashboard.dto.DashboardCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.dto.DashboardUpdateRequest;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.common.constant.StatusConstants;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/** Imports pages, navigation, dashboards and dictionaries. */
final class PluginPageNavigationImporter {
    // Preserve virtual calls on the original facade receiver.
    private final PluginResourceImporterImpl facadeReceiver;
    private final DictService dictService;
    private final MenuService menuService;
    private final PageSchemaService pageSchemaService;
    private final DashboardService dashboardService;
    private final com.auraboot.framework.environment.service.EnvironmentService environmentService;
    private final MenuMapper menuMapper;
    private final ObjectMapper objectMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final DictMapper dictMapper;
    private final Map<String, Long> menuCodeToIdMap;
    private final Logger log;
    private final PluginImportSupport support;

    PluginPageNavigationImporter(
            PluginResourceImporterImpl facadeReceiver,
            DictService dictService,
            MenuService menuService,
            PageSchemaService pageSchemaService,
            DashboardService dashboardService,
            com.auraboot.framework.environment.service.EnvironmentService environmentService,
            MenuMapper menuMapper,
            ObjectMapper objectMapper,
            PageSchemaMapper pageSchemaMapper,
            DictMapper dictMapper,
            Map<String, Long> menuCodeToIdMap,
            Logger log,
            PluginImportSupport support) {
        this.facadeReceiver = facadeReceiver;
        this.dictService = dictService;
        this.menuService = menuService;
        this.pageSchemaService = pageSchemaService;
        this.dashboardService = dashboardService;
        this.environmentService = environmentService;
        this.menuMapper = menuMapper;
        this.objectMapper = objectMapper;
        this.pageSchemaMapper = pageSchemaMapper;
        this.dictMapper = dictMapper;
        this.menuCodeToIdMap = menuCodeToIdMap;
        this.log = log;
        this.support = support;
    }

    public boolean checkMenuExists(Long tenantId, String code) {
        return menuMapper.existsByCode(tenantId, code);
    }

    /**
     * Clear the menu code-to-ID map. Should be called before each plugin import session.
     */
    public void clearMenuCodeMap() {
        menuCodeToIdMap.clear();
    }

    public boolean checkPageExists(Long tenantId, String pageKey) {
        com.auraboot.framework.meta.dto.PageSchemaDTO page = pageSchemaService.findAnyByPageKey(pageKey);
        return page != null;
    }

    public boolean checkDictExists(Long tenantId, String code) {
        DictDTO dict = dictService.findByCode(code);
        return dict != null;
    }

    /**
     * Detect a page row created by {@code MetaModelServiceImpl.autoCreateDefaultPages}. Such rows
     * carry no user content and are tagged with {@code extension.auto_created=true}. Plugin imports
     * must overwrite them unconditionally so that the canonical DSL from {@code config/pages/*.json}
     * lands in place of the stub.
     *
     * <p>Returns {@code false} on any lookup error or if the flag is absent — the caller will then
     * fall back to the normal OVERWRITE_SAFE skip behavior.
     */
    private boolean isAutoCreatedStubPage(String pageKey) {
        try {
            com.auraboot.framework.meta.dto.PageSchemaDTO existing = pageSchemaService.findAnyByPageKey(pageKey);
            if (existing == null || existing.getExtension() == null) {
                return false;
            }
            Object flag = existing.getExtension().get("auto_created");
            // Supported customer saves increment rowVersion. A historical stub flag alone
            // must never exempt an edited page from customization protection.
            return java.util.Objects.equals(existing.getRowVersion(), 1)
                    && (Boolean.TRUE.equals(flag) || "true".equals(String.valueOf(flag)));
        } catch (Exception e) {
            // Defensive: on lookup failure, do not bypass the user-modified guard.
            log.debug("isAutoCreatedStubPage lookup failed for pageKey={}: {}",
                    PluginImportSupport.logSafe(pageKey), PluginImportSupport.logSafe(e.getMessage()));
            return false;
        }
    }

    public PluginResource importMenu(MenuDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        // Validate: dynamic menus must have pageKey
        String path = dto.getPath();
        if (path != null && path.startsWith("/dynamic/")
                && (dto.getPageKey() == null || dto.getPageKey().isBlank())) {
            throw new PluginException(
                    "Menu '" + dto.getCode() + "' has dynamic path '" + path
                    + "' but missing pageKey. Add pageKey to menus.json.");
        }

        boolean exists = facadeReceiver.checkMenuExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Menu already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.MENU, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.MENU, PluginImportSupport.logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        // Get parent ID if specified
        Long parentId = null;
        if (dto.getParentCode() != null) {
            // First check in-memory map (populated during same import session)
            parentId = menuCodeToIdMap.get(dto.getParentCode());
            if (parentId == null) {
                // Strict lookup by code only; cross-plugin parents resolve from tenants
                // whose provider plugin was imported earlier.
                parentId = menuMapper.findIdByCode(tenantId, dto.getParentCode());
                if (parentId == null) {
                    // Fail-closed: a menu silently re-parented to root hides the broken
                    // reference until navigation looks wrong rounds later.
                    throw new PluginException("Menu '" + dto.getCode() + "' declares parentCode '"
                            + dto.getParentCode() + "' but no menu with that code exists in this tenant; "
                            + "import the plugin that owns the parent menu first (declare it as a "
                            + "dependency) or fix the declaration");
                }
            }
        }

        // Build extension with modelCode and pageType
        Map<String, Object> extension = dto.getExtension() != null
                ? new java.util.HashMap<>(dto.getExtension())
                : new java.util.HashMap<>();
        if (dto.getModelCode() != null && !dto.getModelCode().isBlank()) {
            extension.put("modelCode", dto.getModelCode());
        }
        if (dto.getKind() != null && !dto.getKind().isBlank()) {
            extension.put("pageType", dto.getKind());
        }
        String extensionJson = extension.isEmpty() ? null : support.toJson(extension);
        String pageKey = dto.getPageKey() != null && !dto.getPageKey().isBlank() ? dto.getPageKey() : null;

        if (exists) {
            // Update existing menu — also update name to effective name

            menuMapper.updateForPluginImport(
                    dto.getCode(), dto.getEffectiveName(), dto.getPath(), dto.getComponent(), dto.getIcon(), dto.getType(),
                    parentId, dto.getPermissionCode(), dto.getVisible() != null ? dto.getVisible() : true, dto.getOrderNo(),
                    dto.getI18nKey(), dto.getRedirect(), pageKey, dto.getPagePid(), extensionJson,
                    pluginPid, tenantId, dto.getCode());

            String existingPid = menuMapper.findPidByCode(tenantId, dto.getCode());

            // Store in map for child resolution
            Long existingId = menuMapper.findIdByPid(tenantId, existingPid);
            menuCodeToIdMap.put(dto.getCode(), existingId);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    existingPid, null, dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Create new menu via service
            Menu menu = new Menu();
            menu.setPid(UlidGenerator.generate());
            menu.setTenantId(tenantId);
            menu.setCode(dto.getCode());
            menu.setName(dto.getEffectiveName());
            menu.setPath(dto.getPath());
            menu.setComponent(dto.getComponent());
            menu.setIcon(dto.getIcon());
            // type pass-through: 0=Directory, 1=Menu, 2=Button (same as DB convention)
            menu.setType(dto.getType() != null ? dto.getType() : 1);
            menu.setParentId(parentId);
            menu.setPermissionCode(dto.getPermissionCode());
            menu.setVisible(dto.getVisible() != null ? dto.getVisible() : true);
            menu.setOrderNo(dto.getOrderNo() != null ? dto.getOrderNo() : 0);
            menu.setI18nKey(dto.getI18nKey());
            menu.setRedirect(dto.getRedirect());
            menu.setPageKey(pageKey);
            menu.setStatus(MenuStatus.ACTIVE);
            menu.setDeletedFlag(false);
            menu.setCreatedAt(Instant.now());
            menu.setUpdatedAt(Instant.now());

            Menu created = menuService.createMenu(menu);

            // Store code → id mapping for child menu parent resolution
            menuCodeToIdMap.put(dto.getCode(), created.getId());

            // Update plugin_pid, page_key and extension
            menuMapper.updatePluginFields(pluginPid, pageKey, extensionJson, created.getId());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    public PluginResource importPage(PageSchemaDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                      Boolean autoPublish) {
        boolean exists = facadeReceiver.checkPageExists(tenantId, dto.getPageKey());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Page already exists: " + dto.getPageKey());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    null, null, dto.getPageKey(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists && support.shouldSkipExistingForOverwriteSafe(tenantId, conflictStrategy, ResourceType.PAGE, dto.getPageKey())) {
            // Stub pages emitted by MetaModelServiceImpl.autoCreateDefaultPages are tagged with
            // extension.auto_created=true. Such rows carry no user content (zero columns / buttons /
            // fields) and exist solely so that /api/pages/key/{pageKey} resolves between model
            // publish and plugin import. They must never block plugin DSL overwrite, even if the
            // resource was somehow flagged user-modified earlier. Bypass the skip in that case.
            if (!isAutoCreatedStubPage(dto.getPageKey())) {
                log.info("Skipping user-modified resource: {} {}", ResourceType.PAGE, PluginImportSupport.logSafe(dto.getPageKey()));
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                        null, null, dto.getPageKey(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
            }
            log.info("Forcing overwrite of auto-created stub page: {}", PluginImportSupport.logSafe(dto.getPageKey()));
        }

        // V2 flat format: kind/profile/title/layout/blocks directly on DTO
        String kind = dto.getKind() != null ? dto.getKind() : "list";
        String profile = dto.getProfile() != null ? dto.getProfile() : "admin";
        String titleJson = dto.getTitle() != null ? support.toJson(dto.getTitle()) : null;
        String layoutJson = dto.getLayout() != null ? support.toJson(dto.getLayout()) : null;
        String blocksJson = dto.getBlocks() != null ? support.toJson(dto.getBlocks()) : "[]";
        String extensionJson = support.toJson(mergePageExtension(dto));
        String titleDisplay = dto.getEffectiveName();
        boolean isTemplate = dto.getIsTemplate() != null && dto.getIsTemplate();
        int sortWeight = dto.getSortWeight() != null ? dto.getSortWeight() : 0;
        int schemaVersion = dto.getSchemaVersion() != null
                ? dto.getSchemaVersion()
                : com.auraboot.framework.meta.constant.DslRegistry.PAGE_SCHEMA_CURRENT_VERSION;

        if (exists) {
            // Update existing page via direct SQL to bypass service-layer name uniqueness validation
            com.auraboot.framework.meta.dto.PageSchemaDTO existingPage = pageSchemaService.findAnyByPageKey(dto.getPageKey());

            pageSchemaMapper.updateForPluginImport(
                titleDisplay, titleJson, dto.getDescription(), kind, profile,
                dto.getModelCode(), layoutJson, blocksJson,
                schemaVersion, isTemplate, dto.getTemplateCategory(), sortWeight,
                extensionJson, pluginPid, existingPage.getPid(), tenantId);

            if (autoPublish != null && autoPublish && !StatusConstants.PUBLISHED.equals(existingPage.getStatus())) {
                pageSchemaMapper.publishByPid(existingPage.getPid());
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    existingPage.getPid(), null, dto.getPageKey(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Create new page via direct SQL to bypass service-layer name uniqueness validation
            String pid = UlidGenerator.generate();
            boolean publish = autoPublish != null && autoPublish;

            // env-layering PoC #16: resolve env_id from MetaContext (HTTP request) or fall back
            // to tenant default (CLI / startup imports without env hint).
            Long envId = com.auraboot.framework.application.tenant.MetaContext.getCurrentEnvironmentId();
            if (envId == null) {
                envId = environmentService.findOrCreateDefaultId(tenantId);
            }

            pageSchemaMapper.insertForPluginImport(
                pid, tenantId, envId, publish ? "published" : "draft",
                dto.getPageKey(), dto.getModelCode(),
                titleDisplay, titleJson, dto.getDescription(), kind, profile,
                layoutJson, blocksJson, schemaVersion,
                isTemplate, dto.getTemplateCategory(),
                publish ? java.time.Instant.now() : null,
                sortWeight, extensionJson, pluginPid);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    pid, null, dto.getPageKey(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    private Map<String, Object> mergePageExtension(PageSchemaDTO dto) {
        Map<String, Object> extension = new LinkedHashMap<>();
        if (dto.getExtension() != null) {
            extension.putAll(dto.getExtension());
        }
        if (dto.getDataSources() != null && !dto.getDataSources().isEmpty()) {
            extension.put("dataSources", dto.getDataSources());
        }
        if (dto.getLinkageRules() != null) {
            extension.put("linkageRules", dto.getLinkageRules());
        }
        if (dto.getMobileUx() != null && !dto.getMobileUx().isEmpty()) {
            extension.put("mobileUx", dto.getMobileUx());
        }
        return extension;
    }

    /**
     * Import a dashboard from the first-class {@code config/dashboards/*.json} contract (Plan #8).
     *
     * <p>Conflict strategy is honoured at the dashboard level (by code):
     * <ul>
     *   <li>ERROR       → throw if a dashboard with the same code already exists</li>
     *   <li>SKIP        → return a SKIP record when dashboard already exists</li>
     *   <li>OVERWRITE / OVERWRITE_SAFE → update widgets + layout in place</li>
     * </ul>
     */
    public PluginResource importDashboard(DashboardDefinitionDTO dto, String pluginPid, String importId,
                                          Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        log.info("Importing dashboard '{}' from config/dashboards/", PluginImportSupport.logSafe(dto.getCode()));

        if (!dto.isValid()) {
            throw new PluginException(
                    "Invalid dashboard definition '%s': code, title and widgets are required".formatted(dto.getCode()));
        }

        // Build widgets JsonNode from the raw List<Object>
        com.fasterxml.jackson.databind.JsonNode widgetsNode = objectMapper.valueToTree(dto.getWidgets());
        com.fasterxml.jackson.databind.JsonNode layoutConfigNode = buildDashboardLayoutConfig(dto.getLayoutConfig());
        com.fasterxml.jackson.databind.JsonNode extensionNode = objectMapper.valueToTree(
                dto.getExtension() != null ? dto.getExtension() : Map.of());

        DashboardDTO existing = dashboardService.findByCode(dto.getCode());

        if (existing != null && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Dashboard already exists: " + dto.getCode());
        }

        if (existing != null && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            log.info("Dashboard '{}' already exists — skipping (SKIP strategy)", PluginImportSupport.logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DASHBOARD,
                    existing.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.SKIP, null, null);
        }

        if (existing != null && support.shouldSkipExistingForOverwriteSafe(
                tenantId, conflictStrategy, ResourceType.DASHBOARD, dto.getCode())) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DASHBOARD,
                    existing.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.SKIP, null, null);
        }

        if (existing != null) {
            // Explicit overwrite or an unmodified, locked resource.
            DashboardUpdateRequest updateReq = new DashboardUpdateRequest();
            updateReq.setTitle(dto.getTitle());
            updateReq.setDescription(dto.getDescription());
            updateReq.setScope(dto.getEffectiveScope());
            updateReq.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()));
            updateReq.setLayoutConfig(layoutConfigNode);
            updateReq.setWidgets(widgetsNode);
            updateReq.setExtension(extensionNode);
            dashboardService.update(existing.getPid(), updateReq);
            // Symmetry with the create path: update() only touches the working
            // draft, so a re-imported dashboard would otherwise keep serving
            // the stale published layout while the import reports success.
            if (!"draft".equals(dto.getEffectiveStatus())) {
                dashboardService.publish(existing.getPid());
            }
            log.info("Dashboard updated from config/dashboards/: code={}, pid={}",
                    PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(existing.getPid()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DASHBOARD,
                    existing.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.UPDATE, null, null);
        } else {
            DashboardCreateRequest createReq = new DashboardCreateRequest();
            createReq.setCode(dto.getCode());
            createReq.setTitle(dto.getTitle());
            createReq.setDescription(dto.getDescription());
            createReq.setScope(dto.getEffectiveScope());
            createReq.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()));
            createReq.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
            createReq.setLayoutConfig(layoutConfigNode);
            createReq.setWidgets(widgetsNode);
            createReq.setExtension(extensionNode);
            DashboardDTO created = dashboardService.create(createReq);
            // Plugin dashboards are published immediately unless status=draft
            if (!"draft".equals(dto.getEffectiveStatus())) {
                dashboardService.publish(created.getPid());
            }
            log.info("Dashboard created from config/dashboards/: code={}, pid={}",
                    PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(created.getPid()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DASHBOARD,
                    created.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.CREATE, null, null);
        }
    }

    /**
     * Build a dashboard layoutConfig JsonNode from a raw map.
     * Applies defaults when the map is null or missing keys.
     */
    private com.fasterxml.jackson.databind.JsonNode buildDashboardLayoutConfig(Map<String, Object> rawLayout) {
        com.fasterxml.jackson.databind.node.ObjectNode cfg = objectMapper.createObjectNode();
        int columns   = rawLayout != null && rawLayout.get("columns")   instanceof Number n ? n.intValue() : 12;
        int rowHeight = rawLayout != null && rawLayout.get("rowHeight") instanceof Number n ? n.intValue() : 100;
        int gap       = rawLayout != null && rawLayout.get("gap")       instanceof Number n ? n.intValue() : 16;
        String compact = rawLayout != null && rawLayout.get("compactType") instanceof String s ? s : "vertical";
        cfg.put("columns",     columns);
        cfg.put("rowHeight",   rowHeight);
        cfg.put("gap",         gap);
        cfg.put("compactType", compact);
        return cfg;
    }

    public PluginResource importDict(DictDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        boolean exists = facadeReceiver.checkDictExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Dictionary already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.DICT, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.DICT, PluginImportSupport.logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // A previous uninstall leaves the dict soft-deleted while
            // findCurrentByCode (no deleted_flag filter) still reports it as
            // existing — the update-in-place below would silently refresh a dead
            // row. Revive it first so the reimport lands on a live dict.
            int revived = dictMapper.reviveRetiredByCode(dto.getCode());
            if (revived > 0) {
                log.info("Revived soft-deleted dict {} for plugin reimport", PluginImportSupport.logSafe(dto.getCode()));
            }

            // Update existing dict
            DictDTO existingDict = dictService.findByCode(dto.getCode());

            DictUpdateRequest updateRequest = new DictUpdateRequest();
            updateRequest.setName(dto.getEffectiveName());
            updateRequest.setDescription(dto.getDescription());
            updateRequest.setDictType(dto.getDictType());

            DictDTO updated = dictService.update(existingDict.getPid(), updateRequest);

            // Update dict items using new replaceItems service method
            if (dto.getItems() != null && !dto.getItems().isEmpty()) {
                List<DictCreateRequest.DictItemCreateRequest> itemRequests = dto.getItems().stream()
                    .map(this::toDictItemRequest)
                    .toList();
                dictService.replacePluginItems(updated.getPid(), itemRequests);
            }

            // Update plugin_pid
            dictMapper.updatePluginPidByPid(pluginPid, updated.getPid());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    updated.getPid(), updated.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Check for soft-deleted record and resurrect if found
            String resurrectPid = support.resurrectSoftDeleted("ab_dict", "code", dto.getCode(), tenantId, pluginPid, null);
            if (resurrectPid != null) {
                log.info("Resurrected soft-deleted dict: code={}, pid={}",
                        PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(resurrectPid));
                // Now update the resurrected dict with current data
                DictDTO resurrected = dictService.findByCode(dto.getCode());
                if (resurrected != null && dto.getItems() != null && !dto.getItems().isEmpty()) {
                    List<DictCreateRequest.DictItemCreateRequest> itemRequests = dto.getItems().stream()
                        .map(this::toDictItemRequest)
                        .toList();
                    dictService.replaceItems(resurrected.getPid(), itemRequests);
                }
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                        resurrectPid, null, dto.getCode(), dto.getEffectiveName(),
                        ResourceAction.CREATE, null, null);
            }

            // Create new dict via service with pluginPid
            DictCreateRequest request = new DictCreateRequest();
            request.setCode(dto.getCode());
            request.setName(dto.getEffectiveName());
            request.setDescription(dto.getDescription());
            request.setDictType(dto.getDictType() != null ? dto.getDictType() : "simple");
            request.setSourceType("static");
            request.setEnabled(true);
            request.setPluginPid(pluginPid);  // Set plugin_pid via request

            // Convert items
            if (dto.getItems() != null) {
                request.setItems(dto.getItems().stream()
                    .map(this::toDictItemRequest)
                    .toList());
            }

            DictDTO created = dictService.create(request);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    /**
     * Maps one manifest dict item onto its persistence request. The manifest's
     * localized "label:*" entries ride along as a "labels" map inside the item
     * extension so the render-time dict reads can serve the label for the
     * request locale (see DictLabelLocalizationHelper); the stored label keeps
     * the effective single-locale value for dictionaries without translations.
     */
    private DictCreateRequest.DictItemCreateRequest toDictItemRequest(DictDefinitionDTO.DictItemDTO item) {
        DictCreateRequest.DictItemCreateRequest itemReq = new DictCreateRequest.DictItemCreateRequest();
        itemReq.setValue(item.getValue());
        itemReq.setLabel(item.getEffectiveLabel());
        itemReq.setSortOrder(item.getSortNo());
        itemReq.setParentValue(item.getParentValue());
        itemReq.setDisabled(StatusConstants.DISABLED.equals(item.getStatus()));
        Map<String, String> localizedLabels = item.getAllLocalizedLabels();
        if (item.getExtra() != null || (localizedLabels != null && !localizedLabels.isEmpty())) {
            com.fasterxml.jackson.databind.node.ObjectNode extension = item.getExtra() != null
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.convertValue(
                            item.getExtra(), JsonNode.class)
                    : objectMapper.createObjectNode();
            if (localizedLabels != null && !localizedLabels.isEmpty()) {
                extension.set("labels", objectMapper.valueToTree(localizedLabels));
            }
            itemReq.setExtension(extension);
        }
        return itemReq;
    }

}
