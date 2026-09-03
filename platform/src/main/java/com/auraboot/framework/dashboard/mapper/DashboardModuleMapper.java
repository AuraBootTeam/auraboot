package com.auraboot.framework.dashboard.mapper;

import com.auraboot.framework.dashboard.entity.DashboardModule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard module (folder) mapper.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Mapper
public interface DashboardModuleMapper extends BaseMapper<DashboardModule> {

    @Select("""
        SELECT * FROM ab_dashboard_module
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    DashboardModule findByPid(@Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_dashboard_module
        WHERE tenant_id = #{tenantId} AND deleted_flag = false
        ORDER BY parent_id IS NOT NULL, parent_id, sort_order, id
        """)
    List<DashboardModule> findAllByTenant(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT COUNT(*) FROM ab_dashboard_module
        WHERE tenant_id = #{tenantId}
          AND parent_id = #{parentId}
          AND deleted_flag = false
        """)
    long countChildren(@Param("tenantId") Long tenantId, @Param("parentId") Long parentId);

    @Insert("""
        INSERT INTO ab_dashboard_module
            (pid, tenant_id, name, parent_id, sort_order,
             deleted_flag, created_at, updated_at, created_by, updated_by)
        VALUES (#{pid}, #{tenantId}, #{name}, #{parentId}, #{sortOrder},
                FALSE, #{createdAt}, #{updatedAt}, #{createdBy}, #{updatedBy})
        """)
    void insertModule(DashboardModule module);

    @Update("""
        UPDATE ab_dashboard_module
        SET name = #{name}, updated_at = #{updatedAt}, updated_by = #{updatedBy}
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    int rename(@Param("pid") String pid, @Param("name") String name,
               @Param("updatedAt") Instant updatedAt, @Param("updatedBy") String updatedBy);

    @Update("""
        UPDATE ab_dashboard_module
        SET parent_id = #{parentId}, updated_at = #{updatedAt}, updated_by = #{updatedBy}
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    int move(@Param("pid") String pid, @Param("parentId") Long parentId,
             @Param("updatedAt") Instant updatedAt, @Param("updatedBy") String updatedBy);

    @Update("""
        UPDATE ab_dashboard_module
        SET deleted_flag = true, updated_at = #{updatedAt}, updated_by = #{updatedBy}
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    int softDelete(@Param("pid") String pid,
                   @Param("updatedAt") Instant updatedAt, @Param("updatedBy") String updatedBy);
}
