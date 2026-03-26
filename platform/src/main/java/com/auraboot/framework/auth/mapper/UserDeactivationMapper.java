package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.entity.UserDeactivation;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for ab_user_deactivation table.
 * <p>
 * This table has NO tenant_id column — all methods use
 * {@code @InterceptorIgnore(tenantLine = "true")} to bypass the tenant interceptor.
 * The table is also registered in MybatisPlusConfig's ignore list for BaseMapper methods.
 *
 * @since 7.1.0
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface UserDeactivationMapper extends BaseMapper<UserDeactivation> {

    /**
     * Find active deactivation request (PENDING or COOLING_OFF) for a user.
     * The partial unique index guarantees at most one row.
     */
    @Select("""
            SELECT * FROM ab_user_deactivation
            WHERE user_id = #{userId}
              AND status IN ('pending', 'cooling_off')
            LIMIT 1
            """)
    UserDeactivation findActiveByUserId(@Param("userId") Long userId);

    /**
     * Find all COOLING_OFF records whose cooling-off period has expired.
     * Used by the scheduler to process pending anonymizations.
     */
    @Select("""
            SELECT * FROM ab_user_deactivation
            WHERE status = 'cooling_off'
              AND cooling_off_until < NOW()
            """)
    List<UserDeactivation> findExpiredCoolingOff();

    /**
     * Cancel an active deactivation request.
     */
    @Update("""
            UPDATE ab_user_deactivation
            SET status = 'cancelled', cancelled_at = NOW()
            WHERE user_id = #{userId}
              AND status IN ('pending', 'cooling_off')
            """)
    int cancelByUserId(@Param("userId") Long userId);
}
