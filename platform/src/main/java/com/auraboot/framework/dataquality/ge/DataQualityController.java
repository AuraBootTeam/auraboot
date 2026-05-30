package com.auraboot.framework.dataquality.ge;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityExpectationSuite;
import com.auraboot.framework.dataquality.ge.entity.AbDataQualityValidationRun;
import com.auraboot.framework.dataquality.ge.mapper.AbDataQualityExpectationSuiteMapper;
import com.auraboot.framework.dataquality.ge.mapper.AbDataQualityValidationRunMapper;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST surface for Great Expectations integration.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/dataquality/expectations} — create an expectation suite</li>
 *   <li>{@code POST /api/dataquality/expectations/{suitePid}/run} — execute validation</li>
 *   <li>{@code GET  /api/dataquality/expectations/{suitePid}/runs} — list past runs</li>
 * </ul>
 *
 * <p>Permission: {@code meta.chatbi.use} (temporary; dedicated
 * {@code meta.dataquality.*} permission codes are planned as a follow-up).
 */
@Slf4j
@RestController
@RequestMapping("/api/dataquality/expectations")
@RequiredArgsConstructor
public class DataQualityController {

    private final AbDataQualityExpectationSuiteMapper suiteMapper;
    private final AbDataQualityValidationRunMapper runMapper;
    private final GreatExpectationsValidator validator;

    /**
     * Create a new expectation suite.
     *
     * <p>Request body fields:
     * <ul>
     *   <li>{@code suiteName}       — human-readable name, unique per tenant</li>
     *   <li>{@code datasetName}     — target table / view name (identifier-validated)</li>
     *   <li>{@code expectationsJson} — GE expectations JSON array</li>
     * </ul>
     *
     * @return JSON with {@code ok: true} and the new {@code suitePid}
     */
    @PostMapping
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public Map<String, Object> createSuite(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.get().getTenantId();

        String suiteName = requireString(body, "suiteName");
        String datasetName = requireString(body, "datasetName");
        String expectationsJson = requireString(body, "expectationsJson");

        // Validate identifier up-front before persisting.
        GreatExpectationsValidator.validateIdentifier(datasetName, "datasetName");

        AbDataQualityExpectationSuite suite = new AbDataQualityExpectationSuite();
        suite.setPid(UlidGenerator.generate());
        suite.setTenantId(tenantId);
        suite.setSuiteName(suiteName);
        suite.setDatasetName(datasetName);
        suite.setExpectationsJson(expectationsJson);

        suiteMapper.insert(suite);

        log.info("Created expectation suite: tenant={} suite={} dataset={}", tenantId, suite.getPid(), datasetName);
        return Map.of("ok", true, "suitePid", suite.getPid());
    }

    /**
     * Execute all expectations in the suite and record a validation run.
     *
     * @param suitePid PID of the expectation suite
     * @return run summary with pass/fail counts and pid
     */
    @PostMapping("/{suitePid}/run")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public Map<String, Object> runValidation(@PathVariable("suitePid") String suitePid) {
        Long tenantId = MetaContext.get().getTenantId();

        AbDataQualityExpectationSuite suite = suiteMapper.findByPid(tenantId, suitePid);
        if (suite == null) {
            throw new IllegalArgumentException("Expectation suite not found: " + suitePid);
        }

        AbDataQualityValidationRun run = validator.validate(tenantId, suite);

        return Map.of(
                "ok", true,
                "runPid", run.getPid(),
                "totalExpectations", run.getTotalExpectations(),
                "passed", run.getPassed(),
                "failed", run.getFailed()
        );
    }

    /**
     * List the last 50 validation runs for a suite (most recent first).
     *
     * @param suitePid PID of the expectation suite
     * @return list of run summaries
     */
    @GetMapping("/{suitePid}/runs")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public List<AbDataQualityValidationRun> listRuns(@PathVariable("suitePid") String suitePid) {
        Long tenantId = MetaContext.get().getTenantId();
        return runMapper.listBySuite(tenantId, suitePid);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String requireString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return v.toString();
    }
}
