package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.handover.*;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface HandoverPreviewItemMapper extends BaseMapper<HandoverPreviewItem> {
    @Select("SELECT * FROM ab_plugin_handover_item WHERE tenant_id=#{tenant} AND batch_pid=#{pid} ORDER BY ordinal")
    @ResultMap("mybatis-plus_HandoverPreviewItem")
    List<HandoverPreviewItem> byBatch(@Param("tenant") Long tenant, @Param("pid") String pid);
}
