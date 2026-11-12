package com.auraboot.framework.permission.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** Explicit tenant/user joins are required before an async adapter has an ambient tenant. */
@Mapper
public interface HumanActorMapper {
    @InterceptorIgnore(tenantLine = "true")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>
        SELECT u.id AS user_id,u.pid AS user_pid,u.user_name AS username,m.id AS member_id
        FROM ab_user u JOIN ab_tenant_member m ON m.user_id=u.id
        WHERE m.tenant_id=#{tenantId} AND m.status='active' AND m.deleted_flag=FALSE
          AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
          AND (m.leave_date IS NULL OR m.leave_date &gt; CURRENT_TIMESTAMP)
          <if test="userId != null">AND u.id=#{userId}</if>
          <if test="userPid != null">AND u.pid=#{userPid}</if>
        </script>
        """)
    List<Map<String,Object>> findActive(@Param("tenantId") Long tenantId,
            @Param("userId") Long userId, @Param("userPid") String userPid);
}
