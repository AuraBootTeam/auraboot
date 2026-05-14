package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.dashboard.dto.*;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.dashboard.mapper.DashboardMapper;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Dashboard Service Implementation
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;
    private final ObjectMapper objectMapper;
    private final VersionHistoryService versionHistoryService;
    private final UserPermissionService userPermissionService;
    private final CurrentUserTeamResolver currentUserTeamResolver;
    private final MenuService menuService;
    private final PermissionMapper permissionMapper;

    // Default layout configuration
    private static final String DEFAULT_LAYOUT_CONFIG = """
        {"columns": 12, "rowHeight": 100, "gap": 16, "compactType": "vertical"}
        """;

    @Override
    @Transactional
    public DashboardDTO create(DashboardCreateRequest request) {
        log.info("Creating dashboard: title={}", request.getTitle());

        validateCreateRequest(request);
        if ("team".equals(request.getScope())) {
            validateCurrentUserInTeam(request.getTeamId());
        }

        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Generate code if not provided
        String code = StringUtils.hasText(request.getCode())
                ? request.getCode()
                : "dashboard_" + UniqueIdGenerator.generate().toLowerCase();

        // Check code uniqueness
        if (!isCodeUnique(code, null)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Dashboard code already exists: " + code);
        }

        // Default scope to PERSONAL
        String scope = StringUtils.hasText(request.getScope()) ? request.getScope() : "personal";

        Dashboard dashboard = new Dashboard();
        dashboard.setPid(UniqueIdGenerator.generate());
        dashboard.setTenantId(tenantId);
        dashboard.setCode(code);
        dashboard.setTitle(request.getTitle());
        dashboard.setDescription(request.getDescription());
        dashboard.setScope(scope);
        dashboard.setOwnerId(currentUserPid);
        dashboard.setTeamId(request.getTeamId());
        dashboard.setLayoutConfig(getLayoutConfig(request.getLayoutConfig()));
        dashboard.setWidgets(request.getWidgets() != null ? request.getWidgets() : parseJsonArray("[]"));
        dashboard.setStatus(StatusConstants.DRAFT);
        dashboard.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : false);
        dashboard.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        dashboard.setExtension(request.getExtension());
        dashboard.setDeletedFlag(false);
        dashboard.setCreatedAt(Instant.now());
        dashboard.setUpdatedAt(Instant.now());
        dashboard.setCreatedBy(currentUserPid);
        dashboard.setUpdatedBy(currentUserPid);

        // If setting as default, clear other defaults first
        if (Boolean.TRUE.equals(dashboard.getIsDefault()) && "personal".equals(scope)) {
            dashboardMapper.clearPersonalDefaultFlag(tenantId, currentUserPid);
        }

        dashboardMapper.insertDashboard(dashboard);

        // Record initial version
        versionHistoryService.recordVersion("dashboard", dashboard.getPid(), "create", null);

        log.info("Dashboard created: pid={}", dashboard.getPid());
        return toDTO(dashboard);
    }

    @Override
    public DashboardDTO findByPid(String pid) {
        Dashboard dashboard = dashboardMapper.findByPid(pid);
        if (dashboard == null) {
            return null;
        }

        validateReadAccess(dashboard);
        return toDTO(dashboard);
    }

    @Override
    public DashboardDTO findByCode(String code) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Dashboard dashboard = dashboardMapper.findByCode(tenantId, code);
        if (dashboard == null) {
            return null;
        }

        validateReadAccess(dashboard);
        return toDTO(dashboard);
    }

    @Override
    @Transactional
    public DashboardDTO update(String pid, DashboardUpdateRequest request) {
        log.info("Updating dashboard: pid={}", pid);

        Dashboard dashboard = dashboardMapper.findByPid(pid);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + pid);
        }

        validateWriteAccess(dashboard);

        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        if (StringUtils.hasText(request.getTitle())) {
            dashboard.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            dashboard.setDescription(request.getDescription());
        }
        if (request.getScope() != null) {
            dashboard.setScope(request.getScope());
        }
        if (request.getTeamId() != null) {
            dashboard.setTeamId(request.getTeamId());
        }
        if (request.getLayoutConfig() != null) {
            dashboard.setLayoutConfig(request.getLayoutConfig());
        }
        if (request.getWidgets() != null) {
            dashboard.setWidgets(request.getWidgets());
        }
        if (request.getIsDefault() != null) {
            // If setting as default, clear other defaults first
            if (Boolean.TRUE.equals(request.getIsDefault()) && dashboard.isPersonal()) {
                dashboardMapper.clearPersonalDefaultFlag(tenantId, currentUserPid);
            }
            dashboard.setIsDefault(request.getIsDefault());
        }
        if (request.getSortOrder() != null) {
            dashboard.setSortOrder(request.getSortOrder());
        }
        if (request.getExtension() != null) {
            dashboard.setExtension(request.getExtension());
        }
        if (dashboard.isTeam()) {
            validateCurrentUserInTeam(dashboard.getTeamId());
        }

        dashboard.setUpdatedAt(Instant.now());
        dashboard.setUpdatedBy(currentUserPid);

        dashboardMapper.updateDashboard(dashboard);

        // Record update version
        versionHistoryService.recordVersion("dashboard", pid, "update", null);

        log.info("Dashboard updated: pid={}", pid);
        return toDTO(dashboard);
    }

    @Override
    @Transactional
    public void delete(String pid) {
        log.info("Deleting dashboard: pid={}", pid);

        Dashboard dashboard = dashboardMapper.findByPid(pid);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + pid);
        }

        validateWriteAccess(dashboard);

        dashboardMapper.deleteById(dashboard.getId());

        log.info("Dashboard deleted: pid={}", pid);
    }

    @Override
    public List<DashboardDTO> getAccessibleDashboards(DashboardQueryRequest request) {
        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> teamIds = getCurrentUserTeamIds();

        List<Dashboard> dashboards = dashboardMapper.findAccessibleDashboards(
                tenantId, currentUserPid, teamIds, request.getStatus(), request.getTitle(), request.getScope());

        return dashboards.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<DashboardDTO> getPersonalDashboards() {
        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        List<Dashboard> dashboards = dashboardMapper.findPersonalDashboards(tenantId, currentUserPid);

        return dashboards.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<DashboardDTO> getGlobalDashboards() {
        Long tenantId = MetaContext.getCurrentTenantId();

        List<Dashboard> dashboards = dashboardMapper.findGlobalDashboards(tenantId);

        return dashboards.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public DashboardDTO getDefaultDashboard() {
        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> teamIds = getCurrentUserTeamIds();

        Dashboard defaultDashboard = dashboardMapper.findDefaultDashboard(tenantId, currentUserPid, teamIds);

        return defaultDashboard != null ? toDTO(defaultDashboard) : null;
    }

    @Override
    public DashboardDTO setAsDefault(String pid) {
        log.info("Setting dashboard as default: pid={}", pid);

        Dashboard dashboard = dashboardMapper.findByPid(pid);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + pid);
        }

        validateWriteAccess(dashboard);

        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Clear other defaults for personal dashboards
        if (dashboard.isPersonal()) {
            dashboardMapper.clearPersonalDefaultFlag(tenantId, currentUserPid);
        }

        dashboard.setIsDefault(true);
        dashboard.setUpdatedAt(Instant.now());
        dashboard.setUpdatedBy(currentUserPid);

        dashboardMapper.updateDashboard(dashboard);

        log.info("Dashboard set as default: pid={}", pid);
        return toDTO(dashboard);
    }

    @Override
    @Transactional
    public DashboardDTO publish(String pid) {
        log.info("Publishing dashboard: pid={}", pid);

        Dashboard dashboard = dashboardMapper.findByPid(pid);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + pid);
        }

        validateWriteAccess(dashboard);
        validateWidgetsForPublish(dashboard);

        String currentUserPid = MetaContext.getCurrentUserPid();

        dashboard.setStatus(StatusConstants.PUBLISHED);
        dashboard.setUpdatedAt(Instant.now());
        dashboard.setUpdatedBy(currentUserPid);

        dashboardMapper.updateDashboard(dashboard);

        // Record publish version
        versionHistoryService.recordVersion("dashboard", pid, "publish", null);

        log.info("Dashboard published: pid={}", pid);
        return toDTO(dashboard);
    }

    @Override
    @Transactional
    public DashboardDTO unpublish(String pid) {
        log.info("Unpublishing dashboard: pid={}", pid);

        Dashboard dashboard = dashboardMapper.findByPid(pid);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + pid);
        }

        validateWriteAccess(dashboard);

        // Auto-unmount from menu if currently mounted
        JsonNode ext = dashboard.getExtension();
        if (ext != null && ext.has("menuMounted") && ext.get("menuMounted").asBoolean()) {
            log.info("Auto-unmounting dashboard from menu before unpublish: pid={}", pid);
            unmountFromMenu(pid);
            // Re-fetch dashboard since extension was modified
            dashboard = dashboardMapper.findByPid(pid);
        }

        String currentUserPid = MetaContext.getCurrentUserPid();

        dashboard.setStatus(StatusConstants.DRAFT);
        dashboard.setUpdatedAt(Instant.now());
        dashboard.setUpdatedBy(currentUserPid);

        dashboardMapper.updateDashboard(dashboard);

        // Record unpublish version
        versionHistoryService.recordVersion("dashboard", pid, "unpublish", null);

        log.info("Dashboard unpublished: pid={}", pid);
        return toDTO(dashboard);
    }

    @Override
    public DashboardDTO duplicate(String pid, String newTitle) {
        log.info("Duplicating dashboard: pid={}, newTitle={}", pid, newTitle);

        Dashboard sourceDashboard = dashboardMapper.findByPid(pid);
        if (sourceDashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + pid);
        }

        validateReadAccess(sourceDashboard);

        // Create request for duplication
        DashboardCreateRequest request = new DashboardCreateRequest();
        request.setTitle(newTitle);
        request.setDescription(sourceDashboard.getDescription());
        request.setScope("personal"); // Duplicates are always personal
        request.setLayoutConfig(sourceDashboard.getLayoutConfig());
        request.setWidgets(sourceDashboard.getWidgets());
        request.setIsDefault(false);
        request.setSortOrder(sourceDashboard.getSortOrder());
        request.setExtension(sourceDashboard.getExtension());

        return create(request);
    }

    @Override
    public boolean isCodeUnique(String code, String excludePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int count = dashboardMapper.countByCode(tenantId, code, excludePid);
        return count == 0;
    }

    // ==================== Mount/Unmount Menu Operations ====================

    @Override
    @Transactional
    public void mountToMenu(String dashboardPid, MountMenuRequest request) {
        log.info("Mounting dashboard to menu: pid={}, parentCode={}", dashboardPid, request.getParentCode());

        Dashboard dashboard = dashboardMapper.findByPid(dashboardPid);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + dashboardPid);
        }

        // Validate: must be PUBLISHED + GLOBAL + not already mounted
        if (!StatusConstants.PUBLISHED.equals(dashboard.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Dashboard must be published before mounting to menu");
        }
        if (!"global".equals(dashboard.getScope())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Only GLOBAL dashboards can be mounted to menu");
        }

        JsonNode ext = dashboard.getExtension();
        if (ext != null && ext.has("menuMounted") && ext.get("menuMounted").asBoolean()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Dashboard is already mounted to menu");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long currentUserId = MetaContext.getCurrentUserId();

        // Generate menu code and path
        String menuCode = "dashboard_view_" + dashboard.getCode().toUpperCase().replace("-", "_");
        String menuPath = "/dashboards/view/" + dashboard.getCode();

        // Idempotency: check if menu/permission already exist
        Permission existingPerm = permissionMapper.findByCode(menuCode);
        if (existingPerm != null) {
            log.info("Permission already exists for menu code {}, skipping mount", menuCode);
            return;
        }

        // Create Permission record
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setTenantId(tenantId);
        permission.setCode(menuCode);
        permission.setName("View Dashboard: " + dashboard.getTitle());
        permission.setDescription("Auto-generated permission for dashboard menu: " + dashboard.getCode());
        permission.setResourceType("menu");
        permission.setResourceCode(menuPath);
        permission.setAction("view");
        permission.setSource("generated");
        permission.setSourceRef("dashboard:" + dashboardPid);
        permission.setStatus(StatusConstants.ACTIVE);
        permission.setDeletedFlag(false);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        permission.setCreatedBy(currentUserId);
        permission.setUpdatedBy(currentUserId);

        permissionMapper.insert(permission);

        // Find parent menu by code
        String parentCode = StringUtils.hasText(request.getParentCode())
                ? request.getParentCode()
                : "dashboard_management";
        Menu parentMenu = menuService.getOne(
                new LambdaQueryWrapper<Menu>()
                        .eq(Menu::getCode, parentCode)
                        .eq(Menu::getDeletedFlag, false));
        if (parentMenu == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND,
                    "Parent menu not found: " + parentCode);
        }

        // Create Menu record
        Menu menu = new Menu();
        menu.setTenantId(tenantId);
        menu.setPid(UniqueIdGenerator.generate());
        menu.setCode(menuCode);
        menu.setName(dashboard.getTitle());
        menu.setPath(menuPath);
        menu.setIcon(request.getIcon() != null ? request.getIcon() : "bar-chart");
        menu.setType(1); // Menu type (leaf node)
        menu.setPermissionCode(menuCode);
        menu.setParentId(parentMenu.getId());
        menu.setOrderNo(request.getOrderNo() != null ? request.getOrderNo() : 50);
        menu.setVisible(true);
        menu.setCreatedBy(currentUserId);

        menuService.createMenu(menu);

        // Update dashboard extension with mount info
        ObjectNode newExt = ext != null ? ((ObjectNode) ext.deepCopy()) : objectMapper.createObjectNode();
        newExt.put("menuMounted", true);
        newExt.put("menuCode", menuCode);
        dashboardMapper.updateExtension(dashboardPid, newExt);

        log.info("Dashboard mounted to menu: pid={}, menuCode={}", dashboardPid, menuCode);
    }

    @Override
    @Transactional
    public void unmountFromMenu(String dashboardPid) {
        log.info("Unmounting dashboard from menu: pid={}", dashboardPid);

        Dashboard dashboard = dashboardMapper.findByPid(dashboardPid);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Dashboard not found: " + dashboardPid);
        }

        JsonNode ext = dashboard.getExtension();
        if (ext == null || !ext.has("menuCode")) {
            log.warn("Dashboard is not mounted to menu: pid={}", dashboardPid);
            return;
        }

        String menuCode = ext.get("menuCode").asText();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Find and delete menu by code
        Menu existingMenu = menuService.getOne(
                new LambdaQueryWrapper<Menu>()
                        .eq(Menu::getCode, menuCode)
                        .eq(Menu::getDeletedFlag, false));
        if (existingMenu != null) {
            menuService.deleteMenu(existingMenu.getId());
            log.info("Deleted menu: code={}, id={}", menuCode, existingMenu.getId());
        }

        // Find and delete permission by code (uses @TableLogic soft delete)
        Permission existingPermission = permissionMapper.findByCode(menuCode);
        if (existingPermission != null) {
            permissionMapper.deleteById(existingPermission.getId());
            log.info("Deleted permission: code={}, id={}", menuCode, existingPermission.getId());
        }

        // Clear menuMounted/menuCode from extension
        ObjectNode newExt = ext != null ? ((ObjectNode) ext.deepCopy()) : objectMapper.createObjectNode();
        newExt.remove("menuMounted");
        newExt.remove("menuCode");
        dashboardMapper.updateExtension(dashboardPid, newExt);

        log.info("Dashboard unmounted from menu: pid={}", dashboardPid);
    }

    // ==================== Workbench ====================

    private static final String WORKBENCH_SCOPE = "workbench";

    @Override
    @Transactional
    public DashboardDTO getOrCreateWorkbench() {
        String currentUserPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Try to find existing workbench
        Dashboard existing = dashboardMapper.findWorkbench(tenantId, currentUserPid);
        if (existing != null) {
            return toDTO(existing);
        }

        // Create from template
        log.info("Creating default workbench for user: {}", currentUserPid);

        Dashboard workbench = new Dashboard();
        workbench.setPid(UniqueIdGenerator.generate());
        workbench.setTenantId(tenantId);
        workbench.setCode("user_workbench_" + currentUserPid);
        workbench.setTitle("My Workbench");
        workbench.setDescription("Personal workbench");
        workbench.setScope(WORKBENCH_SCOPE);
        workbench.setOwnerId(currentUserPid);
        workbench.setLayoutConfig(WorkbenchTemplateProvider.getDefaultLayoutConfig(objectMapper));
        workbench.setWidgets(WorkbenchTemplateProvider.getDefaultWidgets(objectMapper));
        workbench.setStatus(StatusConstants.PUBLISHED);
        workbench.setIsDefault(false);
        workbench.setSortOrder(0);
        workbench.setDeletedFlag(false);
        workbench.setCreatedAt(Instant.now());
        workbench.setUpdatedAt(Instant.now());
        workbench.setCreatedBy(currentUserPid);
        workbench.setUpdatedBy(currentUserPid);

        dashboardMapper.insertDashboard(workbench);

        log.info("Workbench created: pid={}", workbench.getPid());
        return toDTO(workbench);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Validate widgets before publishing.
     * Rejects dashboards with no widgets or widgets missing config.
     */
    private void validateWidgetsForPublish(Dashboard dashboard) {
        JsonNode widgets = dashboard.getWidgets();
        // Allow publishing dashboards with no widgets (empty dashboard is valid for staging/placeholder use)
        if (widgets == null || !widgets.isArray() || widgets.isEmpty()) {
            return;
        }

        for (int i = 0; i < widgets.size(); i++) {
            JsonNode widget = widgets.get(i);
            JsonNode config = widget.get("config");
            if (config == null || config.isEmpty()) {
                String widgetId = resolveWidgetId(widget, i);
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Widget '" + widgetId + "' has no configuration. Configure the widget before publishing.");
            }

            // Title can be on the widget top-level or inside config
            boolean hasTitle = hasNonBlankField(widget, "title") || hasNonBlankField(config, "title");
            if (!hasTitle) {
                String widgetId = resolveWidgetId(widget, i);
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Widget '" + widgetId + "' has no title. Set a title before publishing.");
            }
        }
    }

    private static String resolveWidgetId(JsonNode widget, int index) {
        if (widget.has("id") && StringUtils.hasText(widget.get("id").asText())) {
            return widget.get("id").asText();
        }
        if (widget.has("i") && StringUtils.hasText(widget.get("i").asText())) {
            return widget.get("i").asText();
        }
        return "index=" + index;
    }

    private static boolean hasNonBlankField(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return false;
        }
        return hasNonBlankText(node.get(field));
    }

    private static boolean hasNonBlankText(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return StringUtils.hasText(value.asText());
        }
        if (value.isObject()) {
            for (JsonNode child : value) {
                if (hasNonBlankText(child)) {
                    return true;
                }
            }
            return false;
        }
        return StringUtils.hasText(value.asText());
    }

    private void validateCreateRequest(DashboardCreateRequest request) {
        if (!StringUtils.hasText(request.getTitle())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Dashboard title is required");
        }
        if ("team".equals(request.getScope()) && !StringUtils.hasText(request.getTeamId())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Team ID is required for TEAM scope dashboards");
        }
    }

    private void validateReadAccess(Dashboard dashboard) {
        String currentUserPid = MetaContext.getCurrentUserPid();

        if (dashboard.isGlobal()) {
            return; // Global dashboards are accessible to all
        }

        if (dashboard.isPersonal()) {
            if (!currentUserPid.equals(dashboard.getOwnerId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have access to this dashboard");
            }
            return;
        }

        if (dashboard.isTeam()) {
            if (currentUserPid.equals(dashboard.getCreatedBy())) {
                return;
            }
            List<String> teamIds = getCurrentUserTeamIds();
            if (!teamIds.contains(dashboard.getTeamId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have access to this team dashboard");
            }
        }
    }

    private void validateWriteAccess(Dashboard dashboard) {
        String currentUserPid = MetaContext.getCurrentUserPid();

        // Only owner can modify personal dashboards
        if (dashboard.isPersonal()) {
            if (!currentUserPid.equals(dashboard.getOwnerId())) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You can only modify your own dashboards");
            }
            return;
        }

        // For team and global dashboards, require additional permissions
        if (dashboard.isTeam()) {
            if (currentUserPid.equals(dashboard.getCreatedBy())) {
                return;
            }
            Long currentUserId = MetaContext.getCurrentUserId();
            boolean hasTeamManagePermission = currentUserId != null
                    && (userPermissionService.hasPermission(currentUserId, MetaPermission.DASHBOARD_TEAM_MANAGE)
                    || userPermissionService.hasPermission(currentUserId, MetaPermission.DASHBOARD_MANAGE));
            if (!hasTeamManagePermission) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have permission to modify this team dashboard");
            }
            return;
        }

        if (dashboard.isGlobal()) {
            if (currentUserPid.equals(dashboard.getCreatedBy())) {
                return; // Creator can always modify
            }
            // Allow users with DASHBOARD_MANAGE permission to modify any global dashboard
            Long currentUserId = MetaContext.getCurrentUserId();
            boolean hasManagePermission = currentUserId != null
                    && userPermissionService.hasPermission(currentUserId, MetaPermission.DASHBOARD_MANAGE);
            if (!hasManagePermission) {
                throw new ValidationException(ResponseCode.FORBIDDEN,
                        "You don't have permission to modify this global dashboard");
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

    private JsonNode getLayoutConfig(JsonNode providedConfig) {
        if (providedConfig != null) {
            return providedConfig;
        }
        try {
            return objectMapper.readTree(DEFAULT_LAYOUT_CONFIG);
        } catch (Exception e) {
            log.error("Failed to parse default layout config", e);
            return null;
        }
    }

    private JsonNode parseJsonArray(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to parse JSON array", e);
            return null;
        }
    }

    private DashboardDTO toDTO(Dashboard entity) {
        if (entity == null) {
            return null;
        }

        DashboardDTO dto = DashboardDTO.builder()
                .id(entity.getId())
                .pid(entity.getPid())
                .tenantId(entity.getTenantId())
                .code(entity.getCode())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .scope(entity.getScope())
                .ownerId(entity.getOwnerId())
                .teamId(entity.getTeamId())
                .layoutConfig(entity.getLayoutConfig())
                .widgets(entity.getWidgets())
                .status(entity.getStatus())
                .isDefault(entity.getIsDefault())
                .sortOrder(entity.getSortOrder())
                .extension(entity.getExtension())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .build();

        // Extract menuMounted and menuCode from extension JSONB
        if (entity.getExtension() != null) {
            JsonNode menuMountedNode = entity.getExtension().get("menuMounted");
            if (menuMountedNode != null && menuMountedNode.isBoolean()) {
                dto.setMenuMounted(menuMountedNode.asBoolean());
            }
            JsonNode menuCodeNode = entity.getExtension().get("menuCode");
            if (menuCodeNode != null && menuCodeNode.isTextual()) {
                dto.setMenuCode(menuCodeNode.asText());
            }
        }
        if (dto.getMenuMounted() == null) {
            dto.setMenuMounted(false);
        }

        return dto;
    }
}
