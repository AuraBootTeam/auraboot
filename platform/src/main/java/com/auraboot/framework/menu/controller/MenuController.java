package com.auraboot.framework.menu.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单控制器
 * 提供菜单相关的API接口
 */
@Controller
@RequestMapping("/api/menu")
@Tag(name = "Menu", description = "User menu tree and menu management APIs")
public class MenuController {

    @Autowired
    private MenuService menuService;

    /**
     * 获取当前用户的菜单树
     * 用于前端动态渲染菜单
     */
    @GetMapping("/user")
    @ResponseBody
    @Operation(summary = "Get user menu tree", description = "Returns the sidebar menu tree for the current authenticated user based on their roles and permissions.")
    public ApiResponse<List<Menu>> getUserMenus(
            @CurrentUserId Long userId ) {

        List<Menu> menuTree = menuService.getUserMenuTree(userId, MetaContext.getCurrentTenantId());
        return ApiResponse.success(menuTree);
    }

    /**
     * 获取所有菜单树
     * 用于管理员配置菜单权限
     */
    @GetMapping("/all")
    @ResponseBody
    public ApiResponse<List<Menu>> getAllMenus() {
        List<Menu> menuTree = menuService.getAllMenuTree();
        return ApiResponse.success(menuTree);
    }

    /**
     * 根据类型获取菜单列表
     * @param type 菜单类型：0=目录，1=菜单，2=按钮
     */
    @GetMapping("/type/{type}")
    @ResponseBody
    public ApiResponse<List<Menu>> getMenusByType(@PathVariable("type") Integer type) {
        List<Menu> menus = menuService.getMenusByType(type);
        return ApiResponse.success(menus);
    }

    /**
     * 根据角色ID获取菜单列表
     * 用于角色权限配置
     */
    @GetMapping("/role/{roleId}")
    @ResponseBody
    public ApiResponse<List<Menu>> getMenusByRole(@PathVariable("roleId") Long roleId) {
        List<Menu> menus = menuService.getMenusByRoleId(roleId);
        return ApiResponse.success(menus);
    }

    /**
     * 检查用户菜单权限
     * 用于前端路由守卫
     */
    @GetMapping("/permission/check")
    @ResponseBody
    public ApiResponse<Boolean> checkMenuPermission(
            @CurrentUserId Long userId,
            @RequestParam("permissionCode") String permissionCode
            ) {
        boolean hasPermission = menuService.hasMenuPermission(userId, permissionCode, MetaContext.getCurrentTenantId());
        return ApiResponse.success(hasPermission);
    }


    /**
     * 获取用户按钮权限列表
     * 用于前端按钮级权限控制
     */
    @GetMapping("/buttons")
    @ResponseBody
    public ApiResponse<List<String>> getUserButtonPermissions(
            @CurrentUserId Long userId
            ) {
        List<String> buttonPermissions = menuService.getUserButtonPermissions(userId,  MetaContext.getCurrentTenantId());
        return ApiResponse.success(buttonPermissions);
    }

    /**
     * 创建菜单
     * 管理员功能
     */
    @PostMapping("/create")
    @ResponseBody
    @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Menu> createMenu(
            @RequestBody Menu menu,
            @CurrentUserId Long userId) {
        menu.setCreatedBy(userId);
        Menu createdMenu = menuService.createMenu(menu);
        return ApiResponse.success(createdMenu);
    }

    /**
     * 更新菜单
     * 管理员功能
     */
    @PutMapping("/update")
    @ResponseBody
    @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Menu> updateMenu(
            @RequestBody Menu menu,
            @CurrentUserId Long userId) {
        menu.setUpdatedBy(userId);
        Menu updatedMenu = menuService.updateMenu(menu);
        return ApiResponse.success(updatedMenu);
    }

    /**
     * 删除菜单
     * 管理员功能
     */
    @DeleteMapping("/{menuId}")
    @ResponseBody
    @RequirePermission(MetaPermission.MENU_MANAGE)
    public ApiResponse<Boolean> deleteMenu(@PathVariable("menuId") Long menuId) {
        boolean result = menuService.deleteMenu(menuId);
        return ApiResponse.success(result);
    }

    /**
     * 根据ID获取菜单详情
     */
    @GetMapping("/{menuId}")
    @ResponseBody
    public ApiResponse<Menu> getMenuById(@PathVariable("menuId") Long menuId) {
        Menu menu = menuService.getById(menuId);

        return ApiResponse.success(menu);
    }

    /**
     * 根据路径获取菜单配置
     * 用于动态路由解析
     */
    @GetMapping("/by-path")
    @ResponseBody
    public ApiResponse<Menu> getMenuByPath(@RequestParam("path") String path) {
        Menu menu = menuService.getByPath(MetaContext.getCurrentTenantId(), path);
        return ApiResponse.success(menu);
    }

    /**
     * Get parent menu options (directories) for mount dialog
     * Returns list of directory-type menus as {value, label} options
     */
    @GetMapping("/parent-options")
    @ResponseBody
    public ApiResponse<List<Map<String, Object>>> getParentOptions() {
        List<Menu> directories = menuService.getMenusByType(0);
        List<Map<String, Object>> options = new ArrayList<>();
        for (Menu dir : directories) {
            Map<String, Object> option = new HashMap<>();
            option.put("value", dir.getCode());
            option.put("label", dir.getName());
            options.add(option);
        }
        return ApiResponse.success(options);
    }
}