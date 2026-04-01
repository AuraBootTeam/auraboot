package com.auraboot.framework.meta.template.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.template.dto.*;
import com.auraboot.framework.meta.template.generator.DslGenerator;
import com.auraboot.framework.meta.template.service.TemplateGeneratorService;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Template Generator Service implementation
 * 
 * @author AuraBoot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateGeneratorServiceImpl implements TemplateGeneratorService {
    
    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;
    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final DslGenerator dslGenerator;
    private final MenuService menuService;
    private final PermissionService permissionService;
    private final RolePermissionService rolePermissionService;
    private final com.auraboot.framework.rbac.service.RoleService roleService;
    private final UserRoleService userRoleService;
    private final PageSchemaService pageSchemaService;
    private final ObjectMapper objectMapper;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TemplateGenerationResult generateCrudPages(String modelCode, CrudTemplateConfig config) {
        log.info("Starting CRUD template generation for model: {}", modelCode);
        
        // 1. Validate configuration
        validateConfiguration(modelCode, config);
        
        // 2. Load Model and Fields
        MetaModelDTO modelDTO = metaModelService.findByCode(modelCode);
        if (modelDTO == null) {
            throw new BusinessException(ResponseCode.BadParam, "Model not found: " + modelCode);
        }
        
        // 3. Load entity-level objects for DslGenerator
        Model modelEntity = metaModelMapper.findCurrentByCode(modelCode);
        if (modelEntity == null) {
            throw new BusinessException(ResponseCode.BadParam, "Model entity not found: " + modelCode);
        }

        List<ModelFieldBinding> bindings = metaModelService.getModelFieldBindings(modelDTO.getId(), true);
        if (bindings == null || bindings.isEmpty()) {
            throw new BusinessException(ResponseCode.BadParam, "No fields found for model: " + modelCode);
        }

        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .collect(Collectors.toList());
        List<Field> fieldEntities = metaFieldMapper.findByIds(fieldIds);

        // 4. Generate page DSL via DslGenerator and create pages
        List<GeneratedPage> generatedPages = new ArrayList<>();

        if (config.isGenerateList()) {
            PageSchema listSchema = dslGenerator.generateListPage(modelEntity, bindings, fieldEntities, config);
            PageSchemaDTO listPage = saveGeneratedPageSchema(listSchema, modelDTO);
            generatedPages.add(buildGeneratedPage(listPage, modelDTO.getCode(), "list"));
        }
        if (config.isGenerateForm()) {
            PageSchema formSchema = dslGenerator.generateFormPage(modelEntity, bindings, fieldEntities, config);
            PageSchemaDTO formPage = saveGeneratedPageSchema(formSchema, modelDTO);
            generatedPages.add(buildGeneratedPage(formPage, modelDTO.getCode(), "form"));
        }
        if (config.isGenerateDetail()) {
            PageSchema detailSchema = dslGenerator.generateDetailPage(modelEntity, bindings, fieldEntities, config);
            PageSchemaDTO detailPage = saveGeneratedPageSchema(detailSchema, modelDTO);
            generatedPages.add(buildGeneratedPage(detailPage, modelDTO.getCode(), "detail"));
        }
        
        // 4. Create menus
        List<GeneratedMenu> generatedMenus = createMenus(modelDTO, config);
        
        // 5. Create permissions
        List<GeneratedPermission> generatedPermissions = createPermissions(modelDTO, config);

        // 6. Assign permissions to roles
        if (config.getDefaultRoles() != null && !config.getDefaultRoles().isEmpty()) {
            assignPermissionsToRoles(generatedPermissions, config.getDefaultRoles());
        } else {
            // Auto-assign to current user's roles if no default roles specified
            autoAssignPermissionsToCurrentUserRoles(generatedPermissions);
        }
        
        // 7. Build generation result
        TemplateGenerationResult result = buildGenerationResult(modelDTO, generatedPages, generatedMenus, generatedPermissions);
        
        log.info("CRUD template generation completed for model: {}", modelCode);
        return result;
    }
    
    @Override
    public void validateConfiguration(String modelCode, CrudTemplateConfig config) {
        if (!StringUtils.hasText(modelCode)) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Model code cannot be empty");
        }
        
        if (config == null) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Configuration cannot be null");
        }
        
        if (!StringUtils.hasText(config.getMenuName())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Menu name cannot be empty");
        }
        
        if (!config.isGenerateList() && !config.isGenerateForm() && !config.isGenerateDetail()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "At least one page type must be selected");
        }
    }
    
    
    private PageSchemaDTO saveGeneratedPageSchema(PageSchema pageSchema, MetaModelDTO model) {
        PageSchemaCreateRequest request = new PageSchemaCreateRequest();

        // kind should be lowercase: list, form, detail
        String kind = pageSchema.getKind() != null ? pageSchema.getKind().toLowerCase() : "list";

        // Set pageKey as {modelCode}_{kind}, e.g., "device_list"
        String pageKey = model.getCode() + "_" + kind;
        request.setPageKey(pageKey);

        // Set modelCode for model association
        request.setModelCode(model.getCode());

        request.setName(pageSchema.getName());
        request.setTitle(pageSchema.getTitle());
        request.setKind(kind);
        request.setProfile(pageSchema.getProfile());
        request.setDescription(pageSchema.getDescription() != null
                ? pageSchema.getDescription()
                : "Auto-generated page for " + model.getDisplayName());
        request.setIsTemplate(false);

        // Convert V2 blocks JSON string to List
        if (pageSchema.getBlocks() != null) {
            try {
                List<Object> blocksList = objectMapper.readValue(
                        pageSchema.getBlocks(), new TypeReference<List<Object>>() {});
                request.setBlocks(blocksList);
            } catch (Exception e) {
                log.error("Failed to parse blocks JSON for page: {}", pageSchema.getName(), e);
                throw new BusinessException(ResponseCode.BadParam, "Invalid blocks generated");
            }
        }

        // Convert V2 layout JSON string to Map
        if (pageSchema.getLayout() != null) {
            try {
                Map<String, Object> layoutMap = objectMapper.readValue(
                        pageSchema.getLayout(), new TypeReference<Map<String, Object>>() {});
                request.setLayout(layoutMap);
            } catch (Exception e) {
                log.error("Failed to parse layout JSON for page: {}", pageSchema.getName(), e);
                throw new BusinessException(ResponseCode.BadParam, "Invalid layout generated");
            }
        }

        PageSchemaDTO page = pageSchemaService.create(request);

        // Auto-publish the page
        page = pageSchemaService.publish(page.getPid());

        return page;
    }
    
    private GeneratedPage buildGeneratedPage(PageSchemaDTO page, String modelCode, String kind) {
        return GeneratedPage.builder()
            .id(page.getId().toString())
            .pid(page.getPid())
            .pageName(page.getName())
            .kind(kind)
            .route("/dynamic/" + modelCode)
            .createdAt(page.getCreatedAt() != null ? page.getCreatedAt().toString() : Instant.now().toString())
            .build();
    }
    
    private List<GeneratedMenu> createMenus(MetaModelDTO model, CrudTemplateConfig config) {
        List<GeneratedMenu> menus = new ArrayList<>();
        
        Menu menu = new Menu();
        menu.setPid(UniqueIdGenerator.generate());
        menu.setName(config.getMenuName());
        menu.setPath("/dynamic/" + model.getCode());
        menu.setIcon(config.getMenuIcon());
        menu.setType(1); // MENU = 1
        menu.setOrderNo(100);
        menu.setStatus(MenuStatus.ACTIVE);
        menu.setVisible(true);
        menu.setCreatedAt(Instant.now());
        menu.setUpdatedAt(Instant.now());
        
        if (config.getMenuParentId() != null) {
            menu.setParentId(config.getMenuParentId());
        }
        
        // Save menu (MenuService will inject tenantId via MetaContext)
        Menu savedMenu = menuService.createMenu(menu);
        
        menus.add(GeneratedMenu.builder()
            .id(savedMenu.getId().toString())
            .pid(savedMenu.getPid())
            .menuName(savedMenu.getName())
            .menuPath(savedMenu.getPath())
            .icon(savedMenu.getIcon())
            .displayOrder(savedMenu.getOrderNo())
            .build());
        
        return menus;
    }
    
    private List<GeneratedPermission> createPermissions(MetaModelDTO model, CrudTemplateConfig config) {
        List<GeneratedPermission> permissions = new ArrayList<>();
        
        String resourceCode = model.getCode();
        String resourceType = "dynamic";
        
        // Basic CRUD permissions
        String[] actions = {"read", "create", "update", "delete"};
        for (String action : actions) {
            PermissionCreateRequest request = new PermissionCreateRequest();
            request.setCode("dynamic." + resourceCode + "." + action);
            request.setName(model.getDisplayName() + " - " + action);
            request.setResourceType(resourceType);
            request.setResourceCode(resourceCode);
            request.setAction(action);
            request.setDescription("Auto-generated permission for " + model.getDisplayName());
            
            PermissionDTO saved = permissionService.create(request);
            
            permissions.add(GeneratedPermission.builder()
                .id(saved.getId().toString())
                .pid(saved.getCode())
                .permissionCode(saved.getCode())
                .permissionName(saved.getName())
                .resourceType(saved.getResourceType())
                .resourceId(saved.getResourceCode())
                .build());
        }
        
        // Export permission
        if (config.isEnableExport()) {
            PermissionCreateRequest request = new PermissionCreateRequest();
            request.setCode("dynamic." + resourceCode + ".export");
            request.setName(model.getDisplayName() + " - export");
            request.setResourceType(resourceType);
            request.setResourceCode(resourceCode);
            request.setAction("export");
            request.setDescription("Auto-generated export permission");
            
            PermissionDTO saved = permissionService.create(request);
            
            permissions.add(GeneratedPermission.builder()
                .id(saved.getId().toString())
                .pid(saved.getCode())
                .permissionCode(saved.getCode())
                .permissionName(saved.getName())
                .resourceType(saved.getResourceType())
                .resourceId(saved.getResourceCode())
                .build());
        }
        
        // Import permission
        if (config.isEnableImport()) {
            PermissionCreateRequest request = new PermissionCreateRequest();
            request.setCode("dynamic." + resourceCode + ".import");
            request.setName(model.getDisplayName() + " - import");
            request.setResourceType(resourceType);
            request.setResourceCode(resourceCode);
            request.setAction("import");
            request.setDescription("Auto-generated import permission");
            
            PermissionDTO saved = permissionService.create(request);
            
            permissions.add(GeneratedPermission.builder()
                .id(saved.getId().toString())
                .pid(saved.getCode())
                .permissionCode(saved.getCode())
                .permissionName(saved.getName())
                .resourceType(saved.getResourceType())
                .resourceId(saved.getResourceCode())
                .build());
        }
        
        return permissions;
    }
    
    private void assignPermissionsToRoles(List<GeneratedPermission> permissions, List<String> rolePids) {
        List<Long> permissionIds = permissions.stream()
            .map(p -> Long.parseLong(p.getId()))
            .collect(Collectors.toList());

        for (String rolePid : rolePids) {
            try {
                // Look up role by PID
                com.auraboot.framework.rbac.entity.Role role = roleService.findByPid(rolePid);
                if (role == null) {
                    log.warn("Role not found by PID: {}", rolePid);
                    continue;
                }
                rolePermissionService.assignPermissionsToRole(role.getId(), permissionIds);
                log.info("Assigned {} permissions to role: pid={}, name={}", permissionIds.size(), rolePid, role.getName());
            } catch (Exception e) {
                log.warn("Failed to assign permissions to role: {}", rolePid, e);
            }
        }
    }

    /**
     * Auto-assign permissions to current user's roles when no default roles specified
     */
    private void autoAssignPermissionsToCurrentUserRoles(List<GeneratedPermission> permissions) {
        try {
            Long userId = MetaContext.getCurrentUserId();
            Long tenantId = MetaContext.getCurrentTenantId();

            if (userId == null || tenantId == null) {
                log.warn("Cannot auto-assign permissions: user or tenant context not available");
                return;
            }

            Long memberId = MetaContext.getCurrentMemberId();
            List<UserRole> userRoles = memberId != null ? userRoleService.findByMemberIdAndTenantId(memberId, tenantId) : List.of();
            if (userRoles.isEmpty()) {
                log.warn("User has no roles, cannot auto-assign permissions: userId={}", userId);
                return;
            }

            List<Long> permissionIds = permissions.stream()
                .map(p -> Long.parseLong(p.getId()))
                .collect(Collectors.toList());

            for (UserRole userRole : userRoles) {
                try {
                    rolePermissionService.assignPermissionsToRole(userRole.getRoleId(), permissionIds);
                    log.info("Auto-assigned {} permissions to user's role: userId={}, roleId={}",
                        permissionIds.size(), userId, userRole.getRoleId());
                } catch (Exception e) {
                    log.warn("Failed to auto-assign permissions to role: roleId={}", userRole.getRoleId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to auto-assign permissions to current user's roles", e);
        }
    }
    
    private TemplateGenerationResult buildGenerationResult(
        MetaModelDTO model,
        List<GeneratedPage> pages,
        List<GeneratedMenu> menus,
        List<GeneratedPermission> permissions
    ) {
        // Build access links
        AccessLinks accessLinks = AccessLinks.builder()
            .listPage("/dynamic/" + model.getCode())
            .formPage("/dynamic/" + model.getCode() + "/new")
            .detailPage("/dynamic/" + model.getCode() + "/:id")
            .build();
        
        // Build generated resources
        GeneratedResources generatedResources = GeneratedResources.builder()
            .pages(pages)
            .menus(menus)
            .permissions(permissions)
            .build();
        
        // Build final result
        return TemplateGenerationResult.builder()
            .modelCode(model.getCode())
            .generatedResources(generatedResources)
            .accessLinks(accessLinks)
            .generatedAt(Instant.now())
            .build();
    }
}
