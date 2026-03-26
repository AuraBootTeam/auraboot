package com.auraboot.framework.notification.routing;

import com.auraboot.framework.event.AuraEvent;

import java.util.List;

/**
 * SPI for resolving notification recipients from event context.
 *
 * Implementations translate a strategy name (e.g. OPERATOR, RECORD_OWNER)
 * into a concrete list of user IDs that should receive the notification.
 *
 * @since 6.0.0
 */
public interface RecipientResolver {

    /**
     * Resolve recipient user IDs for a given event and strategy.
     *
     * @param event          the source event
     * @param strategy       strategy name (e.g. "operator", "record_owner")
     * @param strategyConfig optional JSON config for the strategy (may be null)
     * @return list of user IDs; empty list if no recipients can be resolved
     */
    List<Long> resolve(AuraEvent event, String strategy, String strategyConfig);
}
