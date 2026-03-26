package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Field-Dictionary binding mapper
 * 
 * Provides database operations for field-dictionary bindings
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Mapper
public interface MetaFieldDictBindingMapper extends BaseMapper<FieldDictBinding> {

    /**
     * Find binding by field PID
     * 
     * @param fieldPid Field PID
     * @param tenantId Tenant ID
      
     * @return Binding entity, or null if not found
     */
    @Select("""
        SELECT * FROM ab_meta_field_dict_binding
        WHERE field_pid = #{fieldPid}
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        LIMIT 1
        """)
    FieldDictBinding findByFieldPid(
        @Param("fieldPid") String fieldPid,
        @Param("tenantId") Long tenantId
             
         
    );

    /**
     * Find binding by field ID
     * 
     * @param fieldId Field ID
     * @param tenantId Tenant ID
      
     * @return Binding entity, or null if not found
     */
    @Select("""
        SELECT * FROM ab_meta_field_dict_binding
        WHERE field_id = #{fieldId}
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        LIMIT 1
        """)
    FieldDictBinding findByFieldId(
        @Param("fieldId") Long fieldId,
        @Param("tenantId") Long tenantId
             
         
    );

    /**
     * Find bindings by field PIDs (batch query)
     * 
     * @param fieldPids List of field PIDs
     * @param tenantId Tenant ID
      
     * @return List of binding entities
     */
    @Select("""
        <script>
        SELECT * FROM ab_meta_field_dict_binding
        WHERE field_pid IN
        <foreach collection="fieldPids" item="pid" open="(" separator="," close=")">
        #{pid}
        </foreach>
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        </script>
        """)
    List<FieldDictBinding> findByFieldPids(
        @Param("fieldPids") List<String> fieldPids,
        @Param("tenantId") Long tenantId
             
         
    );

    /**
     * Find all bindings for a dictionary
     * 
     * @param dictCode Dictionary code
     * @param tenantId Tenant ID
      
     * @return List of binding entities
     */
    @Select("""
        SELECT * FROM ab_meta_field_dict_binding
        WHERE dict_code = #{dictCode}
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        ORDER BY created_at DESC
        """)
    List<FieldDictBinding> findByDictCode(
        @Param("dictCode") String dictCode,
        @Param("tenantId") Long tenantId
             
         
    );

    /**
     * Check if binding exists for field
     * 
     * @param fieldPid Field PID
     * @param tenantId Tenant ID
      
     * @return Count (0 or 1)
     */
    @Select("""
        SELECT COUNT(*) FROM ab_meta_field_dict_binding
        WHERE field_pid = #{fieldPid}
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        """)
    int countByFieldPid(
        @Param("fieldPid") String fieldPid,
        @Param("tenantId") Long tenantId
             
         
    );

    /**
     * Delete binding by field PID (soft delete)
     * 
     * @param fieldPid Field PID
     * @param tenantId Tenant ID
      
     * @return Number of rows affected
     */
    @Update("""
        UPDATE ab_meta_field_dict_binding
        SET deleted_flag = TRUE,
            updated_at = CURRENT_TIMESTAMP
        WHERE field_pid = #{fieldPid}
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        """)
    int deleteByFieldPid(
        @Param("fieldPid") String fieldPid,
        @Param("tenantId") Long tenantId
             
         
    );
}
