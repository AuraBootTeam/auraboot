package com.auraboot.framework.bpm.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BpmTaskCandidateMapper {

    @Select("""
            <script>
            SELECT ti.id::text
            FROM se_task_instance ti
            JOIN se_task_assignee_instance tai
              ON tai.task_instance_id = ti.id
             AND tai.process_instance_id = ti.process_instance_id
             AND tai.tenant_id = ti.tenant_id
            WHERE ti.tenant_id = #{tenantId}
              AND ti.status = 'pending'
              AND (
                tai.assignee_id = #{userId}
                <if test="groupIds != null and groupIds.size() > 0">
                  OR tai.assignee_id IN
                  <foreach collection="groupIds" item="groupId" open="(" separator="," close=")">
                    #{groupId}
                  </foreach>
                </if>
              )
            GROUP BY ti.id, ti.gmt_create
            ORDER BY ti.gmt_create DESC
            LIMIT #{limit}
            </script>
            """)
    List<String> findPendingTaskIdsVisibleTo(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("groupIds") List<String> groupIds,
            @Param("limit") int limit);
}
