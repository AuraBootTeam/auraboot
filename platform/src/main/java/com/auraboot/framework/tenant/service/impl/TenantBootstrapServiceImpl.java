package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.enums.RolePermissionTemplate;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.tenant.dto.bootstrap.TenantBootstrapTemplate;
import com.auraboot.framework.tenant.dto.bootstrap.RoleTemplate;
import com.auraboot.framework.tenant.dto.bootstrap.MenuTemplate;
import com.auraboot.framework.tenant.dto.bootstrap.PermissionTemplate;
import com.auraboot.framework.tenant.exception.TemplateNotFoundException;
import com.auraboot.framework.tenant.exception.TemplateParseException;
import com.auraboot.framework.tenant.exception.TemplateValidationException;
import com.auraboot.framework.tenant.exception.BootstrapException;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 租户初始化服务实现
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantBootstrapServiceImpl implements TenantBootstrapService {
    
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;
    private final MenuService menuService;
    private final RolePermissionService rolePermissionService;
    private final AutoPermissionAssignmentService autoPermissionAssignmentService;
    private final UserRoleService userRoleService;
    private final com.auraboot.framework.tenant.service.TenantMemberService tenantMemberService;
    private final com.auraboot.framework.permission.service.SystemPermissionInitializer systemPermissionInitializer;
    private final PermissionMapper permissionMapper;
    private final I18nResourceService i18nResourceService;
    private final DynamicDataMapper dynamicDataMapper;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BootstrapResult bootstrapTenant(Long tenantId, Long userId) {
        long startTime = System.currentTimeMillis();
        
        // 设置MetaContext
        com.auraboot.framework.application.tenant.MetaContext previousContext = setupMetaContext(tenantId, userId);
        
        try {
            log.info("开始初始化租户: tenantId={}, userId={}", tenantId, userId);
            
            // 1. 加载模板
            TenantBootstrapTemplate template = loadTemplate("default-bootstrap");
            log.info("模板加载成功: {}", template.getName());
            
            // 2. 验证模板
            validateTemplate(template);
            log.info("模板验证通过");
            
            // 3. 创建角色
            List<Role> roles = createRoles(tenantId, template.getRoles(), userId);
            log.info("角色创建完成: count={}", roles.size());
            
            // 4. 创建系统级Permission
            List<com.auraboot.framework.permission.entity.Permission> systemPermissions = 
                systemPermissionInitializer.initializeSystemPermissions(tenantId);
            log.info("系统级Permission创建完成: count={}", systemPermissions.size());
            
            // 5. 分配系统级Permission给角色
            Map<String, Role> roleMap = new HashMap<>();
            for (Role role : roles) {
                roleMap.put(role.getCode(), role);
            }
            int permissionsAssigned = assignSystemPermissionsToRoles(roleMap, systemPermissions);
            log.info("系统级Permission分配完成: count={}", permissionsAssigned);
            
            // 6. 创建模板Permission (用于菜单与模板绑定)
            List<Permission> templatePermissions = createTemplatePermissions(
                tenantId, template.getPermissions(), userId
            );
            log.info("模板Permission创建完成: count={}", templatePermissions.size());

            // 7. 根据 rolePermissionBindings 分配模板Permission给角色
            int templatePermissionsAssigned = assignTemplatePermissionsToRoles(
                roleMap, templatePermissions, template.getRolePermissionBindings()
            );
            log.info("模板Permission分配完成: count={}", templatePermissionsAssigned);

            // 8. 创建菜单
            List<Menu> menus = createMenus(tenantId, template.getMenus(), userId);
            log.info("菜单创建完成: count={}", menus.size());

            // 9. 分配用户角色（将创建者分配为TENANT_ADMIN）
            assignUserRole(userId, "tenant_admin", tenantId, roleMap);
            log.info("用户角色分配完成: userId={}, roleCode=tenant_admin", userId);

            // 10. 创建 AuraBot Agent
            createAuraBotAgent(tenantId);
            log.info("AuraBot Agent 创建完成: tenantId={}", tenantId);

            long duration = System.currentTimeMillis() - startTime;
            log.info("租户初始化完成: tenantId={}, 耗时={}ms", tenantId, duration);
            
            return BootstrapResult.success(roles.size(), menus.size(), permissionsAssigned, duration);
            
        } catch (Exception e) {
            log.error("租户初始化失败: tenantId={}, userId={}", tenantId, userId, e);
            throw new BootstrapException("租户初始化失败: " + e.getMessage(), e);
        } finally {
            // 清理并恢复MetaContext
            cleanupMetaContext(previousContext);
        }
    }
    
    @Override
    public TenantBootstrapTemplate loadTemplate(String templateName) {
        try {
            log.info("开始加载模板: {}", templateName);
            
            // 构建模板文件路径
            String templatePath = "classpath:tenant-templates/" + templateName + ".json";
            
            // 加载资源
            Resource resource = resourceLoader.getResource(templatePath);
            
            // 检查资源是否存在
            if (!resource.exists()) {
                throw new TemplateNotFoundException(
                    "模板文件不存在: " + templatePath
                );
            }
            
            // 读取文件内容
            String json = new String(
                resource.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
            );
            
            log.debug("模板文件内容长度: {} 字节", json.length());
            
            // 解析JSON为Java对象
            TenantBootstrapTemplate template = objectMapper.readValue(
                json,
                TenantBootstrapTemplate.class
            );
            
            log.info("模板加载成功: name={}, version={}, roles={}, menus={}",
                template.getName(),
                template.getVersion(),
                template.getRoles() != null ? template.getRoles().size() : 0,
                template.getMenus() != null ? template.getMenus().size() : 0
            );
            
            return template;
            
        } catch (IOException e) {
            log.error("模板解析失败: {}", templateName, e);
            throw new TemplateParseException(
                "模板解析失败: " + templateName,
                e
            );
        }
    }
    
    @Override
    public void validateTemplate(TenantBootstrapTemplate template) {
        log.info("开始验证模板: {}", template.getName());
        
        // 验证模板基本信息
        if (template.getName() == null || template.getName().trim().isEmpty()) {
            throw new TemplateValidationException("模板名称不能为空");
        }
        
        if (template.getVersion() == null || template.getVersion().trim().isEmpty()) {
            throw new TemplateValidationException("模板版本不能为空");
        }
        
        // 验证角色定义
        if (template.getRoles() == null || template.getRoles().isEmpty()) {
            throw new TemplateValidationException("模板必须包含至少一个角色定义");
        }
        
        for (RoleTemplate role : template.getRoles()) {
            validateRoleTemplate(role);
        }
        
        // 验证菜单定义
        if (template.getMenus() == null || template.getMenus().isEmpty()) {
            throw new TemplateValidationException("模板必须包含至少一个菜单定义");
        }
        
        for (MenuTemplate menu : template.getMenus()) {
            validateMenuTemplate(menu);
        }
        
        // Validate menu permission codes are defined in permission templates
        Set<String> permissionCodeSet = new HashSet<>();
        if (template.getPermissions() != null) {
            for (PermissionTemplate permissionTemplate : template.getPermissions()) {
                if (permissionTemplate.getCode() == null || permissionTemplate.getCode().trim().isEmpty()) {
                    throw new TemplateValidationException("权限编码不能为空");
                }
                permissionCodeSet.add(permissionTemplate.getCode().trim());
            }
        }
        
        for (MenuTemplate menu : template.getMenus()) {
            String permissionCode = menu.getPermissionCode();
            if (permissionCode != null && !permissionCode.trim().isEmpty() && !permissionCodeSet.contains(permissionCode.trim())) {
                throw new TemplateValidationException(
                    "菜单权限未定义: menuCode=" + menu.getCode() + ", permissionCode=" + permissionCode
                );
            }
        }
        
        // Validate role-permission bindings
        if (template.getRolePermissionBindings() == null || template.getRolePermissionBindings().isEmpty()) {
            throw new TemplateValidationException("Template must contain at least one role-permission binding");
        }
        
        log.info("模板验证通过: {}", template.getName());
    }
    
    /**
     * Create template permissions before menu creation.
     */
    private List<Permission> createTemplatePermissions(Long tenantId,
                                                      List<PermissionTemplate> permissionTemplates,
                                                      Long userId) {
        if (permissionTemplates == null || permissionTemplates.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<Permission> created = new ArrayList<>();
        
        for (PermissionTemplate template : permissionTemplates) {
            String code = template.getCode() != null ? template.getCode().trim() : null;
            if (code == null || code.isEmpty()) {
                throw new TemplateValidationException("权限编码不能为空");
            }
            
            Permission existing = permissionMapper.findByCode(code);
            if (existing != null) {
                log.debug("模板Permission已存在,跳过: code={}, id={}", code, existing.getId());
                continue;
            }
            
            Permission permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setTenantId(tenantId);
            permission.setCode(code);
            permission.setName(template.getName());
            permission.setDescription(template.getDescription());
            permission.setResourceType(template.getResourceType());
            permission.setResourceCode(template.getResource());
            permission.setAction(template.getAction());
            permission.setSource("system");
            permission.setSourceRef(template.getModule());
            permission.setStatus(StatusConstants.ACTIVE);
            permission.setDeletedFlag(false);
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permission.setCreatedBy(userId);
            permission.setUpdatedBy(userId);
            
            permissionMapper.insert(permission);
            created.add(permission);
            
            log.debug("创建模板Permission: code={}, id={}, pid={}", 
                permission.getCode(), permission.getId(), permission.getPid());
        }
        
        return created;
    }
    
    /**
     * 验证角色模板
     */
    private void validateRoleTemplate(RoleTemplate role) {
        if (role.getCode() == null || role.getCode().trim().isEmpty()) {
            throw new TemplateValidationException("角色编码不能为空");
        }
        
        if (role.getName() == null || role.getName().trim().isEmpty()) {
            throw new TemplateValidationException(
                "角色名称不能为空: code=" + role.getCode()
            );
        }
        
        if (role.getPriority() == null || role.getPriority() < 0) {
            throw new TemplateValidationException(
                "角色优先级必须为非负整数: code=" + role.getCode()
            );
        }
    }
    
    /**
     * 验证菜单模板
     */
    private void validateMenuTemplate(MenuTemplate menu) {
        if (menu.getCode() == null || menu.getCode().trim().isEmpty()) {
            throw new TemplateValidationException("菜单编码不能为空");
        }
        
        if (menu.getName() == null || menu.getName().trim().isEmpty()) {
            throw new TemplateValidationException(
                "菜单名称不能为空: code=" + menu.getCode()
            );
        }
        
        // Only leaf menus (type=1) require a path; directory menus (type=0) don't
        if (menu.getType() != null && menu.getType() == 1
                && (menu.getPath() == null || menu.getPath().trim().isEmpty())) {
            throw new TemplateValidationException(
                "菜单路径不能为空: code=" + menu.getCode()
            );
        }
        
        if (menu.getType() == null || (menu.getType() != 0 && menu.getType() != 1)) {
            throw new TemplateValidationException(
                "菜单类型必须为0(目录)或1(菜单): code=" + menu.getCode()
            );
        }
    }
    
    /**
     * 创建角色
     * 
     * @param tenantId 租户ID
     * @param roleTemplates 角色模板列表
     * @param userId 创建者ID
     * @return 创建的角色列表
     */
    private List<Role> createRoles(Long tenantId, List<RoleTemplate> roleTemplates, Long userId) {
        log.info("开始创建角色: tenantId={}, count={}", tenantId, roleTemplates.size());
        
        List<Role> roles = new ArrayList<>();
        Instant now = Instant.now();
        
        for (RoleTemplate template : roleTemplates) {
            Role role = new Role();
            
            // 设置基本信息
            role.setPid(UniqueIdGenerator.generate());
            role.setTenantId(tenantId);
            role.setCode(template.getCode());
            role.setName(template.getName());
            role.setDescription(template.getDescription());
            
            // 设置角色类型和作用域
            role.setType(template.getType() != null ? template.getType() : "tenant");
            role.setScopeType(template.getScopeType() != null ? template.getScopeType() : "tenant");
            role.setScopeContent("{\"tenantId\":" + tenantId + "}");
            
            // 设置优先级
            role.setPriority(template.getPriority() != null ? template.getPriority() : 100);
            
            // 设置状态和标记
            role.setStatus(StatusConstants.ACTIVE);
            role.setIsDefault(template.getIsDefault() != null ? template.getIsDefault() : false);
            role.setIsSystem(false);
            role.setDeletedFlag(false);
            
            // 设置审计字段
            role.setCreatedAt(now);
            role.setUpdatedAt(now);
            role.setCreatedBy(userId);
            role.setUpdatedBy(userId);
            
            // 保存角色
            roleService.createRole(role);
            roles.add(role);
            
            log.info("角色创建成功: code={}, name={}, priority={}, isDeletable={}",
                role.getCode(),
                role.getName(),
                role.getPriority(),
                template.getIsDeletable()
            );
        }
        
        return roles;
    }
    
    /**
     * 创建菜单
     * 
     * @param tenantId 租户ID
     * @param menuTemplates 菜单模板列表
     * @param userId 创建者ID
     * @return 创建的菜单列表
     */
    private List<Menu> createMenus(Long tenantId, List<MenuTemplate> menuTemplates, Long userId) {
        log.info("开始创建菜单: tenantId={}, count={}", tenantId, menuTemplates.size());
        
        List<Menu> menus = new ArrayList<>();
        Map<String, Menu> menuCodeMap = new HashMap<>();
        Instant now = Instant.now();
        
        // 第一遍：创建所有菜单（不设置parentId）
        for (MenuTemplate template : menuTemplates) {
            Menu menu = new Menu();
            
            // 设置基本信息
            menu.setPid(UniqueIdGenerator.generate());
            menu.setTenantId(tenantId);
            menu.setCode(template.getCode());
            menu.setName(template.getName());
            menu.setPath(template.getPath());
            menu.setComponent(template.getComponent());
            menu.setIcon(template.getIcon());
            
            // 设置菜单类型和权限
            menu.setType(template.getType());
            menu.setPermissionCode(template.getPermissionCode());
            
            // 设置显示属性
            menu.setVisible(template.getVisible() != null ? template.getVisible() : true);
            menu.setOrderNo(template.getOrderNo() != null ? template.getOrderNo() : 0);
            
            // 设置扩展属性 (e.g., platforms visibility)
            if (template.getExtension() != null && !template.getExtension().isEmpty()) {
                ExtensionBean ext = new ExtensionBean();
                template.getExtension().forEach(ext::setDynamicProperty);
                menu.setExtension(ext);
            }

            // 设置状态
            menu.setStatus(MenuStatus.ACTIVE);
            menu.setDeletedFlag(false);
            
            // 设置审计字段
            menu.setCreatedAt(now);
            menu.setUpdatedAt(now);
            menu.setCreatedBy(userId);
            menu.setUpdatedBy(userId);
            
            // 暂时不设置parentId，先保存菜单
            menu.setParentId(null);
            
            // 保存菜单
            menuService.createMenu(menu);
            menus.add(menu);
            menuCodeMap.put(template.getCode(), menu);
            
            log.debug("菜单创建成功: code={}, name={}, path={}",
                template.getCode(),
                menu.getName(),
                menu.getPath()
            );
        }
        
        // 第二遍：设置父子关系
        for (MenuTemplate template : menuTemplates) {
            if (template.getParentCode() != null && !template.getParentCode().trim().isEmpty()) {
                Menu menu = menuCodeMap.get(template.getCode());
                Menu parentMenu = menuCodeMap.get(template.getParentCode());
                
                if (parentMenu == null) {
                    log.warn("父菜单不存在: parentCode={}, menuCode={}",
                        template.getParentCode(),
                        template.getCode()
                    );
                    continue;
                }
                
                // 更新parentId
                menu.setParentId(parentMenu.getId());
                menuService.updateById(menu);
                
                log.debug("菜单父子关系设置成功: child={}, parent={}",
                    template.getCode(),
                    template.getParentCode()
                );
            }
        }
        
        // Generate menu i18n records from localized names
        generateMenuI18n(menuTemplates);

        log.info("菜单创建完成: count={}", menus.size());
        return menus;
    }

    /**
     * Auto-generate i18n records for bootstrap menus from name:zh-CN / name:en fields.
     */
    private void generateMenuI18n(List<MenuTemplate> templates) {
        List<I18nResource> resources = new ArrayList<>();
        for (MenuTemplate t : templates) {
            if (t.getCode() == null || t.getCode().isBlank()) continue;
            String key = "menu." + t.getCode();

            Map<String, String> allNames = t.getAllLocalizedNames();
            // Fall back to the generic name field for zh-CN if no explicit zh-CN localization
            if (t.getName() != null && !t.getName().isBlank()) {
                allNames.putIfAbsent("zh-CN", t.getName());
            }

            for (Map.Entry<String, String> entry : allNames.entrySet()) {
                I18nResource res = new I18nResource();
                res.setI18nKey(key);
                res.setLang(entry.getKey());
                res.setValue(entry.getValue());
                res.setSource(I18nResource.SOURCE_SYSTEM);
                res.setRefType("menu");
                res.setStatus(I18nResource.STATUS_APPROVED);
                resources.add(res);
            }
        }
        if (!resources.isEmpty()) {
            int count = i18nResourceService.batchUpsert(resources);
            log.info("Auto-generated {} bootstrap menu i18n records", count);
        }
    }
    
    /**
     * 分配系统级Permission给角色
     * 
     * <p>分配策略:
     * <ul>
     *   <li>TENANT_ADMIN: 所有系统级Permission</li>
     *   <li>DEVELOPER: 所有系统级Permission</li>
     *   <li>VIEWER: 只分配read Permission</li>
     * </ul>
     * 
     * @param roleMap 角色映射（code -> Role）
     * @param systemPermissions 系统级Permission列表
     * @return 分配的Permission总数
     */
    private int assignSystemPermissionsToRoles(
            Map<String, Role> roleMap,
            List<com.auraboot.framework.permission.entity.Permission> systemPermissions) {

        log.info("开始分配系统级Permission给角色: roleCount={}, permissionCount={}",
            roleMap.size(), systemPermissions.size());

        int totalAssigned = 0;

        for (Map.Entry<String, Role> entry : roleMap.entrySet()) {
            Role role = entry.getValue();
            RolePermissionTemplate template = RolePermissionTemplate.findByRoleCode(entry.getKey());
            if (template == null) {
                log.debug("No template for role: {}", entry.getKey());
                continue;
            }

            List<Long> filteredIds = systemPermissions.stream()
                .filter(template::shouldAssign)
                .map(com.auraboot.framework.permission.entity.Permission::getId)
                .toList();

            if (!filteredIds.isEmpty()) {
                rolePermissionService.assignPermissionsToRole(role.getId(), filteredIds);
                totalAssigned += filteredIds.size();
                log.info("{}角色Permission分配完成: count={}", entry.getKey(), filteredIds.size());
            }
        }

        log.info("系统级Permission分配完成: totalAssigned={}", totalAssigned);

        return totalAssigned;
    }
    
    /**
     * Assign template permissions to roles based on rolePermissionBindings.
     *
     * <p>Processing rules:
     * <ul>
     *   <li>"*" in permissionCodes: assign ALL template permissions to the role</li>
     *   <li>Specific codes: assign only matching template permissions</li>
     * </ul>
     *
     * <p>Note: assignPermissionsToRole is additive (checks duplicates), safe to call
     * after system permissions have already been assigned.
     */
    private int assignTemplatePermissionsToRoles(
            Map<String, Role> roleMap,
            List<Permission> templatePermissions,
            List<com.auraboot.framework.tenant.dto.bootstrap.RolePermissionBinding> bindings) {

        if (bindings == null || bindings.isEmpty() || templatePermissions.isEmpty()) {
            return 0;
        }

        // Build permission code -> id map for specific code lookups
        Map<String, Long> codeToId = new HashMap<>();
        for (Permission p : templatePermissions) {
            codeToId.put(p.getCode(), p.getId());
        }

        List<Long> allTemplateIds = templatePermissions.stream()
            .map(Permission::getId)
            .toList();

        int totalAssigned = 0;

        for (com.auraboot.framework.tenant.dto.bootstrap.RolePermissionBinding binding : bindings) {
            Role role = roleMap.get(binding.getRoleCode());
            if (role == null) {
                log.warn("Role not found for binding: {}", binding.getRoleCode());
                continue;
            }

            List<Long> permissionIds;
            if (binding.getPermissionCodes().contains("*")) {
                // Wildcard: assign all template permissions
                permissionIds = allTemplateIds;
            } else {
                // Specific permission codes
                permissionIds = binding.getPermissionCodes().stream()
                    .map(codeToId::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            }

            if (!permissionIds.isEmpty()) {
                rolePermissionService.assignPermissionsToRole(role.getId(), permissionIds);
                totalAssigned += permissionIds.size();
                log.info("Assigned {} template permissions to role {}",
                    permissionIds.size(), binding.getRoleCode());
            }
        }

        return totalAssigned;
    }

    /**
     * 分配用户角色
     *
     * @param userId 用户ID
     * @param roleCode 角色编码
     * @param tenantId 租户ID
     * @param roleMap 角色映射（code -> Role）
     */
    private void assignUserRole(Long userId, String roleCode, Long tenantId, Map<String, Role> roleMap) {
        log.info("开始分配用户角色: userId={}, roleCode={}, tenantId={}", userId, roleCode, tenantId);
        
        // 获取角色
        Role role = roleMap.get(roleCode);
        if (role == null) {
            throw new BootstrapException(
                String.format("角色不存在，无法分配用户角色: roleCode=%s", roleCode)
            );
        }
        
        // 验证TENANT_ADMIN唯一性（检查是否已有其他用户拥有此角色）
        // 注意：这里使用TENANT_ADMIN而不是TENANT_OWNER，因为模板中定义的是TENANT_ADMIN
        if ("tenant_admin".equals(roleCode)) {
            // 这里可以添加唯一性验证逻辑
            // 但由于是新租户初始化，通常不需要验证
            log.debug("分配tenant_admin角色: userId={}", userId);
        }
        
        // Resolve memberId from userId + tenantId
        com.auraboot.framework.tenant.dao.entity.TenantMember member =
                tenantMemberService.findByTenantIdAndUserId(tenantId, userId);
        if (member == null) {
            throw new BootstrapException(
                String.format("TenantMember not found for userId=%d, tenantId=%d", userId, tenantId)
            );
        }

        // Create user-role association with member_id
        Instant now = Instant.now();
        UserRole userRole = new UserRole();
        userRole.setPid(UniqueIdGenerator.generate());
        userRole.setMemberId(member.getId());
        userRole.setRoleId(role.getId());
        userRole.setTenantId(tenantId);
        userRole.setStatus(StatusConstants.ACTIVE);
        userRole.setCreatedAt(now);
        userRole.setUpdatedAt(now);
        userRole.setCreatedBy(userId);
        userRole.setUpdatedBy(userId);

        userRoleService.save(userRole);
        
        log.info("用户角色分配成功: userId={}, roleCode={}, roleId={}",
            userId,
            roleCode,
            role.getId()
        );
    }
    
    /**
     * Seed the AuraBot agent for a newly bootstrapped tenant.
     *
     * <p>This method is idempotent: if an AuraBot agent already exists for the tenant
     * (e.g. from an earlier bootstrap attempt or a schema.sql INSERT), the call is a no-op.
     *
     * @param tenantId the tenant to seed
     */
    private void createAuraBotAgent(Long tenantId) {
        // Check for existing AuraBot agent (idempotent guard)
        String checkSql = "SELECT COUNT(*) AS cnt FROM ab_agent_definition " +
            "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
            "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
            checkSql, Map.of("tenantId", tenantId, "agentCode", "aurabot"));
        if (!rows.isEmpty() && ((Number) rows.get(0).get("cnt")).intValue() > 0) {
            log.debug("AuraBot agent already exists for tenant {}, skipping creation", tenantId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("pid", "aurabot_" + tenantId);
        agent.put("tenant_id", tenantId);
        agent.put("agent_code", "aurabot");
        agent.put("name", "AuraBot");
        agent.put("description",
            "Platform-native AI assistant with full access to all models, commands, and tools.");
        agent.put("agent_type", "reactive");
        agent.put("model", null); // resolved at runtime from the first enabled LLM provider
        agent.put("system_prompt",
            "You are AuraBot, the intelligent assistant for this platform. " +
            "Help users accomplish their tasks efficiently.");
        agent.put("max_tools", 20);
        agent.put("max_concurrent_runs", 3);
        agent.put("execution_timeout_seconds", 300);
        agent.put("status", "active");
        agent.put("deleted_flag", false);
        agent.put("created_at", now);
        agent.put("updated_at", now);

        dynamicDataMapper.insert("ab_agent_definition", agent);
        log.info("AuraBot agent created for tenant {}", tenantId);
    }

    /**
     * 设置租户上下文
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @return 原有的MetaContext（如果存在）
     */
    private com.auraboot.framework.application.tenant.MetaContext setupMetaContext(Long tenantId, Long userId) {
        // 保存原有上下文
        com.auraboot.framework.application.tenant.MetaContext previousContext = 
            com.auraboot.framework.application.tenant.MetaContext.exists() 
                ? com.auraboot.framework.application.tenant.MetaContext.get() 
                : null;
        
        // 设置新上下文
        com.auraboot.framework.application.tenant.MetaContext.setContext(
            tenantId,

            userId,
            null,       // userPid暂时为null
            null        // username暂时为null
        );
        
        log.info("设置租户上下文: tenantId={}, userId={}",
            tenantId, userId);
        
        return previousContext;
    }
    
    /**
     * 清理并恢复租户上下文
     * 
     * @param previousContext 之前保存的MetaContext
     */
    private void cleanupMetaContext(com.auraboot.framework.application.tenant.MetaContext previousContext) {
        // 清理当前上下文
        com.auraboot.framework.application.tenant.MetaContext.clear();
        log.debug("清理租户上下文");
        
        // 恢复原有上下文
        if (previousContext != null) {
            com.auraboot.framework.application.tenant.MetaContext.setContext(
                previousContext.getTenantId(),

                previousContext.getUserId(),
                previousContext.getUserPid(),
                previousContext.getUsername()
            );
            log.debug("恢复原有租户上下文: tenantId={}", previousContext.getTenantId());
        }
    }
}
