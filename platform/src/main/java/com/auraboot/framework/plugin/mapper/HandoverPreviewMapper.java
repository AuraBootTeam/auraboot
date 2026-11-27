package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.handover.*;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface HandoverPreviewMapper extends BaseMapper<HandoverPreviewBatch> {
    @Select("SELECT * FROM ab_plugin_handover_batch WHERE tenant_id=#{tenant} AND idempotency_key=#{key}")
    @ResultMap("mybatis-plus_HandoverPreviewBatch")
    HandoverPreviewBatch byKey(@Param("tenant") Long tenant, @Param("key") String key);
    @Select("SELECT * FROM ab_plugin_handover_batch WHERE tenant_id=#{tenant} AND pid=#{pid}")
    @ResultMap("mybatis-plus_HandoverPreviewBatch")
    HandoverPreviewBatch byPid(@Param("tenant") Long tenant, @Param("pid") String pid);
}
