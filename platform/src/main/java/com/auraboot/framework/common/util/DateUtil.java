package com.auraboot.framework.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * @author 高海军 帝奇 Apr 9, 2015 10:28:54 PM
 */
public abstract class DateUtil {

    public static Instant getCurrentInstant() {
        return Instant.now();
    }
    /**
     * @deprecated Use {@link TenantClock#businessDate(Long)} for tenant-aware dates,
     * or {@link #getCurrentInstant()} for timestamps. LocalDateTime in UTC is misleading
     * because LocalDateTime carries no timezone info.
     */
    @Deprecated
    public static LocalDateTime getCurrentLocalDateTimeUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static LocalDateTime toUtcLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant toUtcInstant(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.toInstant(ZoneOffset.UTC);
    }

}
