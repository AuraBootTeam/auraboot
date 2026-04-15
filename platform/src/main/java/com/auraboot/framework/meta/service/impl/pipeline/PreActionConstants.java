package com.auraboot.framework.meta.service.impl.pipeline;

/**
 * Well-known {@code type} discriminators for entries in
 * {@code CommandDefinition.preActions} (pre-flight actions) and well-known
 * postAction {@code type} discriminators used by {@link
 * com.auraboot.framework.meta.service.impl.pipeline.phases.PostExecutionPhase}.
 *
 * <p>Kept in a dedicated constants class to avoid magic strings scattered
 * across the pipeline and plugin JSON schemas.
 *
 * @since 7.3.0
 */
public final class PreActionConstants {

    /** preAction type: evaluate a Drools rule pre-flight (abort on invalid). */
    public static final String TYPE_RUN_RULE = "bpm:run-rule";

    /** postAction type: start a BPM process via BpmIntegrationService. */
    public static final String POST_TYPE_START_PROCESS = "start_process";

    private PreActionConstants() {
        // no instances
    }
}
