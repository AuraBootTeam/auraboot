package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.permission.entity.SubjectPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * SubjectPermission Mapper Interface (V4)
 * 
 * Provides data access operations for Subject-Permission declarations.
 * 
 * Key Features:
 * - Unified Subject abstraction (MENU, PAGE, BUTTON, QUERY, WORKFLOW)
 * - Logic group support (AND/OR)
 * - is_negated support (UI visibility only)
 * - Tenant isolation (auto-injected)
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Mapper
public interface SubjectPermissionMapper extends BaseMapper<SubjectPermission> {
    
    /**
     * Find all permission declarations for a subject
     * 
     * @param subjectType Subject type (MENU, PAGE, BUTTON, etc.)
     * @param subjectId Subject ID
     * @return List of subject permission declarations
     */
    @Select("""
        SELECT * FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
          AND deleted_flag = false
        ORDER BY logic_group, logic_order
        """)
    List<SubjectPermission> findBySubject(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId
    );
    
    /**
     * Find permission declarations by subject code
     * 
     * @param subjectType Subject type
     * @param subjectCode Subject code
     * @return List of subject permission declarations
     */
    @Select("""
        SELECT * FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_code = #{subjectCode}
          AND deleted_flag = false
        ORDER BY logic_group, logic_order
        """)
    List<SubjectPermission> findBySubjectCode(
        @Param("subjectType") String subjectType,
        @Param("subjectCode") String subjectCode
    );
    
    /**
     * Batch find permission declarations for multiple subjects
     * 
     * @param subjectType Subject type
     * @param subjectIds List of subject IDs
     * @return List of subject permission declarations
     */
    @Select("""
        <script>
        SELECT * FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_id IN
        <foreach collection="subjectIds" item="id" open="(" separator="," close=")">
          #{id}
        </foreach>
          AND deleted_flag = false
        ORDER BY subject_id, logic_group, logic_order
        </script>
        """)
    List<SubjectPermission> findBySubjects(
        @Param("subjectType") String subjectType,
        @Param("subjectIds") List<Long> subjectIds
    );
    
    /**
     * Find subjects that require a specific permission
     * 
     * @param permissionId Permission ID
     * @return List of subject permission declarations
     */
    @Select("""
        SELECT * FROM ab_subject_permission
        WHERE permission_id = #{permissionId}
          AND deleted_flag = false
        ORDER BY subject_type, subject_id, logic_group
        """)
    List<SubjectPermission> findByPermission(@Param("permissionId") Long permissionId);
    
    /**
     * Find declarations by logic group
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param logicGroup Logic group number
     * @return List of subject permission declarations
     */
    @Select("""
        SELECT * FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
          AND logic_group = #{logicGroup}
          AND deleted_flag = false
        ORDER BY logic_order
        """)
    List<SubjectPermission> findByLogicGroup(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId,
        @Param("logicGroup") Integer logicGroup
    );
    
    /**
     * Check logic group consistency
     * 
     * Validates that all declarations in the same logic group have the same group_logic_type.
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param logicGroup Logic group number
     * @return Count of distinct group_logic_type values (should be 1)
     */
    @Select("""
        SELECT COUNT(DISTINCT group_logic_type)
        FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
          AND logic_group = #{logicGroup}
          AND deleted_flag = false
        """)
    int checkLogicGroupConsistency(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId,
        @Param("logicGroup") Integer logicGroup
    );
    
    /**
     * Get max logic group number for a subject
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @return Max logic group number or 0 if no declarations exist
     */
    @Select("""
        SELECT COALESCE(MAX(logic_group), 0)
        FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
          AND deleted_flag = false
        """)
    int getMaxLogicGroup(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId
    );
    
    /**
     * Get max logic order within a logic group
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param logicGroup Logic group number
     * @return Max logic order or 0 if no declarations exist
     */
    @Select("""
        SELECT COALESCE(MAX(logic_order), 0)
        FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
          AND logic_group = #{logicGroup}
          AND deleted_flag = false
        """)
    int getMaxLogicOrder(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId,
        @Param("logicGroup") Integer logicGroup
    );
    
    /**
     * Check if declaration exists
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param subjectCode Subject code
     * @param permissionId Permission ID
     * @param logicGroup Logic group
     * @param excludeId ID to exclude (for update operations)
     * @return Count (0 or 1)
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM ab_subject_permission
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
          AND subject_code = #{subjectCode}
          AND permission_id = #{permissionId}
          AND logic_group = #{logicGroup}
          AND deleted_flag = false
        <if test="excludeId != null">
          AND id != #{excludeId}
        </if>
        </script>
        """)
    int countByDeclaration(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId,
        @Param("subjectCode") String subjectCode,
        @Param("permissionId") Long permissionId,
        @Param("logicGroup") Integer logicGroup,
        @Param("excludeId") Long excludeId
    );
    
    /**
     * Batch insert subject permission declarations
     * 
     * @param declarations List of declarations to insert
     * @return Number of rows inserted
     */
    @Insert("""
        <script>
        INSERT INTO ab_subject_permission (
            pid, tenant_id,  
            subject_type, subject_id, subject_code,
            permission_id,
            logic_group, group_logic_type, is_negated, logic_order,
            requirement_type, status, deleted_flag,
            created_at, updated_at, created_by, updated_by
        ) VALUES
        <foreach collection="declarations" item="decl" separator=",">
        (
            #{decl.pid}, #{decl.tenantId},
            #{decl.subjectType}, #{decl.subjectId}, #{decl.subjectCode},
            #{decl.permissionId},
            #{decl.logicGroup}, #{decl.groupLogicType}, #{decl.isNegated}, #{decl.logicOrder},
            #{decl.requirementType}, #{decl.status}, #{decl.deletedFlag},
            #{decl.createdAt}, #{decl.updatedAt}, #{decl.createdBy}, #{decl.updatedBy}
        )
        </foreach>
        </script>
        """)
    int batchInsert(@Param("declarations") List<SubjectPermission> declarations);
    
    /**
     * Delete all declarations for a subject
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @return Number of rows deleted
     */
    @Update("""
        UPDATE ab_subject_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
        """)
    int deleteBySubject(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId
    );
    
    /**
     * Delete declarations by logic group
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param logicGroup Logic group number
     * @return Number of rows deleted
     */
    @Update("""
        UPDATE ab_subject_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE subject_type = #{subjectType}
          AND subject_id = #{subjectId}
          AND logic_group = #{logicGroup}
        """)
    int deleteByLogicGroup(
        @Param("subjectType") String subjectType,
        @Param("subjectId") Long subjectId,
        @Param("logicGroup") Integer logicGroup
    );
    
    /**
     * Soft delete declaration
     * 
     * @param id Declaration ID
     * @return Number of rows updated
     */
    @Update("""
        UPDATE ab_subject_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE id = #{id}
        """)
    int softDelete(@Param("id") Long id);
}
