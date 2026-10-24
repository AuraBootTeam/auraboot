package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** Current directory facts; every query is explicitly constrained to the trusted tenant. */
@Mapper
public interface AnnouncementAudienceMapper {
    @Select("""
        SELECT m.id AS member_id,m.pid AS member_pid,u.pid AS user_pid,u.nick_name AS display_name,u.user_name AS username,
            e.org_emp_dept_id AS department_pid
        FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id
        LEFT JOIN mt_org_employee e ON e.tenant_id=m.tenant_id AND e.deleted_flag=FALSE
            AND e.org_emp_status='active' AND e.org_emp_member_id=m.pid
        WHERE m.tenant_id=#{tenant} AND m.status='active' AND m.deleted_flag=FALSE
            AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        ORDER BY u.pid,m.pid
        """)
    List<Map<String,Object>> members(@Param("tenant") Long tenant);

    @Select("""
        SELECT pid,org_dept_parent_id AS parent_pid,org_dept_name AS label FROM mt_org_department
        WHERE tenant_id=#{tenant} AND deleted_flag=FALSE AND org_dept_status='active' ORDER BY pid
        """)
    List<Map<String,Object>> departments(@Param("tenant") Long tenant);

    @Select("""
        SELECT r.pid AS role_pid,r.name AS label,u.pid AS user_pid FROM ab_role r
        LEFT JOIN ab_user_role ur ON ur.role_id=r.id AND ur.tenant_id=#{tenant}
            AND ur.deleted_flag=FALSE AND ur.status='active'
            AND (ur.effective_date IS NULL OR ur.effective_date<=CURRENT_DATE)
            AND (ur.expiry_date IS NULL OR ur.expiry_date>=CURRENT_DATE)
        LEFT JOIN ab_tenant_member m ON m.id=ur.member_id AND m.tenant_id=#{tenant}
            AND m.status='active' AND m.deleted_flag=FALSE
        LEFT JOIN ab_user u ON u.id=m.user_id AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        WHERE r.tenant_id=#{tenant} AND r.deleted_flag=FALSE AND r.status='active' ORDER BY r.pid,u.pid
        """)
    List<Map<String,Object>> roles(@Param("tenant") Long tenant);

    @Select("""
        SELECT t.pid AS team_pid,u.pid AS user_pid FROM ab_team t
        JOIN ab_team_member tm ON tm.team_id=t.id AND tm.tenant_id=t.tenant_id
        JOIN ab_user u ON u.id=tm.user_id AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        JOIN ab_tenant_member m ON m.user_id=u.id AND m.tenant_id=t.tenant_id
            AND m.status='active' AND m.deleted_flag=FALSE
        WHERE t.tenant_id=#{tenant} AND t.status='active' AND t.deleted_flag=FALSE ORDER BY t.pid,u.pid
        """)
    List<Map<String,Object>> teams(@Param("tenant") Long tenant);
}
