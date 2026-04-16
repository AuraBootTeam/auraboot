package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmCcRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BpmCcRecordMapper extends BaseMapper<BpmCcRecord> {

    @Select("""
        SELECT * FROM ab_bpm_cc_record
        WHERE tenant_id = #{tenantId}
          AND process_instance_id = #{processInstanceId}
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY created_at ASC
        """)
    List<BpmCcRecord> findByProcessInstance(@Param("tenantId") Long tenantId,
                                            @Param("processInstanceId") String processInstanceId);

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
