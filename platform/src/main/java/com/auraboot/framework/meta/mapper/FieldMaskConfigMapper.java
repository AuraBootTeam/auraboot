package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for FieldMaskConfig entity.
 *
 * @since 5.2.0
 */
@Mapper
public interface FieldMaskConfigMapper extends BaseMapper<FieldMaskConfig> {

    @Select("""
        SELECT * FROM ab_field_mask_config
        WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} AND enabled = TRUE
        ORDER BY field_code
        """)
    List<FieldMaskConfig> findByModelCode(@Param("tenantId") Long tenantId,
                                           @Param("modelCode") String modelCode);

    @Select("""
        SELECT * FROM ab_field_mask_config
        WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} AND field_code = #{fieldCode}
        """)
    FieldMaskConfig findByModelAndField(@Param("tenantId") Long tenantId,
                                         @Param("modelCode") String modelCode,
                                         @Param("fieldCode") String fieldCode);

    @Select("""
        SELECT * FROM ab_field_mask_config
        WHERE tenant_id = #{tenantId} AND model_code = #{modelCode}
        ORDER BY field_code
        """)
    List<FieldMaskConfig> findAllByModelCode(@Param("tenantId") Long tenantId,
                                              @Param("modelCode") String modelCode);

    @Delete("""
        DELETE FROM ab_field_mask_config
        WHERE tenant_id = #{tenantId} AND id = #{id}
        """)
    int deleteByTenantAndId(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
