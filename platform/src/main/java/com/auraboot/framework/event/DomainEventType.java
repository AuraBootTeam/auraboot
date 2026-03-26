package com.auraboot.framework.event;

/**
 * Enumeration of domain event types.
 * Provides compile-time safety for event type strings used in outbox serialization
 * and event listeners.
 *
 * @author AuraBoot Team
 * @since 6.1.0
 */
public enum DomainEventType {

    COMMAND_EXECUTED("CommandExecutedEvent"),
    STATE_TRANSITION("StateTransitionEvent"),
    DECISION("DecisionEvent"),
    USER_REGISTERED("UserRegisteredEvent");

    private final String value;

    DomainEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
