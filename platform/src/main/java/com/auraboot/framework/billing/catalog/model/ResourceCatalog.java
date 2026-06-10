package com.auraboot.framework.billing.catalog.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical registry entry for a billing/quota resource type.
 *
 * <p>Backed by {@code ab_billing_resource_catalog}.  Each row represents a
 * single measurable resource dimension (e.g. AI_TOKEN, SEAT) and carries
 * the metadata that the quota and metering modules need to interpret usage
 * records without coupling to the resource's domain logic.
 *
 * <p>All standard resources are seeded at migration time
 * ({@code 2026-06-10-billing-resource-catalog.sql}).  Additional resources
 * can be registered at runtime (e.g. by marketplace plugins).
 *
 * <p>Convention: {@code id BIGINT} → {@link IdType#ASSIGN_ID} (snowflake).
 * See {@code AbUserAttribute} for the project-wide precedent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_resource_catalog")
public class ResourceCatalog {

    /** Snowflake ID — primary key. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Stable machine-readable code (e.g. {@code AI_TOKEN}, {@code SEAT}).
     * Unique across the catalog; referenced by quota definitions and metering events.
     */
    private String resourceCode;

    /**
     * Human-readable display name.
     * TODO(i18n): stored as UTF-8 text for now; migrate to i18n key once the
     *   platform's LocalizedText / $i18n: layer is wired into catalog reads.
     */
    private String resourceName;

    /**
     * Unit of measure (e.g. {@code COUNT}, {@code TOKEN}, {@code GB}, {@code DAY}).
     */
    private String unit;

    /**
     * Functional category grouping.
     * Stored as VARCHAR; matched to {@link ResourceCategory}.
     */
    private String category;

    /**
     * How usage is measured.
     * Stored as VARCHAR; matched to {@link MeteringMode}.
     */
    private String meteringMode;

    /**
     * How the quota for this resource is managed.
     * Stored as VARCHAR; matched to {@link QuotaMode}.
     */
    private String quotaMode;

    /**
     * Optional unit-conversion factor.
     * Example: {@code 1_000_000} to convert raw token count → billed M-token units.
     * Null means the raw usage value is already in the billing unit.
     */
    private BigDecimal conversionFactor;

    /**
     * Lifecycle status.  Active resources are returned by {@code listActive()}.
     * Values: {@code ACTIVE}, {@code DEPRECATED}, {@code RETIRED}.
     */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
