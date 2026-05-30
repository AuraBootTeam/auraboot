package com.auraboot.framework.dataquality.ge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Persisted result of one Great Expectations validation run.
 *
 * <p>Backed by table {@code ab_dataquality_validation_run}.
 * {@code results_json} is a JSONB array of per-expectation pass/fail entries
 * with the actual observed value for diagnosability.
 */
@Data
@TableName("ab_dataquality_validation_run")
public class AbDataQualityValidationRun {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** ULID, 32 chars. External-stable identifier. */
    private String pid;

    private Long tenantId;

    /** References {@link AbDataQualityExpectationSuite#getPid()}. */
    private String suitePid;

    /** Dataset name copied from the suite at run time (denormalized for query convenience). */
    private String datasetName;

    private Integer totalExpectations;

    private Integer passed;

    private Integer failed;

    /**
     * Per-expectation results array.
     * Each element: {@code {"expectation_type":…, "column":…, "passed":true/false, "actualValue":…}}.
     */
    private String resultsJson;

    private Instant startedAt;

    private Instant finishedAt;

    @TableLogic
    private Boolean deletedFlag;
}
