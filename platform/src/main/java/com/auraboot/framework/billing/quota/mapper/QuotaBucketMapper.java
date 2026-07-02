package com.auraboot.framework.billing.quota.mapper;

import com.auraboot.framework.billing.quota.model.QuotaBucket;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * MyBatis-Plus mapper for {@link QuotaBucket}.
 *
 * <p>{@code @InterceptorIgnore(tenantLine = "true")}: quota bucket is a
 * platform-global table (not per-tenant) — bypasses the multi-tenant interceptor.
 *
 * <p>Manual CAS methods are provided because the platform-wide MyBatis-Plus
 * configuration does not register {@code OptimisticLockerInnerInterceptor}.
 * The {@code casXxx} methods append {@code AND version = :versionBefore} and
 * increment the version atomically in SQL — returning the affected-row count
 * (1 on success, 0 on version mismatch → caller must retry).
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface QuotaBucketMapper extends BaseMapper<QuotaBucket> {

    /**
     * CAS reserve: {@code reserved_amount += delta}, only when {@code version = versionBefore}
     * AND the bucket still has enough headroom ({@code total - used - reserved >= delta}).
     * Increments version on success. The balance predicate makes the hard-limit invariant
     * atomic at the DB, so concurrent authorize calls cannot over-reserve past the total even
     * when both pass the service-layer pre-check (TOCTOU).
     *
     * @return 1 if updated, 0 if version mismatch OR insufficient headroom (caller
     *         re-reads to distinguish retry-vs-insufficient)
     */
    @Update("UPDATE ab_billing_quota_bucket " +
            "SET reserved_amount = reserved_amount + #{delta}, " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{versionBefore} " +
            "  AND (total_amount - used_amount - reserved_amount) >= #{delta}")
    int casAddReserved(@Param("id") Long id,
                       @Param("delta") BigDecimal delta,
                       @Param("versionBefore") Long versionBefore);

    /**
     * CAS commit: {@code used_amount += usedDelta}, {@code reserved_amount -= reservedDelta},
     * only when {@code version = versionBefore}.
     * Increments version on success.
     *
     * @return 1 if updated, 0 if version mismatch
     */
    @Update("UPDATE ab_billing_quota_bucket " +
            "SET used_amount = GREATEST(0, used_amount + #{usedDelta}), " +
            "    reserved_amount = GREATEST(0, reserved_amount - #{reservedDelta}), " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{versionBefore}")
    int casCommit(@Param("id") Long id,
                  @Param("usedDelta") BigDecimal usedDelta,
                  @Param("reservedDelta") BigDecimal reservedDelta,
                  @Param("versionBefore") Long versionBefore);

    /**
     * CAS release: {@code reserved_amount -= delta}, only when {@code version = versionBefore}.
     * Increments version on success.
     *
     * @return 1 if updated, 0 if version mismatch
     */
    @Update("UPDATE ab_billing_quota_bucket " +
            "SET reserved_amount = GREATEST(0, reserved_amount - #{delta}), " +
            "    version = version + 1, " +
            "    updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{versionBefore}")
    int casSubtractReserved(@Param("id") Long id,
                             @Param("delta") BigDecimal delta,
                             @Param("versionBefore") Long versionBefore);
}
