package com.auraboot.framework.meta.annotation;

import java.lang.annotation.*;

/**
 * Marks a Spring bean as a handler for one or more command pipeline phases.
 * This annotation is purely metadata for IDE/tool discoverability and does NOT
 * alter the execution order of the pipeline. The actual orchestration remains
 * in {@code CommandExecutorImpl}.
 *
 * <p>Usage example:
 * <pre>
 * {@code @CommandPhase(stage = CommandStage.STATE_CHECK, name = "State Check",
 *     transactional = true, interruptible = true,
 *     description = "Validates state transitions using state graph definitions")}
 * {@code @Component}
 * public class CommandStateCheckExecutor { ... }
 * </pre>
 *
 * @author AuraBoot Team
 * @since 2.5.0
 * @see com.auraboot.framework.meta.constant.CommandStage
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CommandPhase {

    /**
     * Pipeline stage number(s) this handler participates in.
     * Use constants from {@link com.auraboot.framework.meta.constant.CommandStage}.
     */
    int[] stage();

    /**
     * Human-readable name of this phase handler.
     */
    String name();

    /**
     * Whether this phase runs inside the command's @Transactional boundary.
     * Default is {@code true} because most phases run within the transaction.
     */
    boolean transactional() default true;

    /**
     * Whether this phase can interrupt (abort) the pipeline by throwing an exception.
     * Default is {@code true}.
     */
    boolean interruptible() default true;

    /**
     * Brief description of what this phase handler does.
     */
    String description() default "";
}
