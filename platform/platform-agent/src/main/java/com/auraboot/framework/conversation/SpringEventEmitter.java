package com.auraboot.framework.conversation;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Phase B.2 implementation of {@link TurnSideEffects.EventEmitter}. Replaces
 * {@link TurnSideEffects.EventEmitter#NOOP} that Phase A injected so
 * {@link TurnCompletedEvent} and {@link TurnSuspendedEvent} are published as
 * Spring application events and downstream listeners (memory L1 writeback,
 * cross-channel sync, audit pipelines, metrics) can subscribe via
 * {@code @EventListener}.
 *
 * <p>Phase A's NOOP made the event bundle a stub — emit was a no-op so even
 * if a listener registered, it would never fire. B.2 makes the chokepoint
 * publish-real, and the events become the single seam through which all
 * post-turn workflows attach (per design v3.3 §3.2 row "Memory L1 writeback /
 * Audit / Approval Gate" — all collapse to a single event subscription
 * surface).
 *
 * <p>Per design §3.4 invariants:
 * <ul>
 *     <li>Each turn lifecycle emits {@link TurnCompletedEvent} <strong>exactly once</strong></li>
 *     <li>{@link TurnSuspendedEvent} can fire arbitrary many times across resume cycles</li>
 * </ul>
 * Both are guaranteed by {@code ConversationTurnServiceImpl.finalizeTurn} dispatch;
 * this emitter is purely a transport.
 */
@Component
public class SpringEventEmitter implements TurnSideEffects.EventEmitter {

    private final ApplicationEventPublisher publisher;

    public SpringEventEmitter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void emit(Object event) {
        if (event == null) {
            // Defensive: ConversationTurnServiceImpl never emits null in any branch,
            // but if a future caller does we silently ignore rather than NPE.
            return;
        }
        publisher.publishEvent(event);
    }
}
