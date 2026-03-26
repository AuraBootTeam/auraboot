package com.auraboot.framework.notification.mapper;

import com.auraboot.framework.notification.digest.DigestEntry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for DigestEntry entity.
 *
 * @since 6.0.0
 */
@Mapper
public interface DigestEntryMapper extends BaseMapper<DigestEntry> {

    /**
     * Find and lock flushable entries using SELECT FOR UPDATE SKIP LOCKED.
     * Ensures only one instance processes each entry in multi-node deployments.
     */
    @Select("SELECT * FROM ab_notification_digest WHERE flushed = false " +
            "AND (count >= #{threshold} OR window_start <= #{cutoff}) " +
            "FOR UPDATE SKIP LOCKED")
    List<DigestEntry> findFlushableEntriesForUpdate(int threshold, Instant cutoff);
}
