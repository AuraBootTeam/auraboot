package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.QueryAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Query audit log Mapper.
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Mapper
public interface QueryAuditLogMapper extends BaseMapper<QueryAuditLog> {

    // ==================== Count Queries ====================

    /**
     * Count queries by tenant and time range.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countByTenantAndTimeRange(@Param("tenantId") Long tenantId,
                                   @Param("startTime") Instant startTime,
                                   @Param("endTime") Instant endTime);

    /**
     * Count queries by tenant, user and time range.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countByTenantUserAndTimeRange(@Param("tenantId") Long tenantId,
                                       @Param("userId") Long userId,
                                       @Param("startTime") Instant startTime,
                                       @Param("endTime") Instant endTime);

    /**
     * Count queries by tenant, model and time range.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countByTenantModelAndTimeRange(@Param("tenantId") Long tenantId,
                                        @Param("modelCode") String modelCode,
                                        @Param("startTime") Instant startTime,
                                        @Param("endTime") Instant endTime);

    /**
     * Count successful queries.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND success = TRUE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countSuccessfulQueries(@Param("tenantId") Long tenantId,
                                @Param("startTime") Instant startTime,
                                @Param("endTime") Instant endTime);

    /**
     * Count failed queries.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND success = FALSE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countFailedQueries(@Param("tenantId") Long tenantId,
                            @Param("startTime") Instant startTime,
                            @Param("endTime") Instant endTime);

    /**
     * Count slow queries (using execution_time_ms with fallback to cost_ms).
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND COALESCE(execution_time_ms, cost_ms, 0) >= #{thresholdMs} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countSlowQueries(@Param("tenantId") Long tenantId,
                          @Param("thresholdMs") Integer thresholdMs,
                          @Param("startTime") Instant startTime,
                          @Param("endTime") Instant endTime);

    // ==================== Aggregate Queries ====================

    /**
     * Calculate average execution time (uses execution_time_ms with fallback to cost_ms).
     */
    @Select("SELECT AVG(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Double calculateAverageExecutionTime(@Param("tenantId") Long tenantId,
                                         @Param("startTime") Instant startTime,
                                         @Param("endTime") Instant endTime);

    /**
     * Get max execution time.
     */
    @Select("SELECT MAX(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Integer getMaxExecutionTime(@Param("tenantId") Long tenantId,
                                @Param("startTime") Instant startTime,
                                @Param("endTime") Instant endTime);

    /**
     * Get min execution time.
     */
    @Select("SELECT MIN(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND COALESCE(execution_time_ms, cost_ms) IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Integer getMinExecutionTime(@Param("tenantId") Long tenantId,
                                @Param("startTime") Instant startTime,
                                @Param("endTime") Instant endTime);

    /**
     * Count cache hits.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND cache_hit = TRUE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countCacheHits(@Param("tenantId") Long tenantId,
                        @Param("startTime") Instant startTime,
                        @Param("endTime") Instant endTime);

    /**
     * Count data masking applications.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND data_masking_applied = TRUE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countDataMaskingApplications(@Param("tenantId") Long tenantId,
                                      @Param("startTime") Instant startTime,
                                      @Param("endTime") Instant endTime);

    /**
     * Count unique users.
     */
    @Select("SELECT COUNT(DISTINCT user_id) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countUniqueUsers(@Param("tenantId") Long tenantId,
                          @Param("startTime") Instant startTime,
                          @Param("endTime") Instant endTime);

    /**
     * Count unique models.
     */
    @Select("SELECT COUNT(DISTINCT model_code) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countUniqueModels(@Param("tenantId") Long tenantId,
                           @Param("startTime") Instant startTime,
                           @Param("endTime") Instant endTime);

    /**
     * Sum total records returned.
     */
    @Select("SELECT COALESCE(SUM(COALESCE(result_count, 0)), 0) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long sumTotalRecordsReturned(@Param("tenantId") Long tenantId,
                                 @Param("startTime") Instant startTime,
                                 @Param("endTime") Instant endTime);

    // ==================== Group-By Queries (PostgreSQL) ====================

    /**
     * Count by query type.
     */
    @Select("SELECT query_type, COUNT(*) as count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND query_type IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY query_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByQueryType(@Param("tenantId") Long tenantId,
                                               @Param("startTime") Instant startTime,
                                               @Param("endTime") Instant endTime);

    /**
     * Count by model.
     */
    @Select("SELECT model_code, COUNT(*) as count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY model_code " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByModel(@Param("tenantId") Long tenantId,
                                           @Param("startTime") Instant startTime,
                                           @Param("endTime") Instant endTime);

    /**
     * Count by user.
     */
    @Select("SELECT user_id, COUNT(*) as count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY user_id " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByUser(@Param("tenantId") Long tenantId,
                                          @Param("startTime") Instant startTime,
                                          @Param("endTime") Instant endTime);

    /**
     * Count by hour (PostgreSQL EXTRACT).
     */
    @Select("SELECT EXTRACT(HOUR FROM created_at) AS hour, COUNT(*) AS count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY EXTRACT(HOUR FROM created_at) " +
            "ORDER BY hour")
    List<Map<String, Object>> countByHour(@Param("tenantId") Long tenantId,
                                          @Param("startTime") Instant startTime,
                                          @Param("endTime") Instant endTime);

    /**
     * Count by date (PostgreSQL ::date cast).
     */
    @Select("SELECT created_at::date AS date, COUNT(*) AS count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY created_at::date " +
            "ORDER BY date")
    List<Map<String, Object>> countByDate(@Param("tenantId") Long tenantId,
                                          @Param("startTime") Instant startTime,
                                          @Param("endTime") Instant endTime);

    /**
     * Count by error type.
     */
    @Select("SELECT error_type, COUNT(*) AS count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND success = FALSE " +
            "AND error_type IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY error_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countByErrorType(@Param("tenantId") Long tenantId,
                                               @Param("startTime") Instant startTime,
                                               @Param("endTime") Instant endTime);

    // ==================== Percentile Queries (PostgreSQL) ====================

    /**
     * Calculate percentile execution time (p50/p95/p99).
     */
    @Select("SELECT PERCENTILE_CONT(#{percentile}) WITHIN GROUP (ORDER BY COALESCE(execution_time_ms, cost_ms)) " +
            "FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND COALESCE(execution_time_ms, cost_ms) IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Double calculatePercentileExecutionTime(@Param("tenantId") Long tenantId,
                                            @Param("percentile") Double percentile,
                                            @Param("startTime") Instant startTime,
                                            @Param("endTime") Instant endTime);

    /**
     * Get execution time distribution in predefined buckets.
     */
    @Select("SELECT " +
            "  CASE " +
            "    WHEN COALESCE(execution_time_ms, cost_ms) < 100 THEN '0-100ms' " +
            "    WHEN COALESCE(execution_time_ms, cost_ms) < 500 THEN '100-500ms' " +
            "    WHEN COALESCE(execution_time_ms, cost_ms) < 1000 THEN '500ms-1s' " +
            "    WHEN COALESCE(execution_time_ms, cost_ms) < 5000 THEN '1s-5s' " +
            "    WHEN COALESCE(execution_time_ms, cost_ms) < 10000 THEN '5s-10s' " +
            "    ELSE '10s+' " +
            "  END AS bucket, " +
            "  COUNT(*) AS count " +
            "FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND COALESCE(execution_time_ms, cost_ms) IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY bucket " +
            "ORDER BY MIN(COALESCE(execution_time_ms, cost_ms))")
    List<Map<String, Object>> getExecutionTimeDistribution(@Param("tenantId") Long tenantId,
                                                           @Param("startTime") Instant startTime,
                                                           @Param("endTime") Instant endTime);

    // ==================== List Queries ====================

    /**
     * Get slow queries.
     */
    @Select("SELECT * FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND COALESCE(execution_time_ms, cost_ms, 0) >= #{thresholdMs} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "ORDER BY COALESCE(execution_time_ms, cost_ms) DESC " +
            "LIMIT #{limit}")
    List<QueryAuditLog> getSlowQueries(@Param("tenantId") Long tenantId,
                                       @Param("thresholdMs") Integer thresholdMs,
                                       @Param("startTime") Instant startTime,
                                       @Param("endTime") Instant endTime,
                                       @Param("limit") Integer limit);

    /**
     * Get failed queries.
     */
    @Select("SELECT * FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND success = FALSE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "ORDER BY created_at DESC " +
            "LIMIT #{limit}")
    List<QueryAuditLog> getFailedQueries(@Param("tenantId") Long tenantId,
                                         @Param("startTime") Instant startTime,
                                         @Param("endTime") Instant endTime,
                                         @Param("limit") Integer limit);

    /**
     * Delete expired logs.
     */
    @Delete("DELETE FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at < #{cutoffTime}")
    int deleteExpiredLogs(@Param("tenantId") Long tenantId,
                          @Param("cutoffTime") Instant cutoffTime);

    /**
     * Paginated query with dynamic conditions.
     */
    @Select("""
        <script>
        SELECT * FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        <if test="userId != null">
          AND user_id = #{userId}
        </if>
        <if test="modelCode != null and modelCode != ''">
          AND model_code = #{modelCode}
        </if>
        <if test="queryType != null and queryType != ''">
          AND query_type = #{queryType}
        </if>
        <if test="success != null">
          AND success = #{success}
        </if>
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        ORDER BY created_at DESC
        </script>
        """)
    IPage<QueryAuditLog> selectPageList(
        Page<?> page,
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId,
        @Param("modelCode") String modelCode,
        @Param("queryType") String queryType,
        @Param("success") Boolean success,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    /**
     * Count by dynamic conditions.
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        <if test="userId != null">
          AND user_id = #{userId}
        </if>
        <if test="modelCode != null and modelCode != ''">
          AND model_code = #{modelCode}
        </if>
        <if test="queryType != null and queryType != ''">
          AND query_type = #{queryType}
        </if>
        <if test="success != null">
          AND success = #{success}
        </if>
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        </script>
        """)
    long countByConditions(
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId,
        @Param("modelCode") String modelCode,
        @Param("queryType") String queryType,
        @Param("success") Boolean success,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    /**
     * Find by user ID with time range.
     */
    @Select("""
        <script>
        SELECT * FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        AND user_id = #{userId}
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        ORDER BY created_at DESC
        LIMIT 500
        </script>
        """)
    List<QueryAuditLog> findByUserId(
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    /**
     * Find by model code with time range.
     */
    @Select("""
        <script>
        SELECT * FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        AND model_code = #{modelCode}
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        ORDER BY created_at DESC
        LIMIT 500
        </script>
        """)
    List<QueryAuditLog> findByModelCode(
        @Param("tenantId") Long tenantId,
        @Param("modelCode") String modelCode,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    /**
     * Find failed queries with time range.
     */
    @Select("""
        <script>
        SELECT * FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        AND success = FALSE
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        ORDER BY created_at DESC
        LIMIT 500
        </script>
        """)
    List<QueryAuditLog> findFailedQueries(
        @Param("tenantId") Long tenantId,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    /**
     * Count recent queries by user (for frequency detection).
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND created_at >= #{startTime}")
    long countRecentQueriesByUser(
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId,
        @Param("startTime") Instant startTime
    );

    /**
     * Find slow queries with time range.
     */
    @Select("""
        <script>
        SELECT * FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        AND COALESCE(execution_time_ms, cost_ms, 0) &gt;= #{thresholdMs}
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        ORDER BY COALESCE(execution_time_ms, cost_ms) DESC
        LIMIT 500
        </script>
        """)
    List<QueryAuditLog> findSlowQueries(
        @Param("tenantId") Long tenantId,
        @Param("thresholdMs") long thresholdMs,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    // ==================== Per-User Aggregate Queries ====================

    /**
     * Calculate average execution time for a specific user.
     */
    @Select("SELECT AVG(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Double calculateUserAverageExecutionTime(@Param("tenantId") Long tenantId,
                                             @Param("userId") Long userId,
                                             @Param("startTime") Instant startTime,
                                             @Param("endTime") Instant endTime);

    /**
     * Get max execution time for a specific user.
     */
    @Select("SELECT MAX(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Integer getUserMaxExecutionTime(@Param("tenantId") Long tenantId,
                                    @Param("userId") Long userId,
                                    @Param("startTime") Instant startTime,
                                    @Param("endTime") Instant endTime);

    /**
     * Get min execution time for a specific user.
     */
    @Select("SELECT MIN(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND COALESCE(execution_time_ms, cost_ms) IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Integer getUserMinExecutionTime(@Param("tenantId") Long tenantId,
                                    @Param("userId") Long userId,
                                    @Param("startTime") Instant startTime,
                                    @Param("endTime") Instant endTime);

    /**
     * Count successful queries for a user.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND success = TRUE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countUserSuccessfulQueries(@Param("tenantId") Long tenantId,
                                    @Param("userId") Long userId,
                                    @Param("startTime") Instant startTime,
                                    @Param("endTime") Instant endTime);

    /**
     * Count failed queries for a user.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND success = FALSE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countUserFailedQueries(@Param("tenantId") Long tenantId,
                                @Param("userId") Long userId,
                                @Param("startTime") Instant startTime,
                                @Param("endTime") Instant endTime);

    /**
     * Count distinct models accessed by a user.
     */
    @Select("SELECT COUNT(DISTINCT model_code) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND model_code IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countUserAccessedModels(@Param("tenantId") Long tenantId,
                                 @Param("userId") Long userId,
                                 @Param("startTime") Instant startTime,
                                 @Param("endTime") Instant endTime);

    /**
     * Get last query time for a user.
     */
    @Select("SELECT MAX(created_at) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Instant getUserLastQueryTime(@Param("tenantId") Long tenantId,
                                 @Param("userId") Long userId,
                                 @Param("startTime") Instant startTime,
                                 @Param("endTime") Instant endTime);

    /**
     * Get most active hour for a user.
     */
    @Select("SELECT EXTRACT(HOUR FROM created_at)::int AS hour FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY EXTRACT(HOUR FROM created_at) " +
            "ORDER BY COUNT(*) DESC " +
            "LIMIT 1")
    Integer getUserMostActiveHour(@Param("tenantId") Long tenantId,
                                  @Param("userId") Long userId,
                                  @Param("startTime") Instant startTime,
                                  @Param("endTime") Instant endTime);

    /**
     * Count by query type for a user.
     */
    @Select("SELECT query_type, COUNT(*) AS count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND query_type IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY query_type " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countUserByQueryType(@Param("tenantId") Long tenantId,
                                                    @Param("userId") Long userId,
                                                    @Param("startTime") Instant startTime,
                                                    @Param("endTime") Instant endTime);

    /**
     * Count by model for a user.
     */
    @Select("SELECT model_code, COUNT(*) AS count FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND user_id = #{userId} " +
            "AND model_code IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY model_code " +
            "ORDER BY count DESC")
    List<Map<String, Object>> countUserByModel(@Param("tenantId") Long tenantId,
                                                @Param("userId") Long userId,
                                                @Param("startTime") Instant startTime,
                                                @Param("endTime") Instant endTime);

    // ==================== Per-Model Aggregate Queries ====================

    /**
     * Calculate average execution time for a specific model.
     */
    @Select("SELECT AVG(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Double calculateModelAverageExecutionTime(@Param("tenantId") Long tenantId,
                                              @Param("modelCode") String modelCode,
                                              @Param("startTime") Instant startTime,
                                              @Param("endTime") Instant endTime);

    /**
     * Get max execution time for a specific model.
     */
    @Select("SELECT MAX(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Integer getModelMaxExecutionTime(@Param("tenantId") Long tenantId,
                                     @Param("modelCode") String modelCode,
                                     @Param("startTime") Instant startTime,
                                     @Param("endTime") Instant endTime);

    /**
     * Get min execution time for a specific model.
     */
    @Select("SELECT MIN(COALESCE(execution_time_ms, cost_ms)) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND COALESCE(execution_time_ms, cost_ms) IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Integer getModelMinExecutionTime(@Param("tenantId") Long tenantId,
                                     @Param("modelCode") String modelCode,
                                     @Param("startTime") Instant startTime,
                                     @Param("endTime") Instant endTime);

    /**
     * Count successful queries for a model.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND success = TRUE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countModelSuccessfulQueries(@Param("tenantId") Long tenantId,
                                     @Param("modelCode") String modelCode,
                                     @Param("startTime") Instant startTime,
                                     @Param("endTime") Instant endTime);

    /**
     * Count failed queries for a model.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND success = FALSE " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countModelFailedQueries(@Param("tenantId") Long tenantId,
                                 @Param("modelCode") String modelCode,
                                 @Param("startTime") Instant startTime,
                                 @Param("endTime") Instant endTime);

    /**
     * Count distinct users accessing a model.
     */
    @Select("SELECT COUNT(DISTINCT user_id) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND user_id IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Long countModelAccessUsers(@Param("tenantId") Long tenantId,
                               @Param("modelCode") String modelCode,
                               @Param("startTime") Instant startTime,
                               @Param("endTime") Instant endTime);

    /**
     * Get most active hour for a model.
     */
    @Select("SELECT EXTRACT(HOUR FROM created_at)::int AS hour FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND model_code = #{modelCode} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY EXTRACT(HOUR FROM created_at) " +
            "ORDER BY COUNT(*) DESC " +
            "LIMIT 1")
    Integer getModelMostActiveHour(@Param("tenantId") Long tenantId,
                                   @Param("modelCode") String modelCode,
                                   @Param("startTime") Instant startTime,
                                   @Param("endTime") Instant endTime);

    // ==================== Anomaly Detection Queries ====================

    /**
     * Find users with query frequency above threshold in a time window.
     */
    @Select("SELECT user_id, COUNT(*) AS query_count " +
            "FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY user_id " +
            "HAVING COUNT(*) >= #{threshold} " +
            "ORDER BY query_count DESC")
    List<Map<String, Object>> findHighFrequencyUsers(@Param("tenantId") Long tenantId,
                                                      @Param("startTime") Instant startTime,
                                                      @Param("endTime") Instant endTime,
                                                      @Param("threshold") Integer threshold);

    /**
     * Find queries with execution time above a multiplier of average.
     */
    @Select("SELECT * FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND COALESCE(execution_time_ms, cost_ms) > (" +
            "  SELECT AVG(COALESCE(execution_time_ms, cost_ms)) * #{multiplier} " +
            "  FROM ab_query_audit_log " +
            "  WHERE tenant_id = #{tenantId} " +
            "  AND created_at >= #{startTime} AND created_at <= #{endTime}" +
            ") " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "ORDER BY COALESCE(execution_time_ms, cost_ms) DESC " +
            "LIMIT #{limit}")
    List<QueryAuditLog> findAbnormallySlowQueries(@Param("tenantId") Long tenantId,
                                                   @Param("startTime") Instant startTime,
                                                   @Param("endTime") Instant endTime,
                                                   @Param("multiplier") Double multiplier,
                                                   @Param("limit") Integer limit);

    /**
     * Find users querying at unusual hours (e.g., late night / early morning).
     */
    @Select("SELECT user_id, EXTRACT(HOUR FROM created_at)::int AS hour, COUNT(*) AS count " +
            "FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "AND (EXTRACT(HOUR FROM created_at) < 6 OR EXTRACT(HOUR FROM created_at) >= 23) " +
            "GROUP BY user_id, EXTRACT(HOUR FROM created_at) " +
            "HAVING COUNT(*) >= #{threshold} " +
            "ORDER BY count DESC")
    List<Map<String, Object>> findOffHoursQueries(@Param("tenantId") Long tenantId,
                                                   @Param("startTime") Instant startTime,
                                                   @Param("endTime") Instant endTime,
                                                   @Param("threshold") Integer threshold);

    /**
     * Find queries returning unusually large result sets.
     */
    @Select("SELECT * FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND result_count > (" +
            "  SELECT AVG(result_count) * #{multiplier} " +
            "  FROM ab_query_audit_log " +
            "  WHERE tenant_id = #{tenantId} " +
            "  AND result_count IS NOT NULL AND result_count > 0 " +
            "  AND created_at >= #{startTime} AND created_at <= #{endTime}" +
            ") " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "ORDER BY result_count DESC " +
            "LIMIT #{limit}")
    List<QueryAuditLog> findLargeResultSetQueries(@Param("tenantId") Long tenantId,
                                                   @Param("startTime") Instant startTime,
                                                   @Param("endTime") Instant endTime,
                                                   @Param("multiplier") Double multiplier,
                                                   @Param("limit") Integer limit);

    // ==================== Archive / Export Queries ====================

    /**
     * Count logs before a given date for archiving.
     */
    @Select("SELECT COUNT(*) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at < #{archiveBefore}")
    int countLogsBeforeDate(@Param("tenantId") Long tenantId,
                            @Param("archiveBefore") Instant archiveBefore);

    /**
     * Fetch a batch of logs for export (paginated).
     */
    @Select("""
        <script>
        SELECT * FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        <if test="success != null">
          AND success = #{success}
        </if>
        <if test="modelCode != null and modelCode != ''">
          AND model_code = #{modelCode}
        </if>
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<QueryAuditLog> fetchBatchForExport(
        @Param("tenantId") Long tenantId,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime,
        @Param("success") Boolean success,
        @Param("modelCode") String modelCode,
        @Param("limit") Integer limit,
        @Param("offset") Integer offset
    );

    /**
     * Count total records for export.
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_query_audit_log
        WHERE tenant_id = #{tenantId}
        <if test="startTime != null">
          AND created_at &gt;= #{startTime}
        </if>
        <if test="endTime != null">
          AND created_at &lt;= #{endTime}
        </if>
        <if test="success != null">
          AND success = #{success}
        </if>
        <if test="modelCode != null and modelCode != ''">
          AND model_code = #{modelCode}
        </if>
        </script>
        """)
    long countForExport(
        @Param("tenantId") Long tenantId,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime,
        @Param("success") Boolean success,
        @Param("modelCode") String modelCode
    );

    // ==================== Performance Trend Queries ====================

    /**
     * Get performance trend by hour.
     */
    @Select("SELECT " +
            "  date_trunc('hour', created_at) AS ts, " +
            "  COUNT(*) AS query_count, " +
            "  AVG(COALESCE(execution_time_ms, cost_ms)) AS avg_time, " +
            "  PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY COALESCE(execution_time_ms, cost_ms)) AS p95_time, " +
            "  CASE WHEN COUNT(*) > 0 THEN COUNT(*) FILTER (WHERE cache_hit = TRUE)::float / COUNT(*) * 100 ELSE 0 END AS cache_hit_rate, " +
            "  CASE WHEN COUNT(*) > 0 THEN COUNT(*) FILTER (WHERE success = FALSE)::float / COUNT(*) * 100 ELSE 0 END AS error_rate " +
            "FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime} " +
            "GROUP BY date_trunc('hour', created_at) " +
            "ORDER BY ts")
    List<Map<String, Object>> getPerformanceTrendByHour(@Param("tenantId") Long tenantId,
                                                        @Param("startTime") Instant startTime,
                                                        @Param("endTime") Instant endTime);

    /**
     * Get average permission check time.
     */
    @Select("SELECT AVG(permission_check_time_ms) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND permission_check_time_ms IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Double calculateAveragePermissionCheckTime(@Param("tenantId") Long tenantId,
                                               @Param("startTime") Instant startTime,
                                               @Param("endTime") Instant endTime);

    /**
     * Get average security validation time.
     */
    @Select("SELECT AVG(security_validation_time_ms) FROM ab_query_audit_log " +
            "WHERE tenant_id = #{tenantId} " +
            "AND security_validation_time_ms IS NOT NULL " +
            "AND created_at >= #{startTime} " +
            "AND created_at <= #{endTime}")
    Double calculateAverageSecurityValidationTime(@Param("tenantId") Long tenantId,
                                                   @Param("startTime") Instant startTime,
                                                   @Param("endTime") Instant endTime);
}
