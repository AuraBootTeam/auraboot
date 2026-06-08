package com.auraboot.framework.eventpolicy.mapper;

import com.auraboot.framework.eventpolicy.entity.DrtOutboxEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link DrtOutboxEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtOutboxMapper extends BaseMapper<DrtOutboxEntity> {

    @Select("SELECT * FROM ab_drt_outbox WHERE tenant_id = #{tenantId} AND status = 'PENDING' "
            + "ORDER BY created_at LIMIT #{limit}")
    List<DrtOutboxEntity> findPending(@Param("tenantId") Long tenantId, @Param("limit") int limit);

    @Select("SELECT * FROM ab_drt_outbox WHERE tenant_id = #{tenantId} AND event_id = #{eventId}")
    DrtOutboxEntity findByEventId(@Param("tenantId") Long tenantId, @Param("eventId") String eventId);
}
