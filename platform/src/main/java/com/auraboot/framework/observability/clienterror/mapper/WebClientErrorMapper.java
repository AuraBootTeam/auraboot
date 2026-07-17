package com.auraboot.framework.observability.clienterror.mapper;

import com.auraboot.framework.observability.clienterror.WebClientError;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link WebClientError}. Reads are tenant-scoped by the caller.
 */
@Mapper
public interface WebClientErrorMapper extends BaseMapper<WebClientError> {

    @Select("""
        SELECT * FROM ab_web_client_error
        WHERE tenant_id = #{tenantId}
        ORDER BY created_at DESC
        LIMIT #{pageSize} OFFSET #{offset}
        """)
    List<WebClientError> pageByTenant(@Param("tenantId") Long tenantId,
                                      @Param("pageSize") int pageSize,
                                      @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ab_web_client_error WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") Long tenantId);
}
