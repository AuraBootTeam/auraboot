package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Request DTO for plugin import operations.
 *
 * Jackson deserialization pitfall: Lombok's @AllArgsConstructor + lombok.config's
 * addConstructorProperties=true generates @ConstructorProperties on the all-args
 * constructor. Jackson prefers this over the no-arg constructor, passing null for
 * any JSON fields not present — overriding manual defaults.
 *
 * Fix: @JsonCreator on no-arg constructor forces Jackson to use it, and
 * @JsonSetter(nulls = Nulls.SKIP) on Boolean fields prevents explicit null
 * in JSON from overriding defaults.
 */
@Data
@Builder
@AllArgsConstructor
public class ImportRequest {

    /**
     * No-arg constructor with defaults — annotated @JsonCreator to force Jackson
     * to use this over the @ConstructorProperties all-args constructor.
     */
    @JsonCreator
    public ImportRequest() {
        this.conflictStrategy = ConflictStrategy.OVERWRITE_SAFE;
        this.validateReferences = true;
        this.autoDeployProcesses = true;
        this.createResourcePermissions = false;
        this.autoPublishModels = true;
        this.autoPublishFields = true;
        this.autoPublishCommands = true;
        this.autoPublishPages = true;
        this.dryRun = false;
    }

    /**
     * Import ID (from preview).
     */
    private String importId;

    /**
     * Conflict resolution strategy.
     * OVERWRITE_SAFE - Overwrite unmodified, skip user-modified (default)
     * ERROR - Stop on conflict
     * SKIP - Skip conflicting resources
     * OVERWRITE - Overwrite all existing resources
     */
    @Builder.Default
    private ConflictStrategy conflictStrategy = ConflictStrategy.OVERWRITE_SAFE;

    /**
     * Whether to validate references between resources.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean validateReferences = true;

    /**
     * Whether to auto-deploy BPM processes.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean autoDeployProcesses = true;

    /**
     * Whether to create permissions for imported resources.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean createResourcePermissions = false;

    /**
     * Whether to publish models after import.
     * Default true - plugins should be ready to use immediately.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean autoPublishModels = true;

    /**
     * Whether to publish fields after import.
     * Default true - plugins should be ready to use immediately.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean autoPublishFields = true;

    /**
     * Whether to publish commands after import.
     * Default true - plugins should be ready to use immediately.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean autoPublishCommands = true;

    /**
     * Whether to publish pages after import.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean autoPublishPages = true;

    /**
     * Whether to perform dry run (preview only).
     */
    @JsonSetter(nulls = Nulls.SKIP)
    @Builder.Default
    private Boolean dryRun = false;

    /**
     * Apply defaults for any null fields.
     * Required because Jackson + Lombok @AllArgsConstructor + @ConstructorProperties
     * bypasses the no-arg constructor defaults when deserializing partial JSON.
     */
    public void applyDefaults() {
        if (this.conflictStrategy == null) this.conflictStrategy = ConflictStrategy.OVERWRITE_SAFE;
        if (this.validateReferences == null) this.validateReferences = true;
        if (this.autoDeployProcesses == null) this.autoDeployProcesses = true;
        if (this.createResourcePermissions == null) this.createResourcePermissions = false;
        if (this.autoPublishModels == null) this.autoPublishModels = true;
        if (this.autoPublishFields == null) this.autoPublishFields = true;
        if (this.autoPublishCommands == null) this.autoPublishCommands = true;
        if (this.autoPublishPages == null) this.autoPublishPages = true;
        if (this.dryRun == null) this.dryRun = false;
    }

    /**
     * Conflict resolution strategy enum.
     */
    public enum ConflictStrategy {
        /**
         * Stop import on any conflict.
         */
        ERROR,

        /**
         * Skip conflicting resources.
         */
        SKIP,

        /**
         * Overwrite existing resources (including user-modified ones).
         */
        OVERWRITE,

        /**
         * Overwrite unmodified resources, skip user-modified ones (safe default).
         * Resources marked as user_modified=true in ab_plugin_resource will be skipped.
         */
        OVERWRITE_SAFE;

        @JsonCreator
        public static ConflictStrategy fromValue(String value) {
            if (value == null) return OVERWRITE_SAFE;
            return valueOf(value.toUpperCase());
        }
    }
}
