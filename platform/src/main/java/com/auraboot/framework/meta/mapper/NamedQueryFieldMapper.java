package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.NamedQueryField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Named Query Field Mapper
 * Table: ab_named_query_field
 */
@Mapper
public interface NamedQueryFieldMapper extends BaseMapper<NamedQueryField> {

    /**
     * Find all fields by query code
     */
    @Select("SELECT * FROM ab_named_query_field WHERE query_code = #{queryCode} ORDER BY field_code")
    List<NamedQueryField> findByQueryCode(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode);

    /**
     * Find field by query code and field code
     */
    @Select("SELECT * FROM ab_named_query_field WHERE query_code = #{queryCode} AND field_code = #{fieldCode}")
    NamedQueryField findByQueryAndField(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode, @Param("fieldCode") String fieldCode);

    /**
     * Find all fields (no tenant filter since table has no tenant_id)
     */
    @Select("SELECT * FROM ab_named_query_field ORDER BY query_code, field_code")
    List<NamedQueryField> findByTenant();

    /**
     * Find fields by data type
     */
    @Select("SELECT * FROM ab_named_query_field WHERE data_type = #{dataType} ORDER BY query_code, field_code")
    List<NamedQueryField> findByDataType(@Param("dataType") String dataType);

    /**
     * Find sortable fields by query
     */
    @Select("SELECT * FROM ab_named_query_field WHERE query_code = #{queryCode} AND sortable = TRUE ORDER BY field_code")
    List<NamedQueryField> findSortableByQuery(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode);

    /**
     * Find searchable fields by query
     */
    @Select("SELECT * FROM ab_named_query_field WHERE query_code = #{queryCode} AND searchable = TRUE ORDER BY field_code")
    List<NamedQueryField> findSearchableByQuery(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode);

    /**
     * Find fields with dict association by query
     */
    @Select("SELECT * FROM ab_named_query_field WHERE query_code = #{queryCode} AND dict_code IS NOT NULL AND dict_code != '' ORDER BY field_code")
    List<NamedQueryField> findWithDictByQuery(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode);

    /**
     * Find fields by dict code
     */
    @Select("SELECT * FROM ab_named_query_field WHERE dict_code = #{dictCode} ORDER BY query_code, field_code")
    List<NamedQueryField> findByDict(@Param("dictCode") String dictCode);

    /**
     * Check if field code exists
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM ab_named_query_field WHERE query_code = #{queryCode} AND field_code = #{fieldCode}" +
            "<if test='excludeId != null'> AND id != #{excludeId}</if>" +
            "</script>")
    int countByQueryAndField(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode, @Param("fieldCode") String fieldCode, @Param("excludeId") Long excludeId);

    /**
     * Count fields by query
     */
    @Select("SELECT COUNT(*) FROM ab_named_query_field WHERE query_code = #{queryCode}")
    int countByQuery(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode);

    /**
     * Delete all fields by query code
     */
    @Delete("DELETE FROM ab_named_query_field WHERE query_code = #{queryCode}")
    int deleteByQuery(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode);

    /**
     * Delete fields by query code and source (PLUGIN or USER)
     * Used during reimport to only replace plugin-owned fields while preserving user-added fields
     */
    @Delete("DELETE FROM ab_named_query_field WHERE query_code = #{queryCode} AND source = #{source}")
    int deleteByQueryAndSource(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode, @Param("source") String source);

    /**
     * Delete fields by dict code
     */
    @Delete("DELETE FROM ab_named_query_field WHERE dict_code = #{dictCode}")
    int deleteByDict(@Param("dictCode") String dictCode);

    /**
     * Update source for all fields of a query
     * Used during plugin import to mark fields as PLUGIN-sourced
     */
    @Update("UPDATE ab_named_query_field SET source = #{source} WHERE query_code = #{queryCode}")
    int updateSourceByQuery(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode, @Param("source") String source);

    /**
     * Update sortable flag
     */
    @Update("UPDATE ab_named_query_field SET sortable = #{sortable} WHERE id = #{id}")
    int updateSortable(@Param("id") Long id, @Param("sortable") Boolean sortable);

    /**
     * Update searchable flag
     */
    @Update("UPDATE ab_named_query_field SET searchable = #{searchable} WHERE id = #{id}")
    int updateSearchable(@Param("id") Long id, @Param("searchable") Boolean searchable);

    /**
     * Update dict code
     */
    @Update("UPDATE ab_named_query_field SET dict_code = #{dictCode} WHERE id = #{id}")
    int updateDictCode(@Param("id") Long id, @Param("dictCode") String dictCode);

    /**
     * Update operators
     */
    @Update("UPDATE ab_named_query_field SET operators = #{operators} WHERE id = #{id}")
    int updateOperators(@Param("id") Long id, @Param("operators") String[] operators);

    /**
     * Batch update dict code
     */
    @Update("UPDATE ab_named_query_field SET dict_code = #{newDictCode} WHERE dict_code = #{oldDictCode}")
    int batchUpdateDictCode(@Param("oldDictCode") String oldDictCode, @Param("newDictCode") String newDictCode);

    /**
     * Find fields by column pattern
     */
    @Select("SELECT * FROM ab_named_query_field WHERE column_expr LIKE #{columnPattern} ORDER BY query_code, field_code")
    List<NamedQueryField> findByColumnPattern(@Param("columnPattern") String columnPattern);

    /**
     * Find fields by operator
     */
    @Select("SELECT * FROM ab_named_query_field WHERE query_code = #{queryCode} AND #{operator} = ANY(operators) ORDER BY field_code")
    List<NamedQueryField> findByOperator(@Param("tenantId") Long tenantId, @Param("queryCode") String queryCode, @Param("operator") String operator);

    /**
     * Delete all fields (for testing)
     */
    @Delete("DELETE FROM ab_named_query_field WHERE 1=1")
    int deleteAll();
}
