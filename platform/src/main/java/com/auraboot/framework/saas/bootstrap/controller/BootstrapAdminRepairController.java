package com.auraboot.framework.saas.bootstrap.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.saas.bootstrap.BootstrapRepairService;
import com.auraboot.framework.saas.bootstrap.BootstrapRepairService.RepairOptions;
import com.auraboot.framework.saas.bootstrap.RepairReport;
import com.auraboot.framework.saas.bootstrap.RepairStepResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only bootstrap repair endpoint (Phase 2.4 of the bootstrap-unified plan).
 *
 * <p>Unlike {@link BootstrapController#setup} (the public wizard endpoint that
 * fails when the system is already initialized), this endpoint runs the
 * idempotent {@link BootstrapRepairService} and is safe to call any number of
 * times — useful for restoring a damaged installation, partial migrations, or
 * post-incident reseeding without dropping the database.
 *
 * <p>Permission gating:
 * <ul>
 *   <li>Path is under {@code /api/admin/bootstrap/**} which is registered
 *       with {@code AdminRoleInterceptor.PLATFORM_ADMIN_PATHS} — so only the
 *       {@code platform_admin} role can reach this controller.</li>
 *   <li>Method-level {@link RequirePermission} also enforces
 *       {@code sys.bootstrap.repair} for the fine-grained permission code
 *       contract (registered in {@code default-bootstrap.json}).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bootstrap")
@RequiredArgsConstructor
@Tag(name = "Admin Bootstrap Repair",
        description = "Admin-only endpoint to re-align the 9 bootstrap invariants idempotently")
public class BootstrapAdminRepairController {

    public static final String STEP_ALL = "all";
    static final String ERR_UNKNOWN_STEP = "unknown step";

    private final BootstrapRepairService bootstrapRepairService;

    @PostMapping("/repair")
    @Operation(summary = "Run bootstrap repair (single step or all 9 invariants)")
    @RequirePermission("sys.bootstrap.repair")
    public ResponseEntity<ApiResponse<?>> repair(@RequestBody RepairRequest req) {
        String step = req == null || req.step == null ? STEP_ALL : req.step.trim();
        RepairOptions opts = req == null ? defaults() : effectiveOpts(req);

        if (STEP_ALL.equalsIgnoreCase(step)) {
            RepairReport report = bootstrapRepairService.repairAll(opts);
            log.info("/api/admin/bootstrap/repair step=all created={} repaired={} present={} anyError={}",
                    report.totalCreated(), report.totalRepaired(), report.totalPresent(), report.anyError());
            return ResponseEntity.ok(ApiResponse.success(report));
        }

        if (!BootstrapRepairService.ORDERED_STEPS.contains(step)) {
            log.warn("/api/admin/bootstrap/repair invalid step={}", step);
            return ResponseEntity
                    .badRequest()
                    .body(ApiResponse.error(ERR_UNKNOWN_STEP + ": " + step));
        }

        RepairStepResult result = bootstrapRepairService.repair(step, opts);
        log.info("/api/admin/bootstrap/repair step={} status={}", step, result.status());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private RepairOptions effectiveOpts(RepairRequest req) {
        RepairOptions d = defaults();
        return RepairOptions.of(
                blank(req.adminEmail) ? d.adminEmail() : req.adminEmail,
                blank(req.adminPassword) ? d.adminPassword() : req.adminPassword,
                blank(req.adminDisplayName) ? d.adminDisplayName() : req.adminDisplayName,
                blank(req.companyName) ? d.companyName() : req.companyName,
                blank(req.systemMode) ? d.systemMode() : req.systemMode,
                blank(req.instanceUrl) ? d.instanceUrl() : req.instanceUrl);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static RepairOptions defaults() {
        return RepairOptions.of(
                "admin@auraboot.com",
                "Test2026x",
                "Admin",
                "AuraBoot Dev",
                "single",
                "http://localhost:6443");
    }

    /**
     * Request body for {@link BootstrapAdminRepairController#repair}.
     *
     * <p>{@code step} is required: either {@code "all"} (run the full pipeline)
     * or one of the canonical step names from {@link BootstrapRepairService#ORDERED_STEPS}.
     * Other fields override defaults for the underlying {@link RepairOptions}.
     */
    public static class RepairRequest {
        @NotBlank
        public String step;
        public String adminEmail;
        public String adminPassword;
        public String adminDisplayName;
        public String companyName;
        public String systemMode;
        public String instanceUrl;
    }
}
