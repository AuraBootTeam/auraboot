package com.auraboot.framework.menu.service.impl;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.SubjectPermissionService;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements MenuService {

    @Resource
    private SubjectPermissionService subjectPermissionService;

    @Resource
    private com.auraboot.framework.permission.service.AutoPermissionAssignmentService autoPermissionAssignmentService;

    @Resource
    private RolePermissionMapper rolePermissionMapper;

    @Resource
    private PermissionMapper permissionMapper;
    
    @Override
    public List<Menu> getUserMenuTree(Long userId, Long tenantId) {
        if (null == tenantId) {
            log.warn("Tenant ID is null, returning empty menu tree for userId={}", userId);
            return Collections.emptyList();
        }

        // 1. 查询所有可见的菜单(目录和菜单)
        List<Menu> allMenus = baseMapper.findVisibleDirectoriesAndMenus();
        
        if (allMenus.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 2. 批量评估菜单可见性
        List<Long> menuIds = allMenus.stream()
            .map(Menu::getId)
            .collect(Collectors.toList());
        
        Map<Long, Boolean> visibilityMap = subjectPermissionService
            .batchEvaluateVisibility("menu", menuIds, userId);
        
        // 3. 过滤出可见的菜单
        List<Menu> visibleMenus = allMenus.stream()
            .filter(menu -> visibilityMap.getOrDefault(menu.getId(), true))  // 默认可见
            .collect(Collectors.toList());
        
        // 4. 构建树结构
        return buildMenuTree(visibleMenus);
    }
    
    @Override
    public List<Menu> getMenusByRoleId(Long roleId) {
        if (roleId == null) {
            log.warn("Role ID is null, returning empty menu list");
            return Collections.emptyList();
        }

        // Get all active menus
        List<Menu> allMenus = baseMapper.findAllActiveMenus();

        if (allMenus.isEmpty()) {
            return Collections.emptyList();
        }

        // Get all permission IDs for this role
        Set<Long> rolePermissionIds = rolePermissionMapper.findPermissionIdsByRole(roleId);

        if (rolePermissionIds.isEmpty()) {
            // Role has no permissions, return only menus without permission requirements
            return allMenus.stream()
                .filter(menu -> menu.getPermissionCode() == null || menu.getPermissionCode().isEmpty())
                .collect(Collectors.toList());
        }

        // Get permission codes for the role's permission IDs (batch query)
        List<com.auraboot.framework.permission.entity.Permission> permissions =
            permissionMapper.findByIds(new ArrayList<>(rolePermissionIds));
        Set<String> rolePermissionCodes = permissions.stream()
            .map(p -> p.getCode())
            .collect(Collectors.toSet());

        // Filter menus based on role permissions
        return allMenus.stream()
            .filter(menu -> {
                // If menu has no permission requirement, it's visible to all roles
                if (menu.getPermissionCode() == null || menu.getPermissionCode().isEmpty()) {
                    return true;
                }
                // Check if the role has the required permission for this menu
                return rolePermissionCodes.contains(menu.getPermissionCode());
            })
            .collect(Collectors.toList());
    }
    
    @Override
    public List<Menu> getAllMenuTree() {
        List<Menu> menuList = baseMapper.findAllActiveMenus();
        return buildMenuTree(menuList);
    }
    
    @Override
    public List<Menu> getMenusByType(Integer type) {
        return baseMapper.findByType(type);
    }
    
    @Override
    public List<Menu> buildMenuTree(List<Menu> menuList) {
        Map<Long, Menu> menuMap = menuList.stream()
            .collect(Collectors.toMap(Menu::getId, menu -> menu));
        
        List<Menu> rootMenus = new ArrayList<>();
        
        for (Menu menu : menuList) {
            if (menu.getParentId() == null || menu.getParentId() == 0) {
                rootMenus.add(menu);
            } else {
                Menu parent = menuMap.get(menu.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(menu);
                }
            }
        }
        
        return rootMenus;
    }
    
    @Override
    public boolean hasMenuPermission(Long userId, String permissionCode, Long tenantId) {
        // 1. 通过permissionCode查询Menu
        Menu menu = baseMapper.findByPermissionCode(permissionCode);

        if (menu == null) {
            return false;
        }

        // 2. 评估菜单可见性
        return subjectPermissionService.evaluateVisibility("menu", menu.getId(), userId);
    }


    
    @Override
    @Transactional
    public Menu createMenu(Menu menu) {
        menu.setCreatedAt(Instant.now());
        menu.setUpdatedAt(Instant.now());
        menu.setDeletedFlag(false);
        
        save(menu);
        
        // Auto-assign permissions for menu
        // Skip if menu already has permissionCode (pre-defined permission from bootstrap template)
        // Only auto-assign for dynamically created menus without existing permission
        try {
            if (menu.getPermissionCode() != null && !menu.getPermissionCode().isEmpty()) {
                log.debug("Menu already has permissionCode, skipping auto-permission assignment: menuId={}, permissionCode={}",
                    menu.getId(), menu.getPermissionCode());
            } else if (menu.getPath() != null && !menu.getPath().isEmpty()) {
                // Convert path to valid resourceCode: /meta/models -> meta_models
                String resourceCode = convertPathToResourceCode(menu.getPath());
                if (resourceCode != null) {
                    autoPermissionAssignmentService.autoAssignPermissions(resourceCode, null);
                    log.info("Auto-assigned permissions for menu: menuId={}, resourceCode={}",
                        menu.getId(), resourceCode);
                } else {
                    log.warn("Could not convert path to valid resourceCode: menuId={}, path={}",
                        menu.getId(), menu.getPath());
                }
            } else {
                log.warn("Menu has no permissionCode or path, skipping auto-permission assignment: menuId={}",
                    menu.getId());
            }
        } catch (Exception e) {
            log.error("Failed to auto-assign permissions for menu: menuId={}, error={}",
                menu.getId(), e.getMessage(), e);
            // Don't throw exception - permission assignment failure should not block menu creation
        }
        
        return menu;
    }
    
    @Override
    @Transactional
    public Menu updateMenu(Menu menu) {
        menu.setUpdatedAt(Instant.now());
        updateById(menu);
        return menu;
    }
    
    @Override
    @Transactional
    public boolean deleteMenu(Long menuId) {
        Menu menu = getById(menuId);
        if (menu == null) {
            return false;
        }

        // Check for child menus — prevent orphan nodes
        List<Menu> children = list(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Menu>()
            .eq("parent_id", menuId));
        if (children != null && !children.isEmpty()) {
            throw new IllegalStateException(
                "Cannot delete menu with " + children.size() + " child menus. Delete children first.");
        }

        return getBaseMapper().deleteById(menu.getId()) > 0;
    }
    
    @Override
    public List<String> getUserButtonPermissions(Long userId, Long tenantId) {
        // 1. 查询所有按钮类型的菜单
        List<Menu> allButtons = baseMapper.findAllButtons();

        if (allButtons.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 批量评估按钮可见性
        List<Long> buttonIds = allButtons.stream()
            .map(Menu::getId)
            .collect(Collectors.toList());

        Map<Long, Boolean> visibilityMap = subjectPermissionService
            .batchEvaluateVisibility("menu", buttonIds, userId);

        // 3. 返回可见按钮的permission_code
        return allButtons.stream()
            .filter(button -> visibilityMap.getOrDefault(button.getId(), true))  // 默认可见
            .map(Menu::getPermissionCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Convert menu path to valid resourceCode for permission auto-assignment.
     *
     * <p>Conversion rules:
     * <ul>
     *   <li>Remove leading slash: /meta/models -> meta/models</li>
     *   <li>Replace slashes with underscores: meta/models -> meta_models</li>
     *   <li>Replace hyphens with underscores: data-permissions -> data_permissions</li>
     *   <li>Convert to lowercase: META_MODELS -> meta_models</li>
     *   <li>Remove invalid characters: keep only [a-z0-9_]</li>
     *   <li>Ensure starts with letter: 123test -> null (invalid)</li>
     * </ul>
     *
     * <p>Examples:
     * <ul>
     *   <li>/meta/models -> meta_models</li>
     *   <li>/data-permissions -> data_permissions</li>
     *   <li>/enterprise/members -> enterprise_members</li>
     *   <li>/system -> system</li>
     * </ul>
     *
     * @param path Menu path (e.g., "/meta/models")
     * @return Valid resourceCode or null if cannot convert
     */
    private String convertPathToResourceCode(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // Remove leading slash
        String code = path.startsWith("/") ? path.substring(1) : path;

        // Replace slashes and hyphens with underscores
        code = code.replace("/", "_").replace("-", "_");

        // Convert to lowercase
        code = code.toLowerCase();

        // Remove invalid characters (keep only a-z, 0-9, _)
        code = code.replaceAll("[^a-z0-9_]", "");

        // Remove consecutive underscores
        code = code.replaceAll("_+", "_");

        // Remove leading/trailing underscores
        code = code.replaceAll("^_+|_+$", "");

        // Validate: must start with letter and not be empty
        if (code.isEmpty() || !Character.isLetter(code.charAt(0))) {
            return null;
        }

        return code;
    }

    @Override
    public Menu getByPath(Long tenantId, String path) {
        return baseMapper.findByPath(tenantId, path);
    }
}