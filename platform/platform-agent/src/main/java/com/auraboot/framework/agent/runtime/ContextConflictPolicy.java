package com.auraboot.framework.agent.runtime;

public enum ContextConflictPolicy {
    REJECT_AND_REPLAN,
    REGENERATE_PREVIEW,
    ALLOW_IF_NON_CRITICAL,
    ASK_USER_TO_CONFIRM_AGAIN
}
