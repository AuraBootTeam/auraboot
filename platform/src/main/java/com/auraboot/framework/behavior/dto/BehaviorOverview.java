package com.auraboot.framework.behavior.dto;

import lombok.Data;

/**
 * Tenant behavior overview (M1 analysis): PV (page views), UV (unique visitors —
 * distinct user_id or anon_id), sessions, total events. Backed by ab_behavior_event.
 */
@Data
public class BehaviorOverview {
    private Long totalEvents;
    private Long pageViews;
    private Long uniqueVisitors;
    private Long sessions;
}
