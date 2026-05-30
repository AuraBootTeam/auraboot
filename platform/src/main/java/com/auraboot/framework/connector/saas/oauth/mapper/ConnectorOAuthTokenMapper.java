package com.auraboot.framework.connector.saas.oauth.mapper;

import com.auraboot.framework.connector.saas.oauth.ConnectorOAuthToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface ConnectorOAuthTokenMapper extends BaseMapper<ConnectorOAuthToken> {

    @Select("SELECT * FROM connector_oauth_token "
          + "WHERE tenant_id = #{tenantId} AND vendor = #{vendor} LIMIT 1")
    ConnectorOAuthToken findByTenantAndVendor(@Param("tenantId") Long tenantId,
                                              @Param("vendor") String vendor);

    /**
     * Atomically replace the access token + refresh token + expiry. Touches
     * {@code updated_at}; {@code created_at} stays at the initial-grant value.
     * Returns the number of rows updated.
     */
    @Update("UPDATE connector_oauth_token "
          + "SET access_token = #{accessToken}, refresh_token = #{refreshToken}, "
          + "    expires_at = #{expiresAt}, scopes = #{scopes}, "
          + "    updated_at = NOW() "
          + "WHERE tenant_id = #{tenantId} AND vendor = #{vendor}")
    int updateTokens(@Param("tenantId") Long tenantId,
                     @Param("vendor") String vendor,
                     @Param("accessToken") String accessToken,
                     @Param("refreshToken") String refreshToken,
                     @Param("expiresAt") Instant expiresAt,
                     @Param("scopes") String scopes);

    @Update("DELETE FROM connector_oauth_token "
          + "WHERE tenant_id = #{tenantId} AND vendor = #{vendor}")
    int deleteByTenantAndVendor(@Param("tenantId") Long tenantId,
                                @Param("vendor") String vendor);
}
