package com.auraboot.framework.bpm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BpmNotifyRecordMapper extends BaseMapper<BpmNotifyRecord> {

    @Select("SELECT * FROM ab_bpm_notify_record WHERE tenant_id = #{tenantId} AND recipient_user_id = #{userId} AND notify_type = #{notifyType} AND deleted_flag = false ORDER BY created_at DESC")
    List<BpmNotifyRecord> findByRecipient(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("notifyType") String notifyType);

    @Select("SELECT * FROM ab_bpm_notify_record WHERE pid = #{pid} AND deleted_flag = false")
    BpmNotifyRecord findByPid(@Param("pid") String pid);
}
