package com.auraboot.framework.agent.authorization;

/**
 * Eight effect classes for tool/skill/action capability classification.
 * See enterprise/docs/agent/contracts/effect-taxonomy.md.
 *
 * <p>New values must be synchronised with {@code valid_effect_class()} SQL function.
 */
public enum EffectClass {
    READ_CONTEXT,
    READ_PLATFORM_DATA,
    WRITE_DRAFT,
    WRITE_PLATFORM_STATE,
    EXTERNAL_NETWORK,
    FILE_WRITE,
    TERMINAL_EXEC,
    SECRET_ACCESS
}
