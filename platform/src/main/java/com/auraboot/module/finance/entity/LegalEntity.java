package com.auraboot.module.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Legal entity (company) for consolidated reporting.
 * Stored in {@code ab_legal_entity}.
 *
 * <p>A legal entity belongs to exactly one tenant (group/conglomerate).
 * Entities form a parent-child hierarchy via {@link #parentId}; the root entity
 * carries {@code isParent = true} and serves as the consolidation parent.
 */
@Data
@TableName("ab_legal_entity")
public class LegalEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** ULID public identifier. */
    private String pid;

    /** Tenant (group/conglomerate) that owns this entity. */
    private Long tenantId;

    /**
     * Short code identifying the entity, unique within a tenant.
     * Examples: "HQ", "sh_sub", "bj_sub".
     */
    private String entityCode;

    /** Human-readable name, e.g. "Shanghai Subsidiary". */
    private String entityName;

    /** Parent entity id; null for the root / consolidation parent. */
    private Long parentId;

    /** Functional currency (ISO 4217), e.g. "cny", "usd". */
    private String currency;

    /**
     * Parent company's ownership percentage (0–100).
     * Null for the root entity.
     */
    private BigDecimal ownershipPct;

    /**
     * Whether this entity is the consolidation parent (root of the group).
     * Exactly one entity per tenant should have this flag set.
     */
    private Boolean isParent;

    private Instant createdAt;
    private Instant updatedAt;
}
