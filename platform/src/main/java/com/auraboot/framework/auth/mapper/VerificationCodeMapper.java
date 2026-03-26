package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.entity.VerificationCode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for {@link VerificationCode}.
 *
 * @since 7.0.0
 */
@Mapper
public interface VerificationCodeMapper extends BaseMapper<VerificationCode> {

    /**
     * Find the latest unverified code for a given target and type.
     */
    @Select("SELECT * FROM ab_verification_code "
            + "WHERE target = #{target} AND type = #{type} AND verified = FALSE "
            + "ORDER BY created_at DESC LIMIT 1")
    VerificationCode findLatestUnverified(@Param("target") String target, @Param("type") String type);

    /**
     * Count codes sent from a specific IP in the last hour.
     */
    @Select("SELECT COUNT(*) FROM ab_verification_code "
            + "WHERE ip_address = #{ipAddress} AND created_at > NOW() - INTERVAL '1 hour'")
    int countByIpInLastHour(@Param("ipAddress") String ipAddress);

    /**
     * Find the latest unused code for a given target (regardless of type).
     * Used by dev-only endpoint to retrieve verification codes for testing.
     */
    @Select("SELECT * FROM ab_verification_code "
            + "WHERE target = #{target} AND verified = FALSE "
            + "ORDER BY created_at DESC LIMIT 1")
    VerificationCode findLatestByTarget(@Param("target") String target);
}
