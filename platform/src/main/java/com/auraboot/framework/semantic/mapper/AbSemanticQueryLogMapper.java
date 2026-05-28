package com.auraboot.framework.semantic.mapper;

import com.auraboot.framework.semantic.entity.AbSemanticQueryLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AbSemanticQueryLogMapper extends BaseMapper<AbSemanticQueryLog> {

    @Select("SELECT * FROM ab_semantic_query_log "
          + "WHERE tenant_id = #{tenantId} "
          + "AND executed_at >= #{since} "
          + "ORDER BY executed_at DESC LIMIT #{limit}")
    List<AbSemanticQueryLog> listRecent(@Param("tenantId") Long tenantId,
                                         @Param("since") Instant since,
                                         @Param("limit") int limit);

    /** Slow queries above duration threshold; used by Grafana p95 panel. */
    @Select("SELECT * FROM ab_semantic_query_log "
          + "WHERE tenant_id = #{tenantId} "
          + "AND executed_at >= #{since} "
          + "AND duration_ms >= #{minMs} "
          + "ORDER BY duration_ms DESC LIMIT #{limit}")
    List<AbSemanticQueryLog> listSlow(@Param("tenantId") Long tenantId,
                                       @Param("since") Instant since,
                                       @Param("minMs") int minMs,
                                       @Param("limit") int limit);
}
