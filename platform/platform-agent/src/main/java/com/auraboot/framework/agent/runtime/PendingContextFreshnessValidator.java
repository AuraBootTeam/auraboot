package com.auraboot.framework.agent.runtime;

public interface PendingContextFreshnessValidator {

    PendingContextFreshnessDecision validate(PendingToolSnapshot pending);
}
