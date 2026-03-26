package com.auraboot.framework.event.user;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.event.DomainEventType;
import lombok.Getter;

import java.time.Instant;

/**
 * Event published when a user registers successfully.
 * Standalone POJO — Spring's ApplicationEventPublisher accepts any Object.
 *
 * @author AuraBoot Team
 */
@Getter
public class UserRegisteredEvent {

    private final String eventId;
    private final String eventType;
    private final Instant timestamp;
    private final Long userId;

    public UserRegisteredEvent(Long userId) {
        this.eventId = UniqueIdGenerator.generate();
        this.eventType = DomainEventType.USER_REGISTERED.getValue();
        this.timestamp = Instant.now();
        this.userId = userId;
    }
}
