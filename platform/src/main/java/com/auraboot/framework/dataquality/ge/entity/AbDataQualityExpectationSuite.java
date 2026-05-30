package com.auraboot.framework.dataquality.ge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Persisted Great Expectations suite.
 *
 * <p>Backed by table {@code ab_dataquality_expectation_suite}.
 * The {@code expectations_json} column holds the raw GE expectations array;
 * {@link com.auraboot.framework.dataquality.ge.ExpectationsParser} is responsible
 * for parsing and validating it at runtime.
 */
@Data
@TableName("ab_dataquality_expectation_suite")
public class AbDataQualityExpectationSuite {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** ULID, 32 chars. External-stable identifier. */
    private String pid;

    private Long tenantId;

    /** Human-readable suite name, unique per tenant. */
    private String suiteName;

    /** Target table or view name. White-listed before use in SQL. */
    private String datasetName;

    /**
     * Raw GE expectations JSON array.
     * Stored as JSONB; accessed via Jackson during validation.
     */
    private String expectationsJson;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Boolean deletedFlag;
}
