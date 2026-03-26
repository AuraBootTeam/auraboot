package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.ApprovalTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApprovalTaskMapper extends BaseMapper<ApprovalTask> {

    /**
     * Find pending tasks assigned to a specific user via JSONB containment.
     * TenantLineInterceptor auto-adds tenant_id filter.
     */
    @Select("SELECT * FROM ab_approval_task " +
            "WHERE status = 'pending' " +
            "AND assignee_user_ids @> CAST(#{userIdJson} AS jsonb) " +
            "ORDER BY CASE priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'normal' THEN 2 ELSE 3 END, " +
            "created_at ASC")
    List<ApprovalTask> findPendingByAssigneeUserId(@Param("userIdJson") String userIdJson);

    /**
     * Count pending tasks for a specific user.
     */
    @Select("SELECT COUNT(*) FROM ab_approval_task " +
            "WHERE status = 'pending' " +
            "AND assignee_user_ids @> CAST(#{userIdJson} AS jsonb)")
    int countPendingByUserId(@Param("userIdJson") String userIdJson);
}
