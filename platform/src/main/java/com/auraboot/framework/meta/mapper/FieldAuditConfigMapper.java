package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.FieldAuditConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_field_audit_config table.
 * Provides standard CRUD via BaseMapper and custom lookups
 * for loading audit configuration per model.
 *
 * @since 6.2.0
 */
@Mapper
public interface FieldAuditConfigMapper extends BaseMapper<FieldAuditConfig> {

    /**
     * Get all enabled audit configs for a model (used for cache loading).
     */
    @Select("SELECT * FROM ab_field_audit_config " +
            "WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} AND enabled = TRUE")
    List<FieldAuditConfig> getEnabledByModel(@Param("tenantId") Long tenantId,
                                              @Param("modelCode") String modelCode);

    /**
     * Get all audit configs for a model (including disabled).
     */
    @Select("SELECT * FROM ab_field_audit_config " +
            "WHERE tenant_id = #{tenantId} AND model_code = #{modelCode}")
    List<FieldAuditConfig> getAllByModel(@Param("tenantId") Long tenantId,
                                         @Param("modelCode") String modelCode);

    /**
     * Get a specific config entry.
     */
    @Select("SELECT * FROM ab_field_audit_config " +
            "WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} AND field_code = #{fieldCode}")
    FieldAuditConfig getByModelAndField(@Param("tenantId") Long tenantId,
                                         @Param("modelCode") String modelCode,
                                         @Param("fieldCode") String fieldCode);
}
