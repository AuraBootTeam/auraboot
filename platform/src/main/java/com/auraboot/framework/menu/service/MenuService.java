package com.auraboot.framework.menu.service;

import com.auraboot.framework.menu.entity.Menu;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 菜单服务接口
 */
public interface MenuService extends IService<Menu> {
    
    /**
     * 根据用户ID获取菜单树
     */
    List<Menu> getUserMenuTree(Long userId, Long tenantId);
    
    /**
     * 根据角色ID获取菜单列表
     */
    List<Menu> getMenusByRoleId(Long roleId);
    
    /**
     * 获取所有菜单树
     */
    List<Menu> getAllMenuTree();
    
    /**
     * 根据类型获取菜单列表
     */
    List<Menu> getMenusByType(Integer type);
    
    /**
     * 构建菜单树
     */
    List<Menu> buildMenuTree(List<Menu> menuList);
    
    /**
     * Check if user has menu permission
     */
    boolean hasMenuPermission(Long userId, String permissionCode, Long tenantId);


    /**
     * 创建菜单
     */
    Menu createMenu(Menu menu);
    
    /**
     * 更新菜单
     */
    Menu updateMenu(Menu menu);
    
    /**
     * 删除菜单
     */
    boolean deleteMenu(Long menuId);
    
    /**
     * 获取用户按钮权限
     */
    List<String> getUserButtonPermissions(Long userId, Long tenantId);

    /**
     * 根据路径查询菜单
     * @param tenantId 租户ID
     * @param path 菜单路径
     * @return 菜单
     */
    Menu getByPath(Long tenantId, String path);
}