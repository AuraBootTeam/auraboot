package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.permission.entity.RecordShare;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for ab_record_share table.
 * Tenant isolation is managed manually (tenant_id in queries).
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface RecordShareMapper extends BaseMapper<RecordShare> {

    /**
     * Find shares for a specific record.
     */
    @Select("""
        SELECT * FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_id = #{recordId}
          AND (expires_at IS NULL OR expires_at > #{now})
        """)
    List<RecordShare> findByRecord(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordId") Long recordId,
            @Param("now") Instant now);

    /**
     * Find shares for a specific public record PID.
     */
    @Select("""
        SELECT * FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_pid = #{recordPid}
          AND (expires_at IS NULL OR expires_at > #{now})
        """)
    List<RecordShare> findByRecordPid(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordPid") String recordPid,
            @Param("now") Instant now);

    /**
     * Find shares where a specific member is the direct subject.
     */
    @Select("""
        SELECT * FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND subject_type = 'member'
          AND subject_id = #{userId}
          AND (expires_at IS NULL OR expires_at > #{now})
        """)
    List<RecordShare> findByUserDirect(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("userId") Long userId,
            @Param("now") Instant now);

    /**
     * Find shares where any of the given role IDs is the subject.
     */
    @Select("""
        <script>
        SELECT * FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND subject_type = 'role'
          AND subject_id IN
          <foreach item='id' collection='roleIds' open='(' separator=',' close=')'>
            #{id}
          </foreach>
          AND (expires_at IS NULL OR expires_at > #{now})
        </script>
        """)
    List<RecordShare> findByRoles(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("roleIds") List<Long> roleIds,
            @Param("now") Instant now);

    /**
     * Check if a specific record is shared with a member directly.
     */
    @Select("""
        SELECT COUNT(*) FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_id = #{recordId}
          AND subject_type = 'member'
          AND subject_id = #{userId}
          AND (expires_at IS NULL OR expires_at > #{now})
        """)
    int countByRecordAndUser(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordId") Long recordId,
            @Param("userId") Long userId,
            @Param("now") Instant now);

    /**
     * Check if a public record PID is shared with a subject PID.
     */
    @Select("""
        SELECT COUNT(*) FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_pid = #{recordPid}
          AND subject_type = #{subjectType}
          AND subject_pid = #{subjectPid}
          AND (expires_at IS NULL OR expires_at > #{now})
        """)
    int countByRecordPidAndSubjectPid(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordPid") String recordPid,
            @Param("subjectType") String subjectType,
            @Param("subjectPid") String subjectPid,
            @Param("now") Instant now);

    /**
     * Check if a public record PID is shared with a legacy member ID.
     */
    @Select("""
        SELECT COUNT(*) FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_pid = #{recordPid}
          AND subject_type = 'member'
          AND subject_id = #{userId}
          AND (expires_at IS NULL OR expires_at > #{now})
        """)
    int countByRecordPidAndUser(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordPid") String recordPid,
            @Param("userId") Long userId,
            @Param("now") Instant now);

    /**
     * Check if a specific record is shared with any of the given roles.
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_id = #{recordId}
          AND subject_type = 'role'
          AND subject_id IN
          <foreach item='id' collection='roleIds' open='(' separator=',' close=')'>
            #{id}
          </foreach>
          AND (expires_at IS NULL OR expires_at > #{now})
        </script>
        """)
    int countByRecordAndRoles(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordId") Long recordId,
            @Param("roleIds") List<Long> roleIds,
            @Param("now") Instant now);

    /**
     * Check if a public record PID is shared with any of the given legacy role IDs.
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_pid = #{recordPid}
          AND subject_type = 'role'
          AND subject_id IN
          <foreach item='id' collection='roleIds' open='(' separator=',' close=')'>
            #{id}
          </foreach>
          AND (expires_at IS NULL OR expires_at > #{now})
        </script>
        """)
    int countByRecordPidAndRoles(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordPid") String recordPid,
            @Param("roleIds") List<Long> roleIds,
            @Param("now") Instant now);

    /**
     * Delete a specific share entry.
     */
    @Delete("""
        DELETE FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND record_id = #{recordId}
          AND subject_type = #{subjectType}
          AND subject_id = #{subjectId}
        """)
    int deleteShare(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("recordId") Long recordId,
            @Param("subjectType") String subjectType,
            @Param("subjectId") Long subjectId);

    /**
     * Get all record IDs shared with a member (directly or via roles).
     */
    @Select("""
        <script>
        SELECT DISTINCT record_id FROM ab_record_share
        WHERE tenant_id = #{tenantId}
          AND resource_code = #{resourceCode}
          AND (expires_at IS NULL OR expires_at > #{now})
          AND (
            (subject_type = 'member' AND subject_id = #{userId})
            <if test="roleIds != null and roleIds.size() > 0">
            OR (subject_type = 'role' AND subject_id IN
              <foreach item='id' collection='roleIds' open='(' separator=',' close=')'>
                #{id}
              </foreach>
            )
            </if>
          )
        </script>
        """)
    List<Long> findSharedRecordIds(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("userId") Long userId,
            @Param("roleIds") List<Long> roleIds,
            @Param("now") Instant now);
}
