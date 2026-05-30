package com.auraboot.framework.connector.airflow;

import com.auraboot.framework.tenant.typehandler.JsonStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Builder;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Persisted audit row for every inbound Airflow webhook call.
 *
 * <p>Two status values:
 * <ul>
 *   <li>{@code ACCEPTED} — HMAC verified, body valid. All fields populated,
 *       {@code payloadJson} stores the raw webhook body as JSONB so downstream
 *       analytics can read connector-specific fields without a schema change
 *       here.</li>
 *   <li>{@code REJECTED} — any verification failure. {@code dagId},
 *       {@code taskId}, {@code event}, and {@code payloadJson} are {@code null}
 *       to avoid persisting potentially malicious content from hostile
 *       senders. {@code errorCode} and {@code signatureDriftSeconds} give ops
 *       enough context to diagnose the attack pattern.</li>
 * </ul>
 *
 * <p>Table: {@code airflow_webhook_log}. PRD 18 §D + design doc
 * {@code 30-airflow-provider-design.md §2.4}.
 *
 * <p>Id is a snowflake assigned by MyBatis-Plus
 * {@link IdType#ASSIGN_ID} — NOT a database serial — consistent with every
 * other AuraBoot entity.
 */
@Data
@Builder
@TableName("airflow_webhook_log")
public class AirflowWebhookLog {

    /** Snowflake id — set by IdType.ASSIGN_ID, not BIGSERIAL. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** ULID, 32 chars. Stable public identifier. */
    private String pid;

    /** Owning tenant; nullable during single-tenant transition. */
    private Long tenantId;

    /** Value of the {@code X-AuraBoot-Webhook-Id} header. */
    private String webhookId;

    /** {@code airflow.task.completed} or similar; null for REJECTED rows. */
    private String event;

    /** DAG identifier from the webhook body; null for REJECTED rows. */
    private String dagId;

    /** Task identifier from the webhook body; null for REJECTED rows. */
    private String taskId;

    /** {@code ACCEPTED} or {@code REJECTED}. */
    private String status;

    /** HTTP status code returned to the caller (202, 400, 401, 404, 409). */
    private Integer httpStatus;

    /**
     * Error code for REJECTED rows
     * ({@code BAD_SIGNATURE}, {@code STALE_TIMESTAMP}, etc.); null for ACCEPTED.
     */
    private String errorCode;

    /**
     * Absolute difference {@code |now_epoch - t_epoch|} in seconds.
     * Available for REJECTED/STALE_TIMESTAMP rows to diagnose clock-skew
     * attacks; null when no parseable timestamp is present in the signature.
     */
    private Long signatureDriftSeconds;

    /**
     * Full webhook body as JSONB. Populated only for ACCEPTED rows.
     *
     * <p>REJECTED rows store {@code null} — persisting unverified body content
     * from potentially hostile senders would pollute the audit trail.
     *
     * <p>Stored as JSONB with {@link JsonStringTypeHandler} so MyBatis-Plus
     * maps the Java {@code String} to the Postgres {@code jsonb} column
     * ({@link JdbcType#OTHER}).
     */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonStringTypeHandler.class)
    private String payloadJson;

    /** Wall-clock time the webhook arrived; set to {@code NOW()} by the DB. */
    private Instant receivedAt;
}
