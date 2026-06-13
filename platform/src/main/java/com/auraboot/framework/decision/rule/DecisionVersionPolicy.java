package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.model.VersionBinding;

/**
 * Consumer-facing version policy for rule-center bindings.
 */
public enum DecisionVersionPolicy {
    LATEST_PUBLISHED,
    FIXED_VERSION,
    VERSION_TAG,
    ROLLOUT,
    DEPLOYMENT_VERSION,
    EFFECTIVE_TIME,
    AS_OF_EVENT_TIME;

    public VersionBinding toVersionBinding() {
        return switch (this) {
            case LATEST_PUBLISHED -> VersionBinding.LATEST;
            case FIXED_VERSION -> VersionBinding.FIXED_VERSION;
            case VERSION_TAG -> VersionBinding.VERSION_TAG;
            case ROLLOUT -> VersionBinding.ROLLOUT;
            case DEPLOYMENT_VERSION -> VersionBinding.DEPLOYMENT_VERSION;
            case EFFECTIVE_TIME -> VersionBinding.EFFECTIVE_TIME;
            case AS_OF_EVENT_TIME -> VersionBinding.AS_OF_EVENT_TIME;
        };
    }
}
