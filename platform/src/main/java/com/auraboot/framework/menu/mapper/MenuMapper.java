package com.auraboot.framework.menu.mapper;

import com.auraboot.framework.menu.entity.Menu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Menu Mapper
 */
@Mapper
public interface MenuMapper extends BaseMapper<Menu> {

    /**
     * 查询可见的目录和菜单(type=0或1)
     */
    @Select("""
        SELECT id, tenant_id, pid, created_at, updated_at, parent_id, code, name, path,
               component, icon, type, permission_code, visible, order_no, i18n_key,
               redirect, extension, page_key, page_pid, status, deleted_flag, created_by, updated_by
        FROM ab_menu
        WHERE type IN (0, 1)
          AND (visible IS NULL OR visible = true)
          AND status = 'active'
          AND deleted_flag = false
        ORDER BY order_no ASC
        """)
    List<Menu> findVisibleDirectoriesAndMenus();

    /**
     * 查询所有按钮类型的菜单(type=2)
     */
    @Select("""
        SELECT id, tenant_id, pid, created_at, updated_at, parent_id, code, name, path,
               component, icon, type, permission_code, visible, order_no, i18n_key,
               redirect, extension, page_key, page_pid, status, deleted_flag, created_by, updated_by
        FROM ab_menu
        WHERE type = 2
          AND status = 'active'
          AND deleted_flag = false
        ORDER BY order_no ASC
        """)
    List<Menu> findAllButtons();

    /**
     * 查询所有激活的菜单
     */
    @Select("""
        SELECT id, tenant_id, pid, created_at, updated_at, parent_id, code, name, path,
               component, icon, type, permission_code, visible, order_no, i18n_key,
               redirect, extension, page_key, page_pid, status, deleted_flag, created_by, updated_by
        FROM ab_menu
        WHERE status = 'active'
          AND deleted_flag = false
        ORDER BY order_no ASC
        """)
    List<Menu> findAllActiveMenus();

    /**
     * 查询指定类型的菜单
     */
    @Select("""
        SELECT id, tenant_id, pid, created_at, updated_at, parent_id, code, name, path,
               component, icon, type, permission_code, visible, order_no, i18n_key,
               redirect, extension, page_key, page_pid, status, deleted_flag, created_by, updated_by
        FROM ab_menu
        WHERE type = #{type}
          AND status = 'active'
          AND deleted_flag = false
        ORDER BY order_no ASC
        """)
    List<Menu> findByType(@Param("type") Integer type);

    /**
     * 通过permission_code查询菜单
     */
    @Select("""
        SELECT id, tenant_id, pid, created_at, updated_at, parent_id, code, name, path,
               component, icon, type, permission_code, visible, order_no, i18n_key,
               redirect, extension, page_key, page_pid, status, deleted_flag, created_by, updated_by
        FROM ab_menu
        WHERE permission_code = #{permissionCode}
          AND status = 'active'
          AND deleted_flag = false
        LIMIT 1
        """)
    Menu findByPermissionCode(@Param("permissionCode") String permissionCode);

    /**
     * 检查菜单名称是否存在
     * @param tenantId 租户ID
     * @param name 菜单名称（对应 code）
     * @return 是否存在
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_menu WHERE tenant_id = #{tenantId} AND name = #{name} AND deleted_flag = false")
    boolean existsByName(@Param("tenantId") Long tenantId, @Param("name") String name);

    /**
     * Check if a menu with the given code exists.
     * @param tenantId Tenant ID
     * @param code Menu code
     * @return true if exists
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_menu WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = false")
    boolean existsByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    /**
     * Find menu ID by code.
     * @param tenantId Tenant ID
     * @param code Menu code
     * @return Menu ID or null
     */
    @Select("SELECT id FROM ab_menu WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = false LIMIT 1")
    Long findIdByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    /**
     * Find menu PID by code.
     * @param tenantId Tenant ID
     * @param code Menu code
     * @return Menu PID or null
     */
    @Select("SELECT pid FROM ab_menu WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = false LIMIT 1")
    String findPidByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    /**
     * 根据名称查询菜单ID
     * @param tenantId 租户ID
     * @param name 菜单名称（对应 code）
     * @return 菜单ID
     */
    @Select("SELECT id FROM ab_menu WHERE tenant_id = #{tenantId} AND name = #{name} AND deleted_flag = false")
    Long findIdByName(@Param("tenantId") Long tenantId, @Param("name") String name);

    /**
     * 根据名称查询菜单PID
     * @param tenantId 租户ID
     * @param name 菜单名称（对应 code）
     * @return 菜单PID
     */
    @Select("SELECT pid FROM ab_menu WHERE tenant_id = #{tenantId} AND name = #{name} AND deleted_flag = false")
    String findPidByName(@Param("tenantId") Long tenantId, @Param("name") String name);

    /**
     * 根据路径查询菜单
     * @param tenantId 租户ID
     * @param path 菜单路径
     * @return 菜单
     */
    @Select("""
        SELECT id, tenant_id, pid, created_at, updated_at, parent_id, code, name, path,
               component, icon, type, permission_code, visible, order_no, i18n_key,
               redirect, extension, page_key, page_pid, status, deleted_flag, created_by, updated_by
        FROM ab_menu
        WHERE tenant_id = #{tenantId}
          AND path = #{path}
          AND status = 'active'
          AND deleted_flag = false
        LIMIT 1
        """)
    Menu findByPath(@Param("tenantId") Long tenantId, @Param("path") String path);

    /**
     * Auto-link menus to pages by matching page_key.
     * Updates page_pid for menus that have page_key set but no page_pid.
     *
     * @param tenantId Tenant ID
     * @param pluginPid Plugin PID
     * @return Number of menus updated
     */
    @Update("""
        UPDATE ab_menu m
        SET page_pid = p.pid,
            updated_at = NOW()
        FROM ab_page_schema p
        WHERE m.tenant_id = #{tenantId}
          AND m.plugin_pid = #{pluginPid}
          AND m.page_key IS NOT NULL
          AND m.page_pid IS NULL
          AND m.deleted_flag = false
          AND p.tenant_id = m.tenant_id
          AND p.plugin_pid = m.plugin_pid
          AND p.is_current = true
          AND p.deleted_flag = false
          AND m.page_key = p.page_key
        """)
    int linkMenusToPagesByPageKey(@Param("tenantId") Long tenantId, @Param("pluginPid") String pluginPid);

    /**
     * Update page_pid for a specific menu.
     *
     * @param menuId Menu ID
     * @param pagePid Page PID
     * @return Number of rows updated
     */
    @Update("UPDATE ab_menu SET page_pid = #{pagePid}, updated_at = NOW() WHERE id = #{menuId}")
    int updatePagePid(@Param("menuId") Long menuId, @Param("pagePid") String pagePid);

    // ==================== Plugin Import Support ====================

    /**
     * Update menu fields for plugin import.
     */
    @Update("""
        UPDATE ab_menu SET
            code = #{code}, name = #{name}, path = #{path}, component = #{component},
            icon = #{icon}, type = #{type}, parent_id = #{parentId},
            permission_code = #{permissionCode}, visible = #{visible}, order_no = #{orderNo},
            i18n_key = #{i18nKey}, redirect = #{redirect}, page_key = #{pageKey},
            page_pid = #{pagePid}, extension = #{extension}::jsonb,
            plugin_pid = #{pluginPid}, status = 'active', updated_at = NOW()
        WHERE tenant_id = #{tenantId} AND code = #{currentCode} AND deleted_flag = FALSE
        """)
    int updateForPluginImport(@Param("code") String code,
                              @Param("name") String name,
                              @Param("path") String path,
                              @Param("component") String component,
                              @Param("icon") String icon,
                              @Param("type") Integer type,
                              @Param("parentId") Long parentId,
                              @Param("permissionCode") String permissionCode,
                              @Param("visible") Boolean visible,
                              @Param("orderNo") Integer orderNo,
                              @Param("i18nKey") String i18nKey,
                              @Param("redirect") String redirect,
                              @Param("pageKey") String pageKey,
                              @Param("pagePid") String pagePid,
                              @Param("extension") String extension,
                              @Param("pluginPid") String pluginPid,
                              @Param("tenantId") Long tenantId,
                              @Param("currentCode") String currentCode);

    /**
     * Find menu ID by pid.
     */
    @Select("SELECT id FROM ab_menu WHERE tenant_id = #{tenantId} AND pid = #{pid} AND deleted_flag = FALSE")
    Long findIdByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    /**
     * Update plugin_pid, page_key and extension for a menu.
     */
    @Update("UPDATE ab_menu SET plugin_pid = #{pluginPid}, page_key = #{pageKey}, extension = #{extension}::jsonb WHERE id = #{id}")
    int updatePluginFields(@Param("pluginPid") String pluginPid,
                           @Param("pageKey") String pageKey,
                           @Param("extension") String extension,
                           @Param("id") Long id);

    /**
     * Soft delete menu by id (fallback delete for plugin uninstall).
     */
    @Update("UPDATE ab_menu SET deleted_flag = TRUE, updated_at = NOW() WHERE id = #{id}")
    int softDeleteById(@Param("id") Long id);
}