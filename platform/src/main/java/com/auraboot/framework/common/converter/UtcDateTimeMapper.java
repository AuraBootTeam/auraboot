package com.auraboot.framework.common.converter;

import com.auraboot.framework.common.util.DateUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * MapStruct helper for UTC time conversions between Instant and LocalDateTime.
 */
@Service
public class UtcDateTimeMapper {

    public LocalDateTime toLocalDateTime(Instant instant) {
        return DateUtil.toUtcLocalDateTime(instant);
    }

    public Instant toInstant(LocalDateTime localDateTime) {
        return DateUtil.toUtcInstant(localDateTime);
    }
}
