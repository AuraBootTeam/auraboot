package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.FieldForkHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Field fork history mapper
 * Maps to table: ab_field_fork_history
 */
@Mapper
public interface FieldForkHistoryMapper extends BaseMapper<FieldForkHistory> {

    /**
     * Find fork history by original field ID
     * @param originalFieldId Original field ID
     * @return Fork history list
     */
    @Select("SELECT * FROM ab_field_fork_history WHERE original_field_id = #{originalFieldId} ORDER BY forked_at DESC")
    List<FieldForkHistory> findByOriginalFieldId(@Param("originalFieldId") Long originalFieldId);

    /**
     * Find fork history by forked field ID
     * @param forkedFieldId Forked field ID
     * @return Fork history record
     */
    @Select("SELECT * FROM ab_field_fork_history WHERE forked_field_id = #{forkedFieldId} LIMIT 1")
    FieldForkHistory findByForkedFieldId(@Param("forkedFieldId") Long forkedFieldId);

    /**
     * Find all forked variants of an original field
     * @param originalFieldId Original field ID
     * @return List of forked field IDs
     */
    @Select("SELECT forked_field_id FROM ab_field_fork_history WHERE original_field_id = #{originalFieldId} ORDER BY forked_at DESC")
    List<Long> findForkedFieldIds(@Param("originalFieldId") Long originalFieldId);

    /**
     * Find fork history by tenant
     * @param tenantId Tenant ID
     * @return Fork history list
     */
    @Select("SELECT * FROM ab_field_fork_history WHERE tenant_id = #{tenantId} ORDER BY forked_at DESC")
    List<FieldForkHistory> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Check if field is a forked variant
     * @param fieldId Field ID
     * @return true if field is forked
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_field_fork_history WHERE forked_field_id = #{fieldId}")
    boolean isForkedField(@Param("fieldId") Long fieldId);

    /**
     * Get original field ID for a forked field
     * @param forkedFieldId Forked field ID
     * @return Original field ID
     */
    @Select("SELECT original_field_id FROM ab_field_fork_history WHERE forked_field_id = #{forkedFieldId} LIMIT 1")
    Long getOriginalFieldId(@Param("forkedFieldId") Long forkedFieldId);

    /**
     * Find fork history by original field PID
     * Field Management Enhancement: PID-based query
     * 
     * @param originalFieldPid Original field PID
     * @param tenantId Tenant ID
     * @return Fork history list
     */
    @Select("SELECT h.* FROM ab_field_fork_history h " +
            "JOIN ab_meta_field f ON h.original_field_id = f.id " +
            "WHERE f.pid = #{originalFieldPid} AND h.tenant_id = #{tenantId} " +
            "ORDER BY h.forked_at DESC")
    List<FieldForkHistory> findByOriginalFieldPid(@Param("originalFieldPid") String originalFieldPid, @Param("tenantId") Long tenantId);

    /**
     * Find fork history by forked field PID
     * Field Management Enhancement: PID-based query
     * 
     * @param forkedFieldPid Forked field PID
     * @param tenantId Tenant ID
     * @return Fork history list
     */
    @Select("SELECT h.* FROM ab_field_fork_history h " +
            "JOIN ab_meta_field f ON h.forked_field_id = f.id " +
            "WHERE f.pid = #{forkedFieldPid} AND h.tenant_id = #{tenantId} " +
            "ORDER BY h.forked_at DESC")
    List<FieldForkHistory> findByForkedFieldPid(@Param("forkedFieldPid") String forkedFieldPid, @Param("tenantId") Long tenantId);

    /**
     * Get original field PID for a forked field
     * Field Management Enhancement: PID-based query
     * 
     * @param forkedFieldPid Forked field PID
     * @param tenantId Tenant ID
     * @return Original field PID
     */
    @Select("SELECT f_orig.pid FROM ab_field_fork_history h " +
            "JOIN ab_meta_field f_forked ON h.forked_field_id = f_forked.id " +
            "JOIN ab_meta_field f_orig ON h.original_field_id = f_orig.id " +
            "WHERE f_forked.pid = #{forkedFieldPid} AND h.tenant_id = #{tenantId} " +
            "LIMIT 1")
    String getOriginalFieldPid(@Param("forkedFieldPid") String forkedFieldPid, @Param("tenantId") Long tenantId);

    /**
     * Find forked field PIDs for an original field
     * Field Management Enhancement: PID-based query
     * 
     * @param originalFieldPid Original field PID
     * @param tenantId Tenant ID
     * @return List of forked field PIDs
     */
    @Select("SELECT f_forked.pid FROM ab_field_fork_history h " +
            "JOIN ab_meta_field f_orig ON h.original_field_id = f_orig.id " +
            "JOIN ab_meta_field f_forked ON h.forked_field_id = f_forked.id " +
            "WHERE f_orig.pid = #{originalFieldPid} AND h.tenant_id = #{tenantId} " +
            "ORDER BY h.forked_at DESC")
    List<String> findForkedFieldPids(@Param("originalFieldPid") String originalFieldPid, @Param("tenantId") Long tenantId);
}
