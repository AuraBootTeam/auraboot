package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.Set;

/**
 * Service-Provider Interface for AuraBot Skills.
 *
 * <p>Every {@code @Component} implementing this interface is auto-registered
 * by {@link AuraBotSkillRegistry} during application startup. Duplicate
 * {@link #name()} values cause fail-fast bootstrap (see
 * docs/superpowers/specs/2026-05-08-aurabot-skill-spi-contract.md §5).
 */
public interface AuraBotSkill {

    /**
     * Globally unique skill identifier. Must match
     * {@code ^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)?$}.
     */
    String name();

    /**
     * i18n key (or literal) for human-friendly display in the @mention picker.
     */
    String displayName();

    /**
     * Optional category for grouping in the skill picker (e.g. {@code "model"}).
     */
    default String category() {
        return null;
    }

    /**
     * Risk level — drives confirm/undo policy in the validator pipeline.
     */
    RiskLevel riskLevel();

    /**
     * JSON Schema for {@link SkillRequest#getParams()}. Returned literally to
     * the FE in discovery payloads; validator uses
     * {@code networknt/json-schema-validator} for input validation.
     */
    JsonNode paramsSchema();

    /**
     * Permissions that the calling user must possess (intersection check).
     * Empty set = no permission requirement (e.g. dev-only Echo skill).
     */
    default Set<String> requiredPermissions() {
        return Collections.emptySet();
    }

    /** Reversibility hint surfaced via {@link SkillMeta#isSupportsUndo()}. */
    default boolean supportsUndo() {
        return false;
    }

    /** Dry-run support hint surfaced via {@link SkillMeta#isSupportsDryRun()}. */
    default boolean supportsDryRun() {
        return false;
    }

    /** Streaming hint surfaced via {@link SkillMeta#isSupportsStreaming()}. */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Optional preview without committing side effects.
     * Default impl throws — override when {@link #supportsDryRun()} is true.
     */
    default SkillResult dryRun(SkillRequest req) {
        throw new UnsupportedOperationException(name() + " does not support dry-run");
    }

    /**
     * Commit path. Implementations must be idempotent at the storage layer
     * (validator handles wire-level idempotency via Redis + DB unique constraint).
     */
    SkillResult execute(SkillRequest req);

    /**
     * Reversal path. Default impl throws — override when {@link #supportsUndo()} is true.
     */
    default SkillResult undo(String undoToken) {
        throw new UnsupportedOperationException(name() + " is not reversible");
    }
}
