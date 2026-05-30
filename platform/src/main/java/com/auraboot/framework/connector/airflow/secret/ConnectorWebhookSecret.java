package com.auraboot.framework.connector.airflow.secret;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Per-(tenant, connection_name) Airflow webhook shared secret. PRD 18-C §C.3.3
 * production refinement (W5-FU-5).
 *
 * <p>{@code sharedSecret} carries the {@code ENC:}-prefixed ciphertext from
 * {@link com.auraboot.framework.common.crypto.FieldEncryptionService} — never
 * the bare key at rest.
 *
 * <p>Rotation semantics owned by
 * {@link WebhookSecretService}: one ACTIVE row per
 * {@code (tenant_id, connection_name)} (partial unique index) plus optional
 * INACTIVE rows tracked for up to a 5-minute grace window after
 * {@code rotated_at} so in-flight Airflow tasks signed by the previous secret
 * still land.
 */
@Data
@TableName("connector_webhook_secret")
public class ConnectorWebhookSecret {

    /** {@code id BIGINT PRIMARY KEY} (not BIGSERIAL) → snowflake. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private String connectionName;

    /** ENC: ciphertext. */
    private String sharedSecret;

    /** Always {@code HMAC-SHA256} in v0.1; reserved for forward-compat. */
    private String algorithm;

    private Boolean active;

    private Instant rotatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
