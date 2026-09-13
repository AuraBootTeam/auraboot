package com.auraboot.framework.behavior.dto;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.common.constant.ResponseCode;
import java.time.Duration;
import java.time.Instant;

/** Bounded, half-open event-time window shared by behavior rollups. */
public record BehaviorQueryWindow(Instant from, Instant to) {
    public BehaviorQueryWindow {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BusinessException(ResponseCode.BadParam, "Behavior window requires from < to");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new BusinessException(ResponseCode.BadParam, "Behavior window must not exceed 366 days");
        }
    }

    public static BehaviorQueryWindow resolve(Instant from, Instant to, Instant now) {
        if (from == null && to == null) {
            return new BehaviorQueryWindow(now.minus(Duration.ofDays(30)), now);
        }
        return new BehaviorQueryWindow(from, to);
    }
}
