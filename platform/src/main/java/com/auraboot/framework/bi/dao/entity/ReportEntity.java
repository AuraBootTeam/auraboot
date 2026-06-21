package com.auraboot.framework.bi.dao.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * First-class low-code report definition (Phase 4 "report storage graduation", slice 1).
 *
 * <p>Maps the {@code ab_report} table. PURELY ADDITIVE: nothing reads or writes this table
 * yet — the report designer still persists via {@code ab_page_schema (kind:'list')} +
 * {@code extension.reportDsl}. This entity is the storage destination for the eventual
 * one-time migration off the page shell.
 *
 * <p>The whole {@code ReportDsl} is held in {@link #dsl} as a single jsonb blob (1:1 with
 * today's {@code extension.reportDsl}), so the future data migration is a trivial copy.
 *
 * <p>{@code dsl} is a {@code String} mapped onto a {@code jsonb} column, so it MUST declare
 * the canonical {@link JsonbStringTypeHandler}: without it the MyBatis-Plus {@code BaseMapper}
 * auto-insert binds the String as {@code varchar} and PostgreSQL throws
 * "column dsl is of type jsonb but expression is of type character varying"
 * (see {@code scripts/check-jsonb-typehandler.sh}). {@code autoResultMap = true} makes the
 * read side route the column back through the same handler (object in -&gt; object out).
 */
@Data
@TableName(value = "ab_report", autoResultMap = true)
public class ReportEntity {

    /** App-assigned snowflake id (DDL: {@code id BIGINT PRIMARY KEY}, not a serial). */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** Stable external report id (ULID, 26 chars). Replaces page.pid as reportId. */
    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /** Tenant-unique business code (uk_ab_report_tenant_code). */
    @TableField("code")
    private String code;

    @TableField("title")
    private String title;

    /** Render/layout profile; defaults to {@code paged-media}. */
    @TableField("profile")
    private String profile;

    /**
     * The whole ReportDsl as one jsonb blob (1:1 with {@code extension.reportDsl}).
     * String -&gt; jsonb requires the typeHandler (see class javadoc).
     */
    @TableField(value = "dsl", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String dsl;

    /** Plain VARCHAR status (e.g. draft/published) — no DB enum, avoids enum drift. */
    @TableField("status")
    private String status;

    @TableField("version")
    private Integer version;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * Logical delete flag (DDL: {@code SMALLINT NOT NULL DEFAULT 0}; 0 = live, 1 = deleted).
     *
     * <p>Managed explicitly by {@code ReportStorageService}, NOT via the platform-wide
     * MyBatis-Plus logic-delete. The global config ({@code logic-delete-field: deletedFlag},
     * {@code logic-delete-value: true}/{@code logic-not-delete-value: false}) is built for the
     * platform's BOOLEAN {@code deleted_flag} columns and would inject {@code deleted_flag = false}
     * into every query — which a SMALLINT column rejects with
     * "operator does not exist: smallint = boolean". Naming the Java property {@code deletedState}
     * (instead of {@code deletedFlag}) opts this entity out of that global interceptor while still
     * mapping to the {@code deleted_flag} column; soft-delete is filtered explicitly in the service.
     */
    @TableField("deleted_flag")
    private Integer deletedState;
}
