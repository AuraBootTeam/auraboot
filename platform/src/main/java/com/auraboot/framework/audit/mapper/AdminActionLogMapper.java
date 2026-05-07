package com.auraboot.framework.audit.mapper;

import com.auraboot.framework.audit.entity.AdminActionLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis-Plus mapper for {@link AdminActionLog}.
 *
 * <p>{@code BaseMapper.insert} doesn't emit the {@code ::jsonb} cast that
 * PostgreSQL needs for the {@code payload} column even when a Jackson type
 * handler is registered (the same gotcha that {@code PermissionAuditLogMapper}
 * works around). The custom {@link #insertActionLog} adds the cast explicitly.
 * Service-layer code MUST call this method instead of {@code insert(...)}.
 */
@Mapper
public interface AdminActionLogMapper extends BaseMapper<AdminActionLog> {

    @Insert("""
            INSERT INTO ab_admin_action_log
                (pid, tenant_id, actor_user_id, actor_type, action_type,
                 resource_type, resource_pid, success, reason, payload, created_at)
            VALUES
                (#{entry.pid}, #{entry.tenantId}, #{entry.actorUserId},
                 #{entry.actorType}, #{entry.actionType},
                 #{entry.resourceType}, #{entry.resourcePid},
                 #{entry.success}, #{entry.reason},
                 #{entry.payload, typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler}::jsonb,
                 #{entry.createdAt})
            """)
    int insertActionLog(@Param("entry") AdminActionLog entry);
}
