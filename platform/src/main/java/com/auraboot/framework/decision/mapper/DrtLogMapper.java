package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.DrtLogEntity;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricAggregateRow;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricDistributionRow;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricWindowRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for {@link DrtLogEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtLogMapper extends BaseMapper<DrtLogEntity> {

    /**
     * Find all log entries for a given trace_id (may span multiple decisions in one trace).
     */
    @Select("SELECT * FROM ab_drt_log WHERE tenant_id = #{tenantId} AND trace_id = #{traceId} ORDER BY created_at DESC")
    List<DrtLogEntity> findByTraceId(
            @Param("tenantId") Long tenantId,
            @Param("traceId") String traceId);

    /**
     * Find one log entry by its public pid, scoped to the current tenant.
     */
    @Select("SELECT * FROM ab_drt_log WHERE tenant_id = #{tenantId} AND pid = #{pid} LIMIT 1")
    DrtLogEntity findByPid(
            @Param("tenantId") Long tenantId,
            @Param("pid") String pid);

    /**
     * Find recent log entries for a (tenant, decision_code), newest first.
     */
    @Select("SELECT * FROM ab_drt_log WHERE tenant_id = #{tenantId} AND decision_code = #{decisionCode} ORDER BY created_at DESC LIMIT #{limit}")
    List<DrtLogEntity> findByCode(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode,
            @Param("limit") int limit);

    @Select("SELECT * FROM ab_drt_log WHERE tenant_id = #{tenantId} AND created_at >= #{since} ORDER BY created_at DESC")
    List<DrtLogEntity> findSince(
            @Param("tenantId") Long tenantId,
            @Param("since") Instant since);

    @Select("""
            SELECT * FROM ab_drt_log
            WHERE tenant_id = #{tenantId}
              AND rollout_policy_pid = #{policyPid}
            ORDER BY created_at DESC
            """)
    List<DrtLogEntity> findByRolloutPolicy(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid);

    @Select("""
            SELECT rollout_arm,
                   COUNT(*) AS evaluations,
                   COUNT(*) FILTER (WHERE matched IS TRUE) AS matched,
                   COUNT(*) FILTER (WHERE status = 'ERROR') AS errors,
                   CAST(PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ms)
                       FILTER (WHERE duration_ms IS NOT NULL AND duration_ms >= 0) AS BIGINT) AS p95_latency_ms
            FROM ab_drt_log
            WHERE tenant_id = #{tenantId}
              AND rollout_policy_pid = #{policyPid}
              AND rollout_arm IS NOT NULL
            GROUP BY rollout_arm
            """)
    @Results(id = "rolloutMetricAggregateMap", value = {
            @Result(column = "rollout_arm", property = "rolloutArm"),
            @Result(column = "evaluations", property = "evaluations"),
            @Result(column = "matched", property = "matched"),
            @Result(column = "errors", property = "errors"),
            @Result(column = "p95_latency_ms", property = "p95LatencyMs")
    })
    @InterceptorIgnore(tenantLine = "true")
    List<DecisionRolloutMetricAggregateRow> aggregateByRolloutPolicy(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid);

    @Select("""
            SELECT rollout_arm,
                   COALESCE(rollout_result_key, status) AS result_key,
                   COUNT(*) AS item_count
            FROM ab_drt_log
            WHERE tenant_id = #{tenantId}
              AND rollout_policy_pid = #{policyPid}
              AND rollout_arm IS NOT NULL
            GROUP BY rollout_arm, COALESCE(rollout_result_key, status)
            ORDER BY item_count DESC, result_key ASC
            """)
    @Results(id = "rolloutMetricDistributionMap", value = {
            @Result(column = "rollout_arm", property = "rolloutArm"),
            @Result(column = "result_key", property = "resultKey"),
            @Result(column = "item_count", property = "itemCount")
    })
    @InterceptorIgnore(tenantLine = "true")
    List<DecisionRolloutMetricDistributionRow> aggregateDistributionByRolloutPolicy(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid);

    @Select("""
            SELECT to_timestamp(floor(extract(epoch FROM created_at) / #{bucketSeconds}) * #{bucketSeconds}) AS window_start,
                   rollout_arm,
                   COUNT(*) AS evaluations,
                   COUNT(*) FILTER (WHERE matched IS TRUE) AS matched,
                   COUNT(*) FILTER (WHERE status = 'ERROR') AS errors,
                   CAST(PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ms)
                       FILTER (WHERE duration_ms IS NOT NULL AND duration_ms >= 0) AS BIGINT) AS p95_latency_ms
            FROM ab_drt_log
            WHERE tenant_id = #{tenantId}
              AND rollout_policy_pid = #{policyPid}
              AND rollout_arm IS NOT NULL
              AND created_at >= #{since}
            GROUP BY window_start, rollout_arm
            ORDER BY window_start ASC
            """)
    @Results(id = "rolloutMetricWindowMap", value = {
            @Result(column = "window_start", property = "windowStart"),
            @Result(column = "rollout_arm", property = "rolloutArm"),
            @Result(column = "evaluations", property = "evaluations"),
            @Result(column = "matched", property = "matched"),
            @Result(column = "errors", property = "errors"),
            @Result(column = "p95_latency_ms", property = "p95LatencyMs")
    })
    @InterceptorIgnore(tenantLine = "true")
    List<DecisionRolloutMetricWindowRow> aggregateWindowsByRolloutPolicy(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid,
            @Param("since") Instant since,
            @Param("bucketSeconds") int bucketSeconds);

    @Insert("""
            WITH raw AS (
                SELECT tenant_id,
                       rollout_policy_pid,
                       decision_code,
                       rollout_arm,
                       to_timestamp(floor(extract(epoch FROM created_at) / #{bucketSeconds}) * #{bucketSeconds}) AS bucket_start,
                       matched,
                       status,
                       duration_ms,
                       COALESCE(rollout_result_key, status) AS result_key
                FROM ab_drt_log
                WHERE tenant_id = #{tenantId}
                  AND rollout_policy_pid = #{policyPid}
                  AND rollout_arm IS NOT NULL
                  AND created_at >= #{since}
            ),
            agg AS (
                SELECT tenant_id,
                       rollout_policy_pid,
                       decision_code,
                       rollout_arm,
                       bucket_start,
                       COUNT(*) AS evaluations,
                       COUNT(*) FILTER (WHERE matched IS TRUE) AS matched,
                       COUNT(*) FILTER (WHERE status = 'ERROR') AS errors,
                       CAST(PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL AND duration_ms >= 0) AS BIGINT) AS p95_latency_ms
                FROM raw
                GROUP BY tenant_id, rollout_policy_pid, decision_code, rollout_arm, bucket_start
            ),
            dist AS (
                SELECT tenant_id,
                       rollout_policy_pid,
                       rollout_arm,
                       bucket_start,
                       jsonb_object_agg(result_key, item_count) AS result_distribution_json
                FROM (
                    SELECT tenant_id,
                           rollout_policy_pid,
                           rollout_arm,
                           bucket_start,
                           result_key,
                           COUNT(*) AS item_count
                    FROM raw
                    GROUP BY tenant_id, rollout_policy_pid, rollout_arm, bucket_start, result_key
                ) grouped
                GROUP BY tenant_id, rollout_policy_pid, rollout_arm, bucket_start
            )
            INSERT INTO ab_drt_rollout_metric_bucket (
                tenant_id,
                rollout_policy_pid,
                decision_code,
                rollout_arm,
                bucket_seconds,
                bucket_start,
                evaluations,
                matched,
                errors,
                p95_latency_ms,
                result_distribution_json,
                refreshed_at,
                created_at,
                updated_at
            )
            SELECT agg.tenant_id,
                   agg.rollout_policy_pid,
                   agg.decision_code,
                   agg.rollout_arm,
                   #{bucketSeconds},
                   agg.bucket_start,
                   agg.evaluations,
                   agg.matched,
                   agg.errors,
                   agg.p95_latency_ms,
                   COALESCE(dist.result_distribution_json, '{}'::jsonb),
                   CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP
            FROM agg
            LEFT JOIN dist
              ON dist.tenant_id = agg.tenant_id
             AND dist.rollout_policy_pid = agg.rollout_policy_pid
             AND dist.rollout_arm = agg.rollout_arm
             AND dist.bucket_start = agg.bucket_start
            ON CONFLICT ON CONSTRAINT uk_drt_rollout_metric_bucket
            DO UPDATE SET
                decision_code = EXCLUDED.decision_code,
                evaluations = EXCLUDED.evaluations,
                matched = EXCLUDED.matched,
                errors = EXCLUDED.errors,
                p95_latency_ms = EXCLUDED.p95_latency_ms,
                result_distribution_json = EXCLUDED.result_distribution_json,
                refreshed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            """)
    @InterceptorIgnore(tenantLine = "true")
    int refreshRolloutMetricBuckets(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid,
            @Param("since") Instant since,
            @Param("bucketSeconds") int bucketSeconds);

    @Select("""
            SELECT rollout_arm,
                   SUM(evaluations) AS evaluations,
                   SUM(matched) AS matched,
                   SUM(errors) AS errors,
                   MAX(p95_latency_ms) AS p95_latency_ms
            FROM ab_drt_rollout_metric_bucket
            WHERE tenant_id = #{tenantId}
              AND rollout_policy_pid = #{policyPid}
              AND bucket_seconds = #{bucketSeconds}
              AND bucket_start >= #{since}
            GROUP BY rollout_arm
            """)
    @Result(column = "rollout_arm", property = "rolloutArm")
    @Result(column = "evaluations", property = "evaluations")
    @Result(column = "matched", property = "matched")
    @Result(column = "errors", property = "errors")
    @Result(column = "p95_latency_ms", property = "p95LatencyMs")
    @InterceptorIgnore(tenantLine = "true")
    List<DecisionRolloutMetricAggregateRow> aggregateMetricBucketsByRolloutPolicy(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid,
            @Param("since") Instant since,
            @Param("bucketSeconds") int bucketSeconds);

    @Select("""
            SELECT bucket.rollout_arm,
                   dist.key AS result_key,
                   SUM((dist.value)::BIGINT) AS item_count
            FROM ab_drt_rollout_metric_bucket bucket
            CROSS JOIN LATERAL jsonb_each_text(bucket.result_distribution_json) AS dist(key, value)
            WHERE bucket.tenant_id = #{tenantId}
              AND bucket.rollout_policy_pid = #{policyPid}
              AND bucket.bucket_seconds = #{bucketSeconds}
              AND bucket.bucket_start >= #{since}
            GROUP BY bucket.rollout_arm, dist.key
            ORDER BY item_count DESC, result_key ASC
            """)
    @Result(column = "rollout_arm", property = "rolloutArm")
    @Result(column = "result_key", property = "resultKey")
    @Result(column = "item_count", property = "itemCount")
    @InterceptorIgnore(tenantLine = "true")
    List<DecisionRolloutMetricDistributionRow> aggregateBucketDistributionByRolloutPolicy(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid,
            @Param("since") Instant since,
            @Param("bucketSeconds") int bucketSeconds);

    @Select("""
            SELECT bucket_start AS window_start,
                   rollout_arm,
                   evaluations,
                   matched,
                   errors,
                   p95_latency_ms
            FROM ab_drt_rollout_metric_bucket
            WHERE tenant_id = #{tenantId}
              AND rollout_policy_pid = #{policyPid}
              AND bucket_seconds = #{bucketSeconds}
              AND bucket_start >= #{since}
            ORDER BY bucket_start ASC
            """)
    @Result(column = "window_start", property = "windowStart")
    @Result(column = "rollout_arm", property = "rolloutArm")
    @Result(column = "evaluations", property = "evaluations")
    @Result(column = "matched", property = "matched")
    @Result(column = "errors", property = "errors")
    @Result(column = "p95_latency_ms", property = "p95LatencyMs")
    @InterceptorIgnore(tenantLine = "true")
    List<DecisionRolloutMetricWindowRow> findRolloutMetricBucketWindows(
            @Param("tenantId") Long tenantId,
            @Param("policyPid") String policyPid,
            @Param("since") Instant since,
            @Param("bucketSeconds") int bucketSeconds);

    @Delete("""
            DELETE FROM ab_drt_rollout_metric_bucket
            WHERE tenant_id = #{tenantId}
              AND bucket_start < #{cutoff}
            """)
    @InterceptorIgnore(tenantLine = "true")
    int deleteRolloutMetricBucketsOlderThan(
            @Param("tenantId") Long tenantId,
            @Param("cutoff") Instant cutoff);
}
