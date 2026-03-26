package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.UserProjectBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for UserProjectBinding entity.
 */
@Mapper
public interface UserProjectBindingMapper extends BaseMapper<UserProjectBinding> {

    @Insert("""
        INSERT INTO ab_user_project_binding (tenant_id, user_id, project_pid, binding_role, created_by, created_at, updated_at)
        VALUES (#{tenantId}, #{userId}, #{projectPid}, #{bindingRole}, #{createdBy}, now(), now())
        ON CONFLICT (tenant_id, user_id, project_pid) DO UPDATE
        SET binding_role = #{bindingRole}, updated_at = now()
        """)
    int upsertBinding(@Param("tenantId") Long tenantId,
                      @Param("userId") Long userId,
                      @Param("projectPid") String projectPid,
                      @Param("bindingRole") String bindingRole,
                      @Param("createdBy") Long createdBy);

    @Delete("""
        DELETE FROM ab_user_project_binding
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND project_pid = #{projectPid}
        """)
    int removeBinding(@Param("tenantId") Long tenantId,
                      @Param("userId") Long userId,
                      @Param("projectPid") String projectPid);

    @Select("""
        SELECT b.*, u.display_name AS user_name, u.email AS user_email
        FROM ab_user_project_binding b
        INNER JOIN ab_user u ON u.id = b.user_id
        WHERE b.tenant_id = #{tenantId} AND b.project_pid = #{projectPid}
        ORDER BY b.created_at ASC
        """)
    @Results({
        @Result(column = "id", property = "id"),
        @Result(column = "tenant_id", property = "tenantId"),
        @Result(column = "user_id", property = "userId"),
        @Result(column = "project_pid", property = "projectPid"),
        @Result(column = "binding_role", property = "bindingRole"),
        @Result(column = "created_by", property = "createdBy"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<UserProjectBinding> findByProjectPid(@Param("tenantId") Long tenantId,
                                               @Param("projectPid") String projectPid);

    @Select("""
        SELECT * FROM ab_user_project_binding
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        ORDER BY created_at ASC
        """)
    List<UserProjectBinding> findByUserId(@Param("tenantId") Long tenantId,
                                           @Param("userId") Long userId);

    @Select("""
        SELECT project_pid FROM ab_user_project_binding
        WHERE tenant_id = #{tenantId} AND user_id = #{userId}
        """)
    List<String> findProjectPidsByUserId(@Param("tenantId") Long tenantId,
                                          @Param("userId") Long userId);
}
