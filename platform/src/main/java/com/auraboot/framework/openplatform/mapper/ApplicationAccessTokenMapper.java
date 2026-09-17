package com.auraboot.framework.openplatform.mapper;

import com.auraboot.framework.openplatform.entity.ApplicationAccessToken;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ApplicationAccessTokenMapper extends BaseMapper<ApplicationAccessToken> {
    @Select("""
            SELECT * FROM ab_application_access_token
            WHERE token_hash = #{tokenHash}
              AND audience = #{audience}
              AND revoked_at IS NULL
              AND expires_at > #{now}
            LIMIT 1
            """)
    ApplicationAccessToken findActive(@Param("tokenHash") String tokenHash,
                                      @Param("audience") String audience,
                                      @Param("now") Instant now);

    @Update("""
            UPDATE ab_application_access_token
            SET revoked_at = #{now}
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId}
              AND revoked_at IS NULL
            """)
    int revokeInstallationTokens(@Param("tenantId") Long tenantId,
                                 @Param("installationId") Long installationId,
                                 @Param("now") Instant now);

    @Update("""
            UPDATE ab_application_access_token
            SET revoked_at = #{now}
            WHERE tenant_id = #{tenantId} AND credential_id = #{credentialId}
              AND revoked_at IS NULL
            """)
    int revokeCredentialTokens(@Param("tenantId") Long tenantId,
                               @Param("credentialId") Long credentialId,
                               @Param("now") Instant now);
}
