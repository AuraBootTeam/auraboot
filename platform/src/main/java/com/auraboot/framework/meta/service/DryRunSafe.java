package com.auraboot.framework.meta.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in marker for Spring-bean {@link CommandHandler} implementations
 * declaring they are safe to execute under
 * {@link com.auraboot.framework.meta.dto.CommandExecuteRequest#isDryRun() dryRun=true}.
 *
 * <p>The CommandPipeline transaction rollback only covers writes issued
 * through the pooled JDBC {@code DataSource}. External side effects —
 * outbound HTTP (RestTemplate / WebClient), email, message-queue publishes,
 * object-storage uploads, Redis writes, external-DB writes, file writes —
 * escape that envelope and will fire for real even under dry-run.
 *
 * <p>{@link com.auraboot.framework.meta.service.impl.pipeline.phases.HandlerPhase}
 * skips handlers whose class is NOT annotated with {@code @DryRunSafe}
 * whenever the request carries {@code dryRun=true}. The skip is logged at
 * {@code INFO} and the pipeline proceeds gracefully — it does not throw.
 *
 * <p>A handler qualifies for this marker when it either:
 * <ul>
 *   <li>has no side effects outside the JDBC connection managed by the
 *       enclosing transaction; or</li>
 *   <li>self-checks {@link CommandHandlerContext#isDryRun()} and branches
 *       to a no-op / side-effect-free path for every external call it would
 *       otherwise issue.</li>
 * </ul>
 *
 * <p>Plugin {@code CommandHandlerExtension} implementations signal the
 * same capability via {@code supportsDryRun()} on the SPI interface.
 *
 * @since PR-56
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface DryRunSafe {
}
