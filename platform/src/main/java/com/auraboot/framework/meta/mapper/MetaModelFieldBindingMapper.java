package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for ab_meta_model_field_binding table.
 */
@Mapper
public interface MetaModelFieldBindingMapper extends BaseMapper<ModelFieldBinding> {

    @Select("SELECT * FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND deleted_flag = false ORDER BY field_order ASC")
    List<ModelFieldBinding> findByModelId(@Param("modelId") Long modelId);

    @Select("SELECT * FROM ab_meta_model_field_binding WHERE field_id = #{fieldId} ORDER BY field_order ASC")
    List<ModelFieldBinding> findByFieldId(@Param("fieldId") Long fieldId);

    @Select("SELECT * FROM ab_meta_model_field_binding WHERE tenant_id = #{tenantId} ORDER BY model_id, field_order ASC")
    List<ModelFieldBinding> findByTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND field_id = #{fieldId} AND deleted_flag = false")
    ModelFieldBinding selectByModelAndField(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId);

    @Select("SELECT COUNT(*) FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND field_id = #{fieldId} AND deleted_flag = false")
    int countByModelAndField(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId);

    @Select("SELECT COUNT(*) FROM ab_meta_model_field_binding WHERE model_id = #{modelId}")
    int countFieldsByModelId(@Param("modelId") Long modelId);

    @Select("SELECT COALESCE(MAX(field_order), -1) FROM ab_meta_model_field_binding WHERE model_id = #{modelId}")
    Integer getMaxFieldOrder(@Param("modelId") Long modelId);

    @Delete("DELETE FROM ab_meta_model_field_binding WHERE model_id = #{modelId}")
    int deleteByModelId(@Param("modelId") Long modelId);

    @Delete("DELETE FROM ab_meta_model_field_binding WHERE field_id = #{fieldId}")
    int deleteByFieldId(@Param("fieldId") Long fieldId);

    @Delete("DELETE FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND field_id = #{fieldId}")
    int deleteByModelAndField(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId);

    @Update("UPDATE ab_meta_model_field_binding SET field_order = #{fieldOrder} WHERE id = #{id}")
    int updateFieldOrder(@Param("id") Long id, @Param("fieldOrder") Integer fieldOrder);

    @Update("UPDATE ab_meta_model_field_binding SET field_order = #{newOrder} WHERE model_id = #{modelId} AND field_id = #{fieldId}")
    int updateFieldOrderByModelAndField(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId, @Param("newOrder") Integer newOrder);

    @Update("UPDATE ab_meta_model_field_binding SET field_order = field_order + 1 WHERE model_id = #{modelId} AND field_order >= #{fromOrder}")
    int incrementFieldOrderFrom(@Param("modelId") Long modelId, @Param("fromOrder") Integer fromOrder);

    @Update("UPDATE ab_meta_model_field_binding SET field_order = field_order - 1 WHERE model_id = #{modelId} AND field_order > #{fromOrder}")
    int decrementFieldOrderFrom(@Param("modelId") Long modelId, @Param("fromOrder") Integer fromOrder);

    @Select("SELECT * FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND tenant_id = #{tenantId} ORDER BY field_order ASC")
    List<ModelFieldBinding> findByTenantAndModel(@Param("tenantId") Long tenantId, @Param("modelId") Long modelId);

    @Delete("DELETE FROM ab_meta_model_field_binding WHERE tenant_id = #{tenantId}")
    int deleteByTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND field_id = #{fieldId} AND tenant_id = #{tenantId}")
    ModelFieldBinding findByModelAndField(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId, @Param("tenantId") Long tenantId);

    @Select("SELECT f.pid FROM ab_meta_model_field_binding b " +
            "JOIN ab_meta_field f ON b.field_id = f.id " +
            "WHERE b.id = #{bindingId}")
    String getFieldPidByBinding(@Param("bindingId") Long bindingId);

    @Select("SELECT m.pid FROM ab_meta_model_field_binding b " +
            "JOIN ab_meta_model m ON b.model_id = m.id " +
            "WHERE b.id = #{bindingId}")
    String getModelPidByBinding(@Param("bindingId") Long bindingId);

    /**
     * Find the model code for a given field ID via binding + model join.
     * Returns only PUBLISHED models with current binding.
     */
    @Select("""
        SELECT m.code FROM ab_meta_model_field_binding b
        JOIN ab_meta_model m ON b.model_id = m.id
        WHERE b.field_id = #{fieldId}
          AND b.deleted_flag = false
          AND m.is_current = true
          AND m.deleted_flag = false
        LIMIT 1
        """)
    String findModelCodeByFieldId(@Param("fieldId") Long fieldId);

    // --- Methods for plugin resource management ---

    @Select("SELECT * FROM ab_meta_model_field_binding WHERE pid = #{pid}")
    ModelFieldBinding findByPid(@Param("pid") String pid);

    @Update("UPDATE ab_meta_model_field_binding SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDeleteByPid(@Param("pid") String pid);

    @Update("UPDATE ab_meta_model_field_binding SET " +
            "alias_code = COALESCE(#{aliasCode}, alias_code), " +
            "dict_override_code = COALESCE(#{dictOverrideCode}, dict_override_code), " +
            "ui_hint = COALESCE(#{uiHint}, ui_hint), " +
            "is_system_binding = COALESCE(#{isSystemBinding}, is_system_binding), " +
            "updated_at = NOW() " +
            "WHERE model_id = #{modelId} AND field_id = #{fieldId}")
    int updateExtraFields(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId,
                          @Param("aliasCode") String aliasCode, @Param("dictOverrideCode") String dictOverrideCode,
                          @Param("uiHint") String uiHint, @Param("isSystemBinding") Boolean isSystemBinding);

    @Select("SELECT pid FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND field_id = #{fieldId} AND deleted_flag = false")
    String getPidByModelAndField(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId);

    // ==================== Plugin Import Support ====================

    /**
     * Clear deleted_flag for a binding (used during plugin reimport).
     */
    @Update("UPDATE ab_meta_model_field_binding SET deleted_flag = FALSE WHERE model_id = #{modelId} AND field_id = #{fieldId}")
    int clearDeletedFlag(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId);

    /**
     * Count soft-deleted bindings for a model+field pair.
     */
    @Select("SELECT COUNT(*) FROM ab_meta_model_field_binding WHERE model_id = #{modelId} AND field_id = #{fieldId} AND deleted_flag = TRUE")
    int countSoftDeleted(@Param("modelId") Long modelId, @Param("fieldId") Long fieldId);

    /**
     * Resurrect a soft-deleted binding with updated field values.
     */
    @Update("""
        UPDATE ab_meta_model_field_binding SET
            deleted_flag = FALSE, field_order = #{fieldOrder},
            required = #{required}, visible = #{visible}, editable = #{editable},
            default_value = #{defaultValue}, updated_at = NOW()
        WHERE model_id = #{modelId} AND field_id = #{fieldId}
        """)
    int resurrectBinding(@Param("fieldOrder") Integer fieldOrder,
                         @Param("required") Boolean required,
                         @Param("visible") Boolean visible,
                         @Param("editable") Boolean editable,
                         @Param("defaultValue") String defaultValue,
                         @Param("modelId") Long modelId,
                         @Param("fieldId") Long fieldId);
}
