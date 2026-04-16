package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmCcRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;

@Mapper
public interface BpmCcRecordMapper extends BaseMapper<BpmCcRecord> {

    @Results(id = "ccRecordMap", value = {
        @Result(column = "id", property = "id", id = true),
        @Result(column = "pid", property = "pid"),
        @Result(column = "tenant_id", property = "tenantId"),
        @Result(column = "process_instance_id", property = "processInstanceId"),
        @Result(column = "task_id", property = "taskId"),
        @Result(column = "sender_id", property = "senderId"),
        @Result(column = "receiver_user_ids", property = "receiverUserIds",
                jdbcType = JdbcType.OTHER,
                typeHandler = com.auraboot.framework.bpm.typehandler.JsonListLongTypeHandler.class),
        @Result(column = "comment", property = "comment"),
        @Result(column = "read_state", property = "readState",
                jdbcType = JdbcType.OTHER,
                typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "deleted_flag", property = "deletedFlag")
    })
    @Select("""
        SELECT * FROM ab_bpm_cc_record
        WHERE tenant_id = #{tenantId}
          AND process_instance_id = #{processInstanceId}
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY created_at ASC
        """)
    List<BpmCcRecord> findByProcessInstance(@Param("tenantId") Long tenantId,
                                            @Param("processInstanceId") String processInstanceId);

    @org.apache.ibatis.annotations.ResultMap("ccRecordMap")
    @Select("""
        SELECT * FROM ab_bpm_cc_record
        WHERE tenant_id = #{tenantId}
          AND receiver_user_ids @> CAST(#{userIdJson} AS JSONB)
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY created_at DESC
        """)
    List<BpmCcRecord> findByReceiver(@Param("tenantId") Long tenantId,
                                     @Param("userIdJson") String userIdJson);
}
