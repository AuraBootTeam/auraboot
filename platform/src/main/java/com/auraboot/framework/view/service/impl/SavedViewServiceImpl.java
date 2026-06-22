package com.auraboot.framework.view.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.organization.dto.TeamResponse;
import com.auraboot.framework.organization.service.TeamService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.view.dto.AutoSaveViewRequest;
import com.auraboot.framework.view.dto.SavedViewAuditEventDTO;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckRequest;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckResponse;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.SavedViewUpdateRequest;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.auraboot.framework.view.service.SavedViewService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SavedView Service Implementation
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SavedViewServiceImpl implements SavedViewService {

    private static final String AUDIT_EVENT_TYPE = "SAVED_VIEW";
    private static final String AUDIT_ENTITY_TYPE = "saved_view";
    private static final String CAPABILITY_AVAILABLE = "available";
    private static final String CAPABILITY_BLOCKED = "blocked";
    private static final String REASON_MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";
    private static final int PERSONAL_VIEW_LIMIT = 10;
    private static final int TEAM_VIEW_LIMIT = 20;
    private static final int GLOBAL_VIEW_LIMIT = 20;

    private final SavedViewMapper savedViewMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final UserPermissionService userPermissionService;
    private final CurrentUserTeamResolver currentUserTeamResolver;
    private final TeamMapper teamMapper;
    private final AuditTrailService auditTrailService;

    @Override
    public SavedViewDTO create(SavedViewCreateRequest request) {
        log.info("Creating saved view: name={}, modelCode={}", request.getName(), request.getModelCode());

        validateCreateRequest(request);
        if (StringUtils.hasText(request.getPageKey())) {
            validatePageKeyExists(request.getPageKey());
        }
        if ("team".equals(request.getScope())) {
            validateCurrentUserInTeam(request.getTeamId());
        }
        String viewType = StringUtils.hasText(request.getViewType()) ? request.getViewType() : "table";
        ViewConfig viewConfig = request.getViewConfig() != null ? request.getViewConfig() : new ViewConfig();
        validateViewTypeConfig(viewType, viewConfig);

        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Check name uniqueness for personal views
        if (!isNameUnique(request.getModelCode(), request.getPageKey(),
                request.getName(), null)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "View name already exists: " + request.getName());
        }

        String scope = StringUtils.hasText(request.getScope()) ? request.getScope() : "personal";
        validateViewCountLimit(request, scope, currentUserPid);

        SavedView savedView = new SavedView();
        savedView.setPid(UniqueIdGenerator.generate());
        savedView.setTenantId(tenantId);
        savedView.setName(request.getName());
        savedView.setDescription(request.getDescription());
        savedView.setModelCode(request.getModelCode());
        savedView.setPageKey(request.getPageKey());
        savedView.setScope(scope);
        savedView.setViewType(viewType);
        savedView.setOwnerId(currentUserPid);
        savedView.setTeamId(request.getTeamId());
        savedView.setViewConfig(viewConfig);
        savedView.setAllowFullModel(request.getAllowFullModel() != null ? request.getAllowFullModel() : false);
        savedView.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : false);
        savedView.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        savedView.setDeletedFlag(false);
        savedView.setCreatedAt(Instant.now());
        savedView.setUpdatedAt(Instant.now());
        savedView.setCreatedBy(currentUserPid);
        savedView.setUpdatedBy(currentUserPid);

        // If setting as default, clear other defaults first based on scope
        if (Boolean.TRUE.equals(savedView.getIsDefault())) {
            clearDefaultFlagByScope(scope, request.getModelCode(), request.getPageKey(),
                    currentUserPid, request.getTeamId());
        }

        savedViewMapper.insertSavedView(savedView);
        recordSharedAudit(savedView, "CREATE", Set.of("name", "scope", "viewConfig"));

        log.info("Saved view created: pid={}", savedView.getPid());
        return toDTO(savedView);
    }

    @Override
    public SavedViewDTO findByPid(String pid) {
        SavedView savedView = savedViewMapper.findByPid(pid);
        if (savedView == null) {
            return null;
        }

        // Check access permission
        validateReadAccess(savedView);

        return toDTO(savedView);
    }

    @Override
    public SavedViewDTO update(String pid, SavedViewUpdateRequest request) {
        log.info("Updating saved view: pid={}", pid);

        SavedView savedView = savedViewMapper.findByPid(pid);
        if (savedView == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Saved view not found: " + pid);
        }

        validateWriteAccess(savedView);

        String currentUserPid = MetaContext.getCurrentUserPid();
        Set<String> changedFields = new LinkedHashSet<>();

        // Check name uniqueness if name is being changed
        if (StringUtils.hasText(request.getName()) && !request.getName().equals(savedView.getName())) {
            if (!isNameUnique(savedView.getModelCode(), savedView.getPageKey(),
                    request.getName(), pid)) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "View name already exists: " + request.getName());
            }
            savedView.setName(request.getName());
            changedFields.add("name");
        }

        if (request.getDescription() != null) {
            savedView.setDescription(request.getDescription());
            changedFields.add("description");
        }
        if (request.getScope() != null) {
            savedView.setScope(request.getScope());
            changedFields.add("scope");
        }
        if (request.getTeamId() != null) {
            savedView.setTeamId(request.getTeamId());
            changedFields.add("teamId");
        }
        if (request.getViewConfig() != null) {
            savedView.setViewConfig(request.getViewConfig());
            changedFields.add("viewConfig");
        }
        if (request.getAllowFullModel() != null) {
            savedView.setAllowFullModel(request.getAllowFullModel());
            changedFields.add("allowFullModel");
        }
        if (request.getIsDefault() != null) {
            // If setting as default, clear other defaults first based on scope
            if (Boolean.TRUE.equals(request.getIsDefault())) {
                clearDefaultFlagByScope(savedView.getScope(), savedView.getModelCode(),
                        savedView.getPageKey(), currentUserPid, savedView.getTeamId());
            }
            savedView.setIsDefault(request.getIsDefault());
            changedFields.add("isDefault");
        }
        if (request.getSortOrder() != null) {
            savedView.setSortOrder(request.getSortOrder());
            changedFields.add("sortOrder");
        }

        if ("team".equals(savedView.getScope()) && !StringUtils.hasText(savedView.getTeamId())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Team ID is required for TEAM scope views");
        }
        if (savedView.isTeam()) {
            validateCurrentUserInTeam(savedView.getTeamId());
        }
        if (request.getViewConfig() != null) {
            validateViewTypeConfig(savedView.getViewType(), savedView.getViewConfig());
        }

        savedView.setUpdatedAt(Instant.now());
        savedView.setUpdatedBy(currentUserPid);

        savedViewMapper.updateSavedView(savedView);
        recordSharedAudit(savedView, "UPDATE", changedFields);

        log.info("Saved view updated: pid={}", pid);
        return toDTO(savedView);
    }

    @Override
    public void delete(String pid) {
        log.info("Deleting saved view: pid={}", pid);

        SavedView savedView = savedViewMapper.findByPid(pid);
        if (savedView == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Saved view not found: " + pid);
        }

        validateWriteAccess(savedView);

        savedViewMapper.deleteById(savedView.getId());
        recordSharedAudit(savedView, "DELETE", Set.of("deletedFlag"));

        log.info("Saved view deleted: pid={}", pid);
    }

    @Override
    public List<SavedViewDTO> getAccessibleViews(String modelCode, String pageKey) {
        String currentUserPid = MetaContext.getCurrentUserPid();
        List<String> teamIds = getCurrentUserTeamIds();

        List<SavedView> views = savedViewMapper.findAccessibleViews(
                modelCode, pageKey, currentUserPid, teamIds);

        return views.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<SavedViewDTO> getPersonalViews(String modelCode, String pageKey) {
        String currentUserPid = MetaContext.getCurrentUserPid();

        List<SavedView> views = savedViewMapper.findPersonalViews(
                modelCode, pageKey, currentUserPid);

        return views.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<SavedViewDTO> getGlobalViews(String modelCode, String pageKey) {
        List<SavedView> views = savedViewMapper.findGlobalViews(modelCode, pageKey);

        return views.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public SavedViewDTO getDefaultView(String modelCode, String pageKey) {
        String currentUserPid = MetaContext.getCurrentUserPid();
        List<String> teamIds = getCurrentUserTeamIds();

        SavedView defaultView = savedViewMapper.findDefaultView(
                modelCode, pageKey, currentUserPid, teamIds);

        return defaultView != null ? toDTO(defaultView) : null;
    }

    @Override
    public SavedViewDTO setAsDefault(String pid) {
        log.info("Setting view as default: pid={}", pid);

        SavedView savedView = savedViewMapper.findByPid(pid);
        if (savedView == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Saved view not found: " + pid);
        }

        validateWriteAccess(savedView);

        String currentUserPid = MetaContext.getCurrentUserPid();

        // Clear other defaults based on scope
        clearDefaultFlagByScope(savedView.getScope(), savedView.getModelCode(),
                savedView.getPageKey(), currentUserPid, savedView.getTeamId());

        savedView.setIsDefault(true);
        savedView.setUpdatedAt(Instant.now());
        savedView.setUpdatedBy(currentUserPid);

        savedViewMapper.updateSavedView(savedView);
        recordSharedAudit(savedView, "SET_DEFAULT", Set.of("isDefault"));

        log.info("View set as default: pid={}", pid);
        return toDTO(savedView);
    }

    @Override
    public SavedViewDTO duplicate(String pid, String newName) {
        log.info("Duplicating view: pid={}, newName={}", pid, newName);

        SavedView sourceView = savedViewMapper.findByPid(pid);
        if (sourceView == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Saved view not found: " + pid);
        }

        validateReadAccess(sourceView);

        // Preserve source scope; for TEAM scope, copy teamId; for GLOBAL, check permission
        String duplicateScope = sourceView.getScope();
        String duplicateTeamId = null;

        if ("team".equals(duplicateScope)) {
            duplicateTeamId = sourceView.getTeamId();
            validateCurrentUserInTeam(duplicateTeamId);
        } else if ("global".equals(duplicateScope)) {
            // Only users with VIEW_MANAGE permission can duplicate as GLOBAL
            Long currentUserId = MetaContext.getCurrentUserId();
            boolean hasGlobalPermission = currentUserId != null
                    && userPermissionService.hasPermission(currentUserId, MetaPermission.VIEW_MANAGE);
            if (!hasGlobalPermission) {
                // Fall back to PERSONAL for users without GLOBAL permission
                duplicateScope = "personal";
            }
        }
        validateUserCopyAllowed(sourceView);

        // Create request for duplication
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(newName);
        request.setDescription(sourceView.getDescription());
        request.setModelCode(sourceView.getModelCode());
        request.setPageKey(sourceView.getPageKey());
        request.setScope(duplicateScope);
        request.setTeamId(duplicateTeamId);
        request.setViewType(sourceView.getViewType());
        request.setViewConfig(buildUserOwnedCopyConfig(sourceView, sourceView.getViewConfig()));
        request.setAllowFullModel(sourceView.getAllowFullModel());
        request.setIsDefault(false); // Don't copy default status
        request.setSortOrder(sourceView.getSortOrder());

        return create(request);
    }

    @Override
    public SavedViewDTO copyToPersonal(String pid, String newName, ViewConfig viewConfigOverride) {
        log.info("Copying view to personal scope: pid={}, newName={}", pid, newName);

        SavedView sourceView = savedViewMapper.findByPid(pid);
        if (sourceView == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Saved view not found: " + pid);
        }

        validateReadAccess(sourceView);
        validateUserCopyAllowed(sourceView);

        String sourceName = StringUtils.hasText(sourceView.getName()) ? sourceView.getName() : "View";
        String resolvedName = StringUtils.hasText(newName) ? newName : sourceName + " Copy";
        ViewConfig copiedConfig = buildUserOwnedCopyConfig(
                sourceView,
                mergeViewConfig(sourceView.getViewConfig(), viewConfigOverride));

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(resolvedName);
        request.setDescription(sourceView.getDescription());
        request.setModelCode(sourceView.getModelCode());
        request.setPageKey(sourceView.getPageKey());
        request.setScope("personal");
        request.setTeamId(null);
        request.setViewType(sourceView.getViewType());
        request.setViewConfig(copiedConfig);
        request.setAllowFullModel(sourceView.getAllowFullModel());
        request.setIsDefault(false);
        request.setSortOrder(sourceView.getSortOrder());

        return create(request);
    }

    @Override
    public List<SavedViewAuditEventDTO> getAuditEvents(String pid) {
        SavedView savedView = savedViewMapper.findByPid(pid);
        if (savedView == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Saved view not found: " + pid);
        }

        validateReadAccess(savedView);
        return auditTrailService.getAuditTrailByPid(
                        MetaContext.getCurrentTenantId(), AUDIT_ENTITY_TYPE, pid)
                .stream()
                .map(SavedViewAuditEventDTO::from)
                .toList();
    }

    @Override
    public SavedViewCapabilityCheckResponse checkCapability(SavedViewCapabilityCheckRequest request) {
        String viewType = request != null && StringUtils.hasText(request.getViewType())
                ? request.getViewType().toLowerCase()
                : "table";
        ViewConfig config = request != null && request.getViewConfig() != null
                ? request.getViewConfig()
                : new ViewConfig();
        List<String> missingFields = missingRequiredConfigFields(viewType, config);

        SavedViewCapabilityCheckResponse response = new SavedViewCapabilityCheckResponse();
        response.setViewType(viewType);
        response.setMissingFields(missingFields);
        if (missingFields.isEmpty()) {
            response.setStatus(CAPABILITY_AVAILABLE);
            response.setReasons(List.of());
            return response;
        }

        response.setStatus(CAPABILITY_BLOCKED);
        response.setReasons(missingFields.stream()
                .map(field -> new SavedViewCapabilityCheckResponse.Reason(
                        REASON_MISSING_REQUIRED_FIELD,
                        field,
                        "Missing required " + viewType + " viewConfig field: " + field))
                .toList());
        return response;
    }

    @Override
    public SavedViewDTO autoSave(AutoSaveViewRequest request) {
        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Look for existing implicit view for this user/model/page
        SavedView existing = savedViewMapper.findImplicitView(
                request.getModelCode(), request.getPageKey(), currentUserPid);

        if (existing != null) {
            // Merge new config into existing
            ViewConfig merged = existing.getViewConfig() != null ? existing.getViewConfig() : new ViewConfig();
            ViewConfig incoming = request.getViewConfig();
            if (incoming != null) {
                if (incoming.getColumns() != null) merged.setColumns(incoming.getColumns());
                if (incoming.getSorts() != null) merged.setSorts(incoming.getSorts());
                if (incoming.getFilters() != null) merged.setFilters(incoming.getFilters());
                if (incoming.getGroupBy() != null) merged.setGroupBy(incoming.getGroupBy());
                if (incoming.getPagination() != null) merged.setPagination(incoming.getPagination());
                if (incoming.getDensity() != null) merged.setDensity(incoming.getDensity());
                if (incoming.getRowHeight() != null) merged.setRowHeight(incoming.getRowHeight());
                if (incoming.getConditionalFormats() != null) merged.setConditionalFormats(incoming.getConditionalFormats());
                if (incoming.getToolbarActions() != null) merged.setToolbarActions(incoming.getToolbarActions());
            }
            existing.setViewConfig(merged);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(currentUserPid);
            savedViewMapper.updateSavedView(existing);
            return toDTO(existing);
        }

        // Create new implicit view
        SavedView savedView = new SavedView();
        savedView.setPid(UniqueIdGenerator.generate());
        savedView.setTenantId(tenantId);
        savedView.setName("Default View");
        savedView.setModelCode(request.getModelCode());
        savedView.setPageKey(request.getPageKey());
        savedView.setScope("personal");
        savedView.setViewType("table");
        savedView.setOwnerId(currentUserPid);
        savedView.setViewConfig(request.getViewConfig() != null ? request.getViewConfig() : new ViewConfig());
        savedView.setAllowFullModel(false);
        savedView.setIsDefault(true);
        savedView.setIsImplicit(true);
        savedView.setSortOrder(0);
        savedView.setDeletedFlag(false);
        savedView.setCreatedAt(Instant.now());
        savedView.setUpdatedAt(Instant.now());
        savedView.setCreatedBy(currentUserPid);
        savedView.setUpdatedBy(currentUserPid);

        // Clear other personal defaults before inserting
        savedViewMapper.clearPersonalDefaultFlag(request.getModelCode(), request.getPageKey(), currentUserPid);
        savedViewMapper.insertSavedView(savedView);

        log.info("Auto-created implicit view: pid={}, modelCode={}, pageKey={}",
                savedView.getPid(), request.getModelCode(), request.getPageKey());
        return toDTO(savedView);
    }

    @Override
    public boolean isNameUnique(String modelCode, String pageKey, String name, String excludePid) {
        String currentUserPid = MetaContext.getCurrentUserPid();
        int count = savedViewMapper.countByNameForUser(
                modelCode, pageKey, name, currentUserPid, excludePid);
        return count == 0;
    }

    // ==================== Private Helper Methods ====================

    /**
     * Validate that the pageKey references a row in {@code ab_page_schema}.
     * A SavedView stores user-configured column/sort/filter state keyed by pageKey.
     * If the page doesn't exist, the frontend {@code useSavedViews} hook does a
     * strict-equality match and silently returns no views, making the saved view
     * permanently invisible — so we reject at write time instead.
     *
     * <p>pageKey format: {@code <modelCode>_<list|form|detail>}
     * (e.g. {@code crm_lead_list}); the JSON filename under
     * {@code config/pages/<pageKey>.json} in the plugin.
     */
    private void validatePageKeyExists(String pageKey) {
        PageSchema page = pageSchemaMapper.selectAnyByPageKey(pageKey);
        if (page == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "[S-SAVED-VIEW] pageKey '" + pageKey + "' does not exist in ab_page_schema; "
                            + "define it as config/pages/" + pageKey + ".json in your plugin before creating a SavedView");
        }
    }

    private void validateCreateRequest(SavedViewCreateRequest request) {
        if (!StringUtils.hasText(request.getName())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "View name is required");
        }
        if (!StringUtils.hasText(request.getModelCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Model code is required");
        }
        if ("team".equals(request.getScope()) && !StringUtils.hasText(request.getTeamId())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Team ID is required for TEAM scope views");
        }
    }

    private void validateViewCountLimit(SavedViewCreateRequest request, String scope, String currentUserPid) {
        int limit = viewLimitForScope(scope);
        if (limit <= 0) {
            return;
        }

        String ownerId = "personal".equals(scope) ? currentUserPid : null;
        String teamId = "team".equals(scope) ? request.getTeamId() : null;
        int currentCount = savedViewMapper.countActiveNonImplicitViewsForScope(
                request.getModelCode(),
                request.getPageKey(),
                scope,
                ownerId,
                teamId);
        if (currentCount >= limit) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Saved view limit reached for " + scope + " scope: " + limit);
        }
    }

    private int viewLimitForScope(String scope) {
        return switch (scope) {
            case "personal" -> PERSONAL_VIEW_LIMIT;
            case "team" -> TEAM_VIEW_LIMIT;
            case "global" -> GLOBAL_VIEW_LIMIT;
            default -> 0;
        };
    }

    private void validateViewTypeConfig(String viewType, ViewConfig config) {
        String normalizedType = StringUtils.hasText(viewType) ? viewType.toLowerCase() : "table";
        ViewConfig cfg = config != null ? config : new ViewConfig();
        List<String> missingFields = missingRequiredConfigFields(normalizedType, cfg);
        if (!missingFields.isEmpty()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Missing required " + normalizedType + " viewConfig fields: "
                            + String.join(", ", missingFields));
        }
    }

    private List<String> missingRequiredConfigFields(String viewType, ViewConfig config) {
        String normalizedType = StringUtils.hasText(viewType) ? viewType.toLowerCase() : "table";
        ViewConfig cfg = config != null ? config : new ViewConfig();
        return switch (normalizedType) {
            case "kanban" -> missingConfigFields(
                    new RequiredConfigField("groupByField", cfg.getGroupByField()),
                    new RequiredConfigField("titleField", cfg.getTitleField()));
            case "calendar" -> missingConfigFields(
                    new RequiredConfigField("calendarDateField", cfg.getCalendarDateField()));
            case "gantt" -> missingConfigFields(
                    new RequiredConfigField("ganttStartDateField", cfg.getGanttStartDateField()),
                    new RequiredConfigField("ganttEndDateField", cfg.getGanttEndDateField()));
            case "gallery" -> missingConfigFields(
                    new RequiredConfigField("galleryImageField", cfg.getGalleryImageField()));
            case "tree" -> missingConfigFields(
                    new RequiredConfigField("treeParentField", cfg.getTreeParentField()));
            default -> List.of();
        };
    }

    private List<String> missingConfigFields(RequiredConfigField... fields) {
        return java.util.Arrays.stream(fields)
                .filter(field -> !StringUtils.hasText(field.value()))
                .map(RequiredConfigField::name)
                .toList();
    }

    private record RequiredConfigField(String name, String value) {
    }

    private ViewConfig mergeViewConfig(ViewConfig base, ViewConfig patch) {
        ViewConfig merged = new ViewConfig();
        if (base != null) {
            BeanUtils.copyProperties(base, merged);
        }
        if (patch != null) {
            BeanUtils.copyProperties(patch, merged, getNullPropertyNames(patch));
        }
        return merged;
    }

    private ViewConfig buildUserOwnedCopyConfig(SavedView sourceView, ViewConfig sourceConfig) {
        ViewConfig copiedConfig = mergeViewConfig(sourceConfig, null);
        ViewConfig.Meta sourceMeta = sourceConfig != null ? sourceConfig.getMeta() : null;
        copiedConfig.setMeta(ViewConfig.Meta.builder()
                .managedBy("user")
                .locked(false)
                .allowUserCopy(true)
                .allowUserOverride(true)
                .originViewPid(sourceView != null ? sourceView.getPid() : null)
                .capabilityStatus(sourceMeta != null ? sourceMeta.getCapabilityStatus() : null)
                .build());
        return copiedConfig;
    }

    private void validateUserCopyAllowed(SavedView savedView) {
        ViewConfig.Meta meta = getViewMeta(savedView);
        if (meta != null && Boolean.FALSE.equals(meta.getAllowUserCopy())) {
            throw new ValidationException(ResponseCode.FORBIDDEN,
                    "This view cannot be copied");
        }
    }

    private boolean isLockedView(SavedView savedView) {
        ViewConfig.Meta meta = getViewMeta(savedView);
        if (meta == null) {
            return false;
        }
        return Boolean.TRUE.equals(meta.getLocked()) || "plugin".equalsIgnoreCase(meta.getManagedBy());
    }

    private ViewConfig.Meta getViewMeta(SavedView savedView) {
        if (savedView == null || savedView.getViewConfig() == null) {
            return null;
        }
        return savedView.getViewConfig().getMeta();
    }

    private List<String> resolveActions(SavedView savedView) {
        List<String> actions = new ArrayList<>();
        actions.add("view");
        if (canCopy(savedView)) {
            actions.add("copy");
        }
        if (canSave(savedView)) {
            actions.add("save");
            actions.add("setDefault");
        }
        if (canManage(savedView)) {
            actions.add("manage");
            actions.add("delete");
            if (savedView != null && (savedView.isTeam() || savedView.isGlobal())) {
                actions.add("share");
            }
        }
        return actions;
    }

    private String resolveEffectivePermission(SavedView savedView) {
        if (canManage(savedView)) {
            return "manage";
        }
        if (canSave(savedView)) {
            return "save";
        }
        return "view";
    }

    private boolean canCopy(SavedView savedView) {
        ViewConfig.Meta meta = getViewMeta(savedView);
        return meta == null || !Boolean.FALSE.equals(meta.getAllowUserCopy());
    }

    private boolean canManage(SavedView savedView) {
        return canSave(savedView);
    }

    private boolean canSave(SavedView savedView) {
        if (savedView == null || isLockedView(savedView)) {
            return false;
        }

        String currentUserPid = MetaContext.getCurrentUserPid();
        if (savedView.isPersonal()) {
            return currentUserPid != null && currentUserPid.equals(savedView.getOwnerId());
        }

        if (savedView.isTeam()) {
            if (!getCurrentUserTeamIds().contains(savedView.getTeamId())) {
                return false;
            }
            if (currentUserPid != null && currentUserPid.equals(savedView.getCreatedBy())) {
                return true;
            }
            Long currentUserId = MetaContext.getCurrentUserId();
            return currentUserId != null
                    && (userPermissionService.hasPermission(currentUserId, MetaPermission.VIEW_TEAM_MANAGE)
                    || userPermissionService.hasPermission(currentUserId, MetaPermission.VIEW_MANAGE));
        }

        if (savedView.isGlobal()) {
            if (currentUserPid != null && currentUserPid.equals(savedView.getCreatedBy())) {
                return true;
            }
            Long currentUserId = MetaContext.getCurrentUserId();
            return currentUserId != null
                    && userPermissionService.hasPermission(currentUserId, MetaPermission.VIEW_MANAGE);
        }

        return false;
    }

    private String[] getNullPropertyNames(Object source) {
        try {
            BeanWrapper wrapper = new BeanWrapperImpl(source);
            Set<String> emptyNames = new HashSet<>();
            for (var descriptor : wrapper.getPropertyDescriptors()) {
                String propertyName = descriptor.getName();
                if ("class".equals(propertyName) || wrapper.getPropertyValue(propertyName) == null) {
                    emptyNames.add(propertyName);
                }
            }
            return emptyNames.toArray(String[]::new);
        } catch (BeansException ex) {
            log.warn("Failed to inspect null viewConfig properties: {}", ex.getMessage());
            return new String[0];
        }
    }

    private void recordSharedAudit(SavedView savedView, String operationType, Set<String> changedFields) {
        if (savedView == null || savedView.isPersonal()) {
            return;
        }
        Set<String> fields = changedFields != null ? changedFields : Set.of();
        if ("UPDATE".equals(operationType) && fields.isEmpty()) {
            return;
        }

        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("scope", savedView.getScope());
        metadata.put("modelCode", savedView.getModelCode());
        if (StringUtils.hasText(savedView.getPageKey())) {
            metadata.put("pageKey", savedView.getPageKey());
        }
        if (StringUtils.hasText(savedView.getTeamId())) {
            metadata.put("teamId", savedView.getTeamId());
        }
        metadata.put("summary", buildSharedAuditSummary(operationType, fields));

        auditTrailService.recordAudit(AuditTrailEvent.builder()
                .tenantId(savedView.getTenantId())
                .eventType(AUDIT_EVENT_TYPE)
                .entityType(AUDIT_ENTITY_TYPE)
                .entityId(savedView.getId())
                .entityPid(savedView.getPid())
                .commandCode("saved_view:" + operationType.toLowerCase())
                .operationType(operationType)
                .actorId(MetaContext.exists() ? MetaContext.getCurrentUserId() : null)
                .actorName(MetaContext.exists() ? MetaContext.getCurrentUsername() : null)
                .changedFields(fields.toArray(String[]::new))
                .metadata(metadata)
                .build());
    }

    private String buildSharedAuditSummary(String operationType, Set<String> changedFields) {
        if ("CREATE".equals(operationType)) {
            return "Created shared view";
        }
        if ("DELETE".equals(operationType)) {
            return "Deleted shared view";
        }
        if ("SET_DEFAULT".equals(operationType)) {
            return "Changed shared default view";
        }
        if (changedFields.contains("viewConfig")) {
            return "Saved shared view configuration";
        }
        return "Updated shared view metadata: " + String.join(", ", changedFields);
    }

    private void validateReadAccess(SavedView savedView) {
        String currentUserPid = MetaContext.getCurrentUserPid();

        if (savedView.isGlobal()) {
            return; // Global views are accessible to all
        }

        if (savedView.isPersonal()) {
            if (!currentUserPid.equals(savedView.getOwnerId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have access to this view");
            }
            return;
        }

        if (savedView.isTeam()) {
            List<String> teamIds = getCurrentUserTeamIds();
            if (!teamIds.contains(savedView.getTeamId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You are not a member of team: " + savedView.getTeamId());
            }
            if (currentUserPid.equals(savedView.getCreatedBy())) {
                return;
            }
        }
    }

    private void validateWriteAccess(SavedView savedView) {
        String currentUserPid = MetaContext.getCurrentUserPid();
        if (isLockedView(savedView)) {
            throw new ValidationException(ResponseCode.FORBIDDEN,
                    "This view is managed by a plugin. Copy it before editing");
        }

        // Only owner can modify personal views
        if (savedView.isPersonal()) {
            if (!currentUserPid.equals(savedView.getOwnerId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You can only modify your own views");
            }
            return;
        }

        if (savedView.isTeam()) {
            validateCurrentUserInTeam(savedView.getTeamId());
            if (currentUserPid.equals(savedView.getCreatedBy())) {
                return;
            }
            Long currentUserId = MetaContext.getCurrentUserId();
            boolean hasTeamManagePermission = currentUserId != null
                    && (userPermissionService.hasPermission(currentUserId, MetaPermission.VIEW_TEAM_MANAGE)
                    || userPermissionService.hasPermission(currentUserId, MetaPermission.VIEW_MANAGE));
            if (!hasTeamManagePermission) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have permission to modify this team view");
            }
            return;
        }

        if (savedView.isGlobal()) {
            if (currentUserPid.equals(savedView.getCreatedBy())) {
                return;
            }

            Long currentUserId = MetaContext.getCurrentUserId();
            boolean hasGlobalManagePermission = currentUserId != null
                    && userPermissionService.hasPermission(currentUserId, MetaPermission.VIEW_MANAGE);
            if (!hasGlobalManagePermission) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have permission to modify this global view");
            }
        }
    }

    private void validateCurrentUserInTeam(String teamId) {
        if (!StringUtils.hasText(teamId)) {
            return;
        }
        List<String> teamIds = getCurrentUserTeamIds();
        if (!teamIds.contains(teamId)) {
            throw new ValidationException(ResponseCode.FORBIDDEN,
                    "You are not a member of team: " + teamId);
        }
    }

    private List<String> getCurrentUserTeamIds() {
        return currentUserTeamResolver.resolveCurrentUserTeamIds();
    }

    /**
     * Clear default flag based on view scope.
     * PERSONAL: clears for same user. TEAM: clears for same team. GLOBAL: clears all global defaults.
     */
    private void clearDefaultFlagByScope(String scope, String modelCode, String pageKey,
                                          String currentUserPid, String teamId) {
        switch (scope) {
            case "personal" -> savedViewMapper.clearPersonalDefaultFlag(modelCode, pageKey, currentUserPid);
            case "team" -> {
                if (StringUtils.hasText(teamId)) {
                    savedViewMapper.clearTeamDefaultFlag(modelCode, pageKey, teamId);
                }
            }
            case "global" -> savedViewMapper.clearGlobalDefaultFlag(modelCode, pageKey);
            default -> log.warn("Unknown scope '{}', skipping default flag clear", scope);
        }
    }

    /**
     * Resolve team name from teamId via TeamMapper lookup.
     */
    private String resolveTeamName(String teamId) {
        if (!StringUtils.hasText(teamId)) {
            return null;
        }
        try {
            Team team = teamMapper.findByPid(teamId);
            return team != null ? team.getName() : null;
        } catch (Exception e) {
            log.warn("Failed to resolve team name for teamId={}: {}", teamId, e.getMessage());
            return null;
        }
    }

    private SavedViewDTO toDTO(SavedView entity) {
        if (entity == null) {
            return null;
        }

        SavedViewDTO dto = SavedViewDTO.builder()
                .id(entity.getId())
                .pid(entity.getPid())
                .tenantId(entity.getTenantId())
                .name(entity.getName())
                .description(entity.getDescription())
                .modelCode(entity.getModelCode())
                .pageKey(entity.getPageKey())
                .scope(entity.getScope())
                .viewType(entity.getViewType())
                .ownerId(entity.getOwnerId())
                .teamId(entity.getTeamId())
                .viewConfig(entity.getViewConfig())
                .allowFullModel(entity.getAllowFullModel())
                .isDefault(entity.getIsDefault())
                .isImplicit(entity.getIsImplicit())
                .sortOrder(entity.getSortOrder())
                .effectivePermission(resolveEffectivePermission(entity))
                .actions(resolveActions(entity))
                .dirty(false)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .build();

        // Populate teamName for TEAM scope views
        if ("team".equals(entity.getScope()) && StringUtils.hasText(entity.getTeamId())) {
            dto.setTeamName(resolveTeamName(entity.getTeamId()));
        }

        return dto;
    }
}
