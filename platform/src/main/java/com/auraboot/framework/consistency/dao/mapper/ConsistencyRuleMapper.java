package com.auraboot.framework.consistency.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.consistency.entity.ConsistencyRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mapper for consistency rules.
 */
@Mapper
public interface ConsistencyRuleMapper extends BaseMapper<ConsistencyRule> {

    /**
     * Find all enabled rules by source model
     */
    @Select("SELECT * FROM ab_consistency_rule " +
            "WHERE source_model = #{sourceModel} " +
            "AND enabled = true " +
            "AND (deleted_flag = false OR deleted_flag IS NULL) " +
            "AND tenant_id = #{tenantId}")
    List<ConsistencyRule> selectEnabledBySourceModel(
            @Param("sourceModel") String sourceModel,
            @Param("tenantId") Long tenantId);

    /**
     * Find rule by code within a tenant
     */
    @Select("SELECT * FROM ab_consistency_rule " +
            "WHERE code = #{code} " +
            "AND tenant_id = #{tenantId} " +
            "AND (deleted_flag = false OR deleted_flag IS NULL)")
    ConsistencyRule selectByCode(@Param("code") String code, @Param("tenantId") Long tenantId);

    /**
     * Dynamic aggregate query on source table.
     * The table name, column, link column are injected dynamically.
     * Since this is a dynamic table name, we use @Select with string concatenation.
     * Note: caller must sanitize all inputs to prevent SQL injection.
     */
    @Select("SELECT COALESCE(${aggFunc}(CAST((data->>'${sourceField}') AS NUMERIC)), 0) " +
            "FROM ${sourceTable} " +
            "WHERE (data->>'${linkField}') = #{linkValue} " +
            "AND tenant_id = #{tenantId} " +
            "AND (deleted_status = 0 OR deleted_status IS NULL)")
    BigDecimal aggregateSourceField(
            @Param("aggFunc") String aggFunc,
            @Param("sourceField") String sourceField,
            @Param("sourceTable") String sourceTable,
            @Param("linkField") String linkField,
            @Param("linkValue") String linkValue,
            @Param("tenantId") Long tenantId);

    /**
     * Get target field value from target table by row_id.
     */
    @Select("SELECT COALESCE(CAST((data->>'${targetField}') AS NUMERIC), 0) " +
            "FROM ${targetTable} " +
            "WHERE row_id::text = #{rowId} " +
            "AND tenant_id = #{tenantId} " +
            "AND (deleted_status = 0 OR deleted_status IS NULL)")
    BigDecimal getTargetFieldValue(
            @Param("targetField") String targetField,
            @Param("targetTable") String targetTable,
            @Param("rowId") String rowId,
            @Param("tenantId") Long tenantId);
}
