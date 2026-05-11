package com.auraboot.framework.email.mapper;

import com.auraboot.framework.email.model.EmailAccount;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for {@link EmailAccount}.
 *
 * @since 6.5.0
 */
@Mapper
public interface EmailAccountMapper extends BaseMapper<EmailAccount> {

    /**
     * Finds all active (non-deleted, status=active) accounts for a tenant.
     */
    @Select("""
        SELECT * FROM ab_email_account
        WHERE tenant_id = #{tenantId}
          AND status = 'active'
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY created_at ASC
        """)
    List<EmailAccount> findAllActive(@Param("tenantId") Long tenantId);

    /**
     * Finds all active accounts across all tenants (used by the background sync job).
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM ab_email_account
        WHERE status = 'active'
          AND sync_mode = 'auto'
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY tenant_id, id
        """)
    List<EmailAccount> findAllActiveGlobal();

    /**
     * Persists the Gmail history token / sync cursor as a JSONB blob.
     */
    @Update("""
        UPDATE ab_email_account
        SET sync_state = #{syncState}::jsonb,
            updated_at = NOW()
        WHERE id = #{id}
        """)
    int updateSyncState(@Param("id") Long id, @Param("syncState") String syncState);

    /**
     * Refreshes the OAuth2 access token after a successful token-refresh flow.
     */
    @Update("""
        UPDATE ab_email_account
        SET access_token    = #{accessToken},
            token_expires_at = #{tokenExpiresAt},
            updated_at      = NOW()
        WHERE id = #{id}
        """)
    int updateToken(@Param("id") Long id,
                    @Param("accessToken") String accessToken,
                    @Param("tokenExpiresAt") Instant tokenExpiresAt);

    /**
     * Clears OAuth2 tokens and sets status to 'revoked' when disconnecting an account.
     *
     * <p>Uses explicit SQL because {@code updateById} skips null fields by default.
     */
    @Update("""
        UPDATE ab_email_account
        SET access_token     = NULL,
            refresh_token    = NULL,
            token_expires_at = NULL,
            status           = 'revoked',
            updated_at       = NOW()
        WHERE id = #{id}
        """)
    int revokeTokens(@Param("id") Long id);
}
