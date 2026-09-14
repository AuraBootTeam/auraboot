package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OpenApiRateLimitMapper {
    @Select("""
            INSERT INTO ab_open_api_rate_window (installation_id, window_start, request_count)
            VALUES (#{installationId}, #{windowStart}, 1)
            ON CONFLICT (installation_id, window_start) DO UPDATE
            SET request_count = ab_open_api_rate_window.request_count + 1
            WHERE ab_open_api_rate_window.request_count < #{limit}
            RETURNING request_count
            """)
    Integer consume(@Param("installationId") Long installationId,
                    @Param("windowStart") Instant windowStart,
                    @Param("limit") int limit);
}
