package com.auraboot.framework.base.annotation;

import java.lang.annotation.*;

/**
 * Marks a Spring bean as a handler for one or more command pipeline phases.
 * This annotation is metadata for IDE/tool discoverability and runtime
 * introspection via {@code CommandPipelineRegistry}.
 *
 * <p>The annotation does NOT alter the execution order of the pipeline.
 * The actual orchestration remains in the service orchestration layer.
 *
 * <p>Usage example:
 * <pre>
 * {@code @CommandPhase(stage = CommandStage.STATE_CHECK, name = "State Check",
 *     interruptible = true,
 *     description = "Validates state transitions using state graph definitions")}
 * {@code @Component}
 * public class StateCheckHandler { ... }
 * </pre>
 *
 * @see com.auraboot.framework.base.constant.CommandStage
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommandPhase {

    /**
     * Pipeline stage number this handler participates in.
     * Use constants from {@link com.auraboot.framework.base.constant.CommandStage}.
     */
    int stage();

    /**
     * Human-readable name of this phase handler.
     */
    String name() default "";

    /**
     * Whether this phase can interrupt (abort) the pipeline by throwing an exception.
     */
    boolean interruptible() default false;

    /**
     * Transaction mode for this phase handler.
     */
    TransactionMode transaction() default TransactionMode.INHERITED;

    /**
     * Brief description of what this phase handler does.
     */
    String description() default "";

    /**
     * Transaction propagation modes for command pipeline phases.
     */
    enum TransactionMode {
        /** Inherits the surrounding transaction context (default). */
        INHERITED,
        /** Starts a new independent transaction. */
        REQUIRES_NEW,
        /** Runs outside any transaction boundary. */
        NOT_SUPPORTED
    }
}
