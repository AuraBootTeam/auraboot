package com.auraboot.framework.eventpolicy.mapper;

import com.auraboot.framework.application.database.mybatis.JsonbMapTypeHandler;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link DrtPolicyExecLogEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtPolicyExecLogMapper extends BaseMapper<DrtPolicyExecLogEntity> {

    @Select("SELECT * FROM ab_drt_policy_exec_log WHERE tenant_id = #{tenantId} AND idempotency_key = #{key}")
    @Results(id = "policyExecLogMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "pid", property = "pid"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "policy_code", property = "policyCode"),
            @Result(column = "decision_trace_id", property = "decisionTraceId"),
            @Result(column = "correlation_id", property = "correlationId"),
            @Result(column = "rule_code", property = "ruleCode"),
            @Result(column = "action_type", property = "actionType"),
            @Result(column = "status", property = "status"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "result_payload", property = "resultPayload",
                    typeHandler = JsonbMapTypeHandler.class),
            @Result(column = "failure_strategy", property = "failureStrategy"),
            @Result(column = "action_payload", property = "actionPayload",
                    typeHandler = JsonbMapTypeHandler.class),
            @Result(column = "context_payload", property = "contextPayload",
                    typeHandler = JsonbMapTypeHandler.class),
            @Result(column = "attempt_count", property = "attemptCount"),
            @Result(column = "max_attempts", property = "maxAttempts"),
            @Result(column = "next_retry_at", property = "nextRetryAt"),
            @Result(column = "last_retry_at", property = "lastRetryAt"),
            @Result(column = "dead_lettered_at", property = "deadLetteredAt"),
            @Result(column = "executed_at", property = "executedAt")
    })
    DrtPolicyExecLogEntity findByTenantAndKey(@Param("tenantId") Long tenantId, @Param("key") String key);

    @Select("""
            SELECT * FROM ab_drt_policy_exec_log
            WHERE tenant_id = #{tenantId}
              AND (
                (#{correlationId} IS NOT NULL AND #{correlationId} <> '' AND correlation_id = #{correlationId})
                OR
                (#{decisionTraceId} IS NOT NULL AND #{decisionTraceId} <> '' AND decision_trace_id = #{decisionTraceId})
              )
            ORDER BY executed_at ASC, id ASC
            """)
    @ResultMap("policyExecLogMap")
    List<DrtPolicyExecLogEntity> findByTraceLink(
            @Param("tenantId") Long tenantId,
            @Param("decisionTraceId") String decisionTraceId,
            @Param("correlationId") String correlationId);

    @Select("""
            <script>
            SELECT * FROM ab_drt_policy_exec_log
            WHERE tenant_id = #{tenantId}
            <choose>
              <when test="policyCode != null and policyCode != '' and policyCodePrefix != null and policyCodePrefix != ''">
              AND (policy_code = #{policyCode} OR policy_code LIKE CONCAT(#{policyCodePrefix}, '%'))
              </when>
              <when test="policyCode != null and policyCode != ''">
              AND policy_code = #{policyCode}
              </when>
              <when test="policyCodePrefix != null and policyCodePrefix != ''">
              AND policy_code LIKE CONCAT(#{policyCodePrefix}, '%')
              </when>
              <otherwise>
              AND 1 = 0
              </otherwise>
            </choose>
            ORDER BY executed_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    @ResultMap("policyExecLogMap")
    List<DrtPolicyExecLogEntity> findByPolicyCodeFilter(
            @Param("tenantId") Long tenantId,
            @Param("policyCode") String policyCode,
            @Param("policyCodePrefix") String policyCodePrefix,
            @Param("limit") int limit);

    @Select("""
            SELECT * FROM ab_drt_policy_exec_log
            WHERE status = 'RETRY_PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)
              AND COALESCE(attempt_count, 0) < COALESCE(max_attempts, 3)
            ORDER BY COALESCE(next_retry_at, executed_at), executed_at, id
            LIMIT #{limit}
            """)
    @InterceptorIgnore(tenantLine = "true")
    @ResultMap("policyExecLogMap")
    List<DrtPolicyExecLogEntity> findReadyRetryPending(@Param("limit") int limit);
}
