package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import lombok.Data;

import java.time.Instant;

/**
 * Audit + cache analysis log for {@code /api/semantic/query} requests.
 *
 * <p>Insert-only; partitioned drop after 90 days (PRD 16 §11).
 * No soft-delete column — purely append.
 *
 * <p>Used by:
 * <ul>
 *   <li>Grafana {@code semantic.query.duration_p95} dashboard;</li>
 *   <li>Per-tenant slow-query top-N;</li>
 *   <li>Hot metric identification for v0.2 pre-aggregation.</li>
 * </ul>
 */
@Data
@TableName("ab_semantic_query_log")
public class AbSemanticQueryLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String queryId;

    private Long tenantId;

    private Long userId;

    /** JSON array of metric pids. */
    private String metricPids;

    /** JSON array of dimension pids/codes. */
    private String dimensionPids;

    /** JSON dump of the filter clauses (after RLS injection). */
    private String filters;

    private Integer rowcount;

    private Integer durationMs;

    private Boolean cacheHit;

    /** Pre-aggregation pid if served from materialized rollup (v0.2). */
    private String preaggPid;

    /** SHA-256 of normalized SQL, used for repeated-query detection. */
    private String sqlFingerprint;

    @TableField(fill = FieldFill.INSERT)
    private Instant executedAt;
}
