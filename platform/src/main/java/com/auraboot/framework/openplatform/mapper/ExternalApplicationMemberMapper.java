package com.auraboot.framework.openplatform.mapper;

import com.auraboot.framework.openplatform.entity.ExternalApplicationMember;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ExternalApplicationMemberMapper extends BaseMapper<ExternalApplicationMember> {

    @Select("""
            SELECT * FROM ab_external_application_member
            WHERE tenant_id = #{tenantId} AND application_id = #{applicationId}
            ORDER BY CASE role WHEN 'owner' THEN 0 WHEN 'maintainer' THEN 1 ELSE 2 END, created_at
            """)
    List<ExternalApplicationMember> findByApplication(@Param("tenantId") Long tenantId,
                                                       @Param("applicationId") Long applicationId);

    @Select("""
            SELECT * FROM ab_external_application_member
            WHERE tenant_id = #{tenantId} AND application_id = #{applicationId} AND user_pid = #{userPid}
            LIMIT 1
            """)
    ExternalApplicationMember findMember(@Param("tenantId") Long tenantId,
                                          @Param("applicationId") Long applicationId,
                                          @Param("userPid") String userPid);

    @Select("""
            SELECT COUNT(*) FROM ab_external_application_member
            WHERE tenant_id = #{tenantId} AND application_id = #{applicationId} AND role = 'owner'
            """)
    int countOwners(@Param("tenantId") Long tenantId, @Param("applicationId") Long applicationId);

    @Insert("""
            INSERT INTO ab_external_application_member
              (tenant_id, application_id, user_pid, role, created_by_pid, created_at, updated_by_pid, updated_at)
            VALUES (#{tenantId}, #{applicationId}, #{userPid}, #{role}, #{actorPid}, #{now}, #{actorPid}, #{now})
            ON CONFLICT (application_id, user_pid) DO UPDATE
            SET role = EXCLUDED.role, updated_by_pid = EXCLUDED.updated_by_pid, updated_at = EXCLUDED.updated_at
            """)
    int upsert(@Param("tenantId") Long tenantId, @Param("applicationId") Long applicationId,
               @Param("userPid") String userPid, @Param("role") String role,
               @Param("actorPid") String actorPid, @Param("now") Instant now);

    @Delete("""
            DELETE FROM ab_external_application_member
            WHERE tenant_id = #{tenantId} AND application_id = #{applicationId} AND user_pid = #{userPid}
            """)
    int deleteMember(@Param("tenantId") Long tenantId, @Param("applicationId") Long applicationId,
                     @Param("userPid") String userPid);

    @Insert("""
            INSERT INTO ab_external_application_member_audit
              (pid, tenant_id, application_id, action, target_user_pid, previous_role, new_role,
               actor_user_pid, occurred_at)
            VALUES (#{pid}, #{tenantId}, #{applicationId}, #{action}, #{targetUserPid},
                    #{previousRole}, #{newRole}, #{actorPid}, #{now})
            """)
    int insertAudit(@Param("pid") String pid, @Param("tenantId") Long tenantId,
                    @Param("applicationId") Long applicationId, @Param("action") String action,
                    @Param("targetUserPid") String targetUserPid,
                    @Param("previousRole") String previousRole, @Param("newRole") String newRole,
                    @Param("actorPid") String actorPid, @Param("now") Instant now);
}
