package com.auraboot.framework.category.mapper;

import com.auraboot.framework.category.entity.Category;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 类目数据访问层
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * 根据类目编码和租户ID查询类目
     */
    @Select("SELECT * FROM ab_category WHERE code = #{code} AND  deleted_flag = false")
    Category findByCodeAndTenantId(@Param("code") String code, @Param("tenantId") Long tenantId);

    /**
     * 根据类目PID查询类目
     */
    @Select("SELECT * FROM ab_category WHERE pid = #{pid} AND deleted_flag = false")
    Category findByPid(@Param("pid") String pid);

    /**
     * 根据父类目ID查询子类目列表
     */
    @Select("SELECT * FROM ab_category WHERE parent_id = #{parentId} AND deleted_flag = false ORDER BY sort_order ASC, created_at ASC")
    List<Category> findByParentId(@Param("parentId") Long parentId);

    /**
     * 根据租户ID查询根类目列表(一级类目)
     */
    @Select("SELECT * FROM ab_category WHERE  parent_id IS NULL AND deleted_flag = false ORDER BY sort_order ASC, created_at ASC")
    List<Category> findRootCategoriesByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 根据租户ID和类型查询类目列表
     */
    @Select("SELECT * FROM ab_category WHERE  category_type = #{categoryType} AND deleted_flag = false ORDER BY sort_order ASC, created_at ASC")
    List<Category> findByTenantIdAndType(@Param("tenantId") Long tenantId, @Param("categoryType") String categoryType);

    /**
     * 根据租户ID查询所有激活状态的类目
     */
    @Select("SELECT * FROM ab_category WHERE  status = 'active' AND deleted_flag = false ORDER BY sort_order ASC, created_at ASC")
    List<Category> findAllActiveCategoriesByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 根据租户ID查询所有类目
     */
    @Select("SELECT * FROM ab_category WHERE  deleted_flag = false ORDER BY level ASC, sort_order ASC, created_at ASC")
    List<Category> findAllByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 分页查询类目列表（支持关键字和条件过滤）
     */
    @Select("""
        <script>
        SELECT * FROM ab_category
        WHERE deleted_flag = false
        <if test="keyword != null and keyword != ''">
            AND (name LIKE CONCAT('%', #{keyword}, '%')
                 OR code LIKE CONCAT('%', #{keyword}, '%')
                 OR description LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="categoryType != null and categoryType != ''">
            AND category_type = #{categoryType}
        </if>
        <if test="status != null and status != ''">
            AND status = #{status}
        </if>
        ORDER BY level ASC, sort_order ASC, created_at ASC
        </script>
        """)
    Page<Category> findCategoriesPage(
            Page<Category> page,
            @Param("keyword") String keyword,
            @Param("categoryType") String categoryType,
            @Param("status") String status);

    /**
     * 更新类目状态为启用
     */
    @Update("UPDATE ab_category SET status = 'active', updated_at = NOW() WHERE id = #{categoryId}")
    int enableCategoryById(@Param("categoryId") Long categoryId);

    /**
     * 更新类目状态为禁用
     */
    @Update("UPDATE ab_category SET status = 'inactive', updated_at = NOW() WHERE id = #{categoryId}")
    int disableCategoryById(@Param("categoryId") Long categoryId);

    /**
     * 软删除类目
     */
    @Update("UPDATE ab_category SET deleted_flag = TRUE, updated_at = NOW() WHERE id = #{categoryId}")
    int softDeleteById(@Param("categoryId") Long categoryId);

    /**
     * 更新类目排序
     */
    @Update("UPDATE ab_category SET sort_order = #{sortOrder}, updated_at = NOW() WHERE id = #{categoryId}")
    int updateSortOrderById(@Param("categoryId") Long categoryId, @Param("sortOrder") Integer sortOrder);
}
