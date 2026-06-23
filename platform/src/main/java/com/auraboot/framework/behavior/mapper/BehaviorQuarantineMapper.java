package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/** Mapper for the behavior ingest quarantine sink (SoT §2.7 quarantine.v1). */
@Mapper
public interface BehaviorQuarantineMapper extends BaseMapper<BehaviorQuarantine> {

    @Delete("""
            DELETE FROM ab_behavior_quarantine
            WHERE id IN (
                SELECT id
                FROM ab_behavior_quarantine
                WHERE quarantined_at < #{cutoff}
                ORDER BY quarantined_at ASC, id ASC
                LIMIT #{limit}
            )
            """)
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
