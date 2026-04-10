package com.auraboot.framework.meta.service.impl.pipeline;

/**
 * A single phase in the command execution pipeline.
 * Each phase encapsulates a distinct step (e.g., validation, field mapping, handler execution)
 * and declares only the dependencies it needs.
 *
 * @author AuraBoot Team
 * @since 8.0.0
 */
public interface CommandPhase {

    /**
     * Unique name of this phase, used for timing and error reporting.
     */
    String name();

    /**
     * Execute this phase. May modify the context (payload, fieldMapResults, etc.)
     * or throw to abort the pipeline.
     *
     * @param ctx shared pipeline context
     */
    void execute(CommandPipelineContext ctx);

    /**
     * Whether this phase should be skipped for the given context.
     * Default: never skip.
     */
    default boolean shouldSkip(CommandPipelineContext ctx) {
        return false;
    }
}
