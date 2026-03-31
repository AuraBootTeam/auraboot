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
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.SavedViewUpdateRequest;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.auraboot.framework.view.service.SavedViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
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

    private final SavedViewMapper savedViewMapper;
    private final UserPermissionService userPermissionService;
    private final CurrentUserTeamResolver currentUserTeamResolver;
    private final TeamMapper teamMapper;

    @Override
    public SavedViewDTO create(SavedViewCreateRequest request) {
        log.info("Creating saved view: name={}, modelCode={}", request.getName(), request.getModelCode());

        validateCreateRequest(request);
        if ("team".equals(request.getScope())) {
            validateCurrentUserInTeam(request.getTeamId());
        }

        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Check name uniqueness for personal views
        if (!isNameUnique(request.getModelCode(), request.getPageKey(),
                request.getName(), null)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "View name already exists: " + request.getName());
        }

        String scope = StringUtils.hasText(request.getScope()) ? request.getScope() : "personal";

        SavedView savedView = new SavedView();
        savedView.setPid(UniqueIdGenerator.generate());
        savedView.setTenantId(tenantId);
        savedView.setName(request.getName());
        savedView.setDescription(request.getDescription());
        savedView.setModelCode(request.getModelCode());
        savedView.setPageKey(request.getPageKey());
        savedView.setScope(scope);
        savedView.setViewType(StringUtils.hasText(request.getViewType()) ? request.getViewType() : "table");
        savedView.setOwnerId(currentUserPid);
        savedView.setTeamId(request.getTeamId());
        savedView.setViewConfig(request.getViewConfig() != null ? request.getViewConfig() : new ViewConfig());
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

        // Check name uniqueness if name is being changed
        if (StringUtils.hasText(request.getName()) && !request.getName().equals(savedView.getName())) {
            if (!isNameUnique(savedView.getModelCode(), savedView.getPageKey(),
                    request.getName(), pid)) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "View name already exists: " + request.getName());
            }
            savedView.setName(request.getName());
        }

        if (request.getDescription() != null) {
            savedView.setDescription(request.getDescription());
        }
        if (request.getScope() != null) {
            savedView.setScope(request.getScope());
        }
        if (request.getTeamId() != null) {
            savedView.setTeamId(request.getTeamId());
        }
        if (request.getViewConfig() != null) {
            savedView.setViewConfig(request.getViewConfig());
        }
        if (request.getAllowFullModel() != null) {
            savedView.setAllowFullModel(request.getAllowFullModel());
        }
        if (request.getIsDefault() != null) {
            // If setting as default, clear other defaults first based on scope
            if (Boolean.TRUE.equals(request.getIsDefault())) {
                clearDefaultFlagByScope(savedView.getScope(), savedView.getModelCode(),
                        savedView.getPageKey(), currentUserPid, savedView.getTeamId());
            }
            savedView.setIsDefault(request.getIsDefault());
        }
        if (request.getSortOrder() != null) {
            savedView.setSortOrder(request.getSortOrder());
        }

        if ("team".equals(savedView.getScope()) && !StringUtils.hasText(savedView.getTeamId())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Team ID is required for TEAM scope views");
        }
        if (savedView.isTeam()) {
            validateCurrentUserInTeam(savedView.getTeamId());
        }

        savedView.setUpdatedAt(Instant.now());
        savedView.setUpdatedBy(currentUserPid);

        savedViewMapper.updateSavedView(savedView);

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

        // Create request for duplication
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(newName);
        request.setDescription(sourceView.getDescription());
        request.setModelCode(sourceView.getModelCode());
        request.setPageKey(sourceView.getPageKey());
        request.setScope(duplicateScope);
        request.setTeamId(duplicateTeamId);
        request.setViewType(sourceView.getViewType());
        request.setViewConfig(sourceView.getViewConfig());
        request.setAllowFullModel(sourceView.getAllowFullModel());
        request.setIsDefault(false); // Don't copy default status
        request.setSortOrder(sourceView.getSortOrder());

        return create(request);
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
            if (currentUserPid.equals(savedView.getCreatedBy())) {
                return;
            }
            List<String> teamIds = getCurrentUserTeamIds();
            if (!teamIds.contains(savedView.getTeamId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have access to this team view");
            }
        }
    }

    private void validateWriteAccess(SavedView savedView) {
        String currentUserPid = MetaContext.getCurrentUserPid();

        // Only owner can modify personal views
        if (savedView.isPersonal()) {
            if (!currentUserPid.equals(savedView.getOwnerId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You can only modify your own views");
            }
            return;
        }

        if (savedView.isTeam()) {
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
                .sortOrder(entity.getSortOrder())
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
