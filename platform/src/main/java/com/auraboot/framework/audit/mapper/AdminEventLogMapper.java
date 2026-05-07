package com.auraboot.framework.audit.mapper;

import com.auraboot.framework.audit.entity.AdminEventLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis-Plus mapper for {@link AdminEventLog}.
 *
 * <p>{@code BaseMapper.insert} doesn't emit the {@code ::jsonb} cast that
 * PostgreSQL needs for the {@code payload} column even when a Jackson type
 * handler is registered (the same gotcha that {@code PermissionAuditLogMapper}
 * works around). The custom {@link #insertEventLog} adds the cast explicitly.
 * Service-layer code MUST call this method instead of {@code insert(...)}.
 */
@Mapper
public interface AdminEventLogMapper extends BaseMapper<AdminEventLog> {

    @Insert("""
            INSERT INTO ab_admin_event_log
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
    int insertEventLog(@Param("entry") AdminEventLog entry);
}
