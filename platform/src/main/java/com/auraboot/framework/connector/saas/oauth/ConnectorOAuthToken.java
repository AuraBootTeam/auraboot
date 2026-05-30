package com.auraboot.framework.connector.saas.oauth;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Persisted OAuth2 token row for one {@code (tenant_id, vendor)} connector.
 * PRD 18 §B.3.2.
 *
 * <p>{@code accessToken} + {@code refreshToken} carry the
 * {@code ENC:}-prefixed ciphertext from
 * {@link com.auraboot.framework.common.crypto.FieldEncryptionService} — never
 * plaintext at rest, even if the config-level encryption is disabled in dev.
 *
 * <p>Single row per {@code (tenant_id, vendor)} enforced by the unique
 * index in {@code schema.sql} — UPSERT semantics on refresh.
 */
@Data
@TableName("connector_oauth_token")
public class ConnectorOAuthToken {

    /** {@code id BIGINT PRIMARY KEY} (not BIGSERIAL) → snowflake. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private String vendor;

    /** Encrypted access token; never the bare bearer. */
    private String accessToken;

    /** Encrypted refresh token; null for API-key flows that never refresh. */
    private String refreshToken;

    private Instant expiresAt;

    /** Comma-separated; informational only. */
    private String scopes;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
