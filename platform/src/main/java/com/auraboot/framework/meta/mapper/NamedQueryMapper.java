package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.NamedQuery;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * NamedQuery Mapper interface.
 * Table: ab_named_query
 *
 * NOTE: Methods returning NamedQuery entities use `default` + QueryWrapper
 * so that MyBatis Plus autoResultMap properly handles JSONB type handlers
 * (base_where, default_order, policy). Raw @Select bypasses autoResultMap.
 */
@Mapper
public interface NamedQueryMapper extends BaseMapper<NamedQuery> {

    default NamedQuery findByPid(String pid) {
        return selectOne(new QueryWrapper<NamedQuery>().eq("pid", pid));
    }

    default NamedQuery findByCode(String code) {
        return selectOne(new QueryWrapper<NamedQuery>().eq("code", code));
    }

    default List<NamedQuery> findEnabledByTenant() {
        return selectList(new QueryWrapper<NamedQuery>()
                .in("status", "draft", "testing", "published")
                .orderByDesc("created_at"));
    }

    default List<NamedQuery> findAllByTenant() {
        return selectList(new QueryWrapper<NamedQuery>().orderByDesc("created_at"));
    }

    default List<NamedQuery> findByStatus(String status) {
        return selectList(new QueryWrapper<NamedQuery>()
                .eq("status", status)
                .orderByDesc("created_at"));
    }

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM ab_named_query
        WHERE code = #{code}
        <if test="excludeId != null">
          AND id != #{excludeId}
        </if>
        </script>
        """)
    int countByCode(@Param("code") String code, @Param("excludeId") Long excludeId);

    default List<NamedQuery> findByTitlePattern(String titlePattern) {
        return selectList(new QueryWrapper<NamedQuery>()
                .like("title", titlePattern)
                .in("status", "draft", "testing", "published")
                .orderByDesc("created_at"));
    }

    default List<NamedQuery> findByDescriptionPattern(String descPattern) {
        return selectList(new QueryWrapper<NamedQuery>()
                .like("description", descPattern)
                .in("status", "draft", "testing", "published")
                .orderByDesc("created_at"));
    }

    default List<NamedQuery> findWithBaseWhere() {
        return selectList(new QueryWrapper<NamedQuery>()
                .isNotNull("base_where")
                .apply("jsonb_array_length(base_where) > 0")
                .in("status", "draft", "testing", "published")
                .orderByDesc("created_at"));
    }

    default List<NamedQuery> findWithDefaultOrder() {
        return selectList(new QueryWrapper<NamedQuery>()
                .isNotNull("default_order")
                .apply("jsonb_typeof(default_order) = 'object'")
                .in("status", "draft", "testing", "published")
                .orderByDesc("created_at"));
    }

    @Update("UPDATE ab_named_query SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE ab_named_query SET status = #{status} WHERE pid = #{pid}")
    int updateStatusByPid(@Param("pid") String pid, @Param("status") String status);

    @Update("UPDATE ab_named_query SET status = #{newStatus} WHERE status = #{oldStatus}")
    int batchUpdateStatus(@Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus);

    @Update("UPDATE ab_named_query SET from_sql = #{fromSql} WHERE id = #{id}")
    int updateFromSql(@Param("id") Long id, @Param("fromSql") String fromSql);

    @Update("UPDATE ab_named_query SET base_where = #{baseWhere}::jsonb WHERE id = #{id}")
    int updateBaseWhere(@Param("id") Long id, @Param("baseWhere") String baseWhere);

    @Update("UPDATE ab_named_query SET default_order = #{defaultOrder}::jsonb WHERE id = #{id}")
    int updateDefaultOrder(@Param("id") Long id, @Param("defaultOrder") String defaultOrder);

    @Delete("DELETE FROM ab_named_query")
    int deleteByTenant();
}
