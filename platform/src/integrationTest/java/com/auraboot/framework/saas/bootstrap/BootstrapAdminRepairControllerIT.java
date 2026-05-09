package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.integration.IntegrationTestBase;
import com.auraboot.framework.saas.bootstrap.controller.BootstrapAdminRepairController;
import com.auraboot.framework.saas.bootstrap.controller.BootstrapAdminRepairController.RepairRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration tests for {@link BootstrapAdminRepairController} (Phase 2.4 of
 * the bootstrap-unified plan).
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code step="all"} on the in-process service → returns a non-error
 *       {@link RepairReport}, every step at least PRESENT after a prior
 *       {@code repairAll}.</li>
 *   <li>Single named step (e.g. {@code system_tenant}) → returns a single
 *       {@link RepairStepResult}.</li>
 *   <li>Unknown step → {@code 400} with the {@code unknown step} error message.</li>
 *   <li>HTTP path (no auth) → request is rejected by the
 *       {@code AdminRoleInterceptor} (response payload has the deny code) since
 *       {@code /api/admin/bootstrap/**} is registered as platform-admin-only.
 *       This proves the routing is wired and the path is gated even before the
 *       method-level {@code @RequirePermission("sys.bootstrap.repair")} runs.</li>
 * </ul>
 *
 * <p>The happy-path admin-token-bearing call is exercised end-to-end by the
 * Playwright contract suite ({@code 00-bootstrap.spec.ts}) which has a real
 * JWT against an isolated stack — we do not reproduce that here because the IT
 * harness does not run a JwtAuthenticationFilter chain.
 */
class BootstrapAdminRepairControllerIT extends IntegrationTestBase {

    @Autowired private BootstrapAdminRepairController controller;
    @Autowired private BootstrapRepairService repair;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("step=all on fresh DB → 200 + RepairReport, anyError=false")
    void repair_stepAll_returnsReport() {
        freshDb();
        RepairRequest req = new RepairRequest();
        req.step = BootstrapAdminRepairController.STEP_ALL;
        req.companyName = "IT-Repair-Endpoint-" + System.nanoTime();

        var resp = controller.repair(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Object body = resp.getBody().getData();
        assertThat(body).isInstanceOf(RepairReport.class);
        RepairReport report = (RepairReport) body;
        assertThat(report.steps()).hasSize(BootstrapRepairService.ORDERED_STEPS.size());
        // The first run on a fresh DB creates rows; second run via repair() must be all-PRESENT.
        var second = controller.repair(req);
        RepairReport secondReport = (RepairReport) second.getBody().getData();
        assertThat(secondReport.totalCreated())
                .as("idempotent: second call must not create new rows")
                .isZero();
    }

    @Test
    @DisplayName("step=system_tenant → 200 + single RepairStepResult")
    void repair_singleStep_returnsStepResult() {
        freshDb();
        // Pre-condition: invariants 1-2 must run first; system_tenant alone on a fresh DB
        // can succeed because it only depends on tenantService.findByName which is empty.
        RepairRequest req = new RepairRequest();
        req.step = BootstrapRepairService.STEP_SYSTEM_TENANT;

        var resp = controller.repair(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Object body = resp.getBody().getData();
        assertThat(body).isInstanceOf(RepairStepResult.class);
        RepairStepResult result = (RepairStepResult) body;
        assertThat(result.stepName()).isEqualTo(BootstrapRepairService.STEP_SYSTEM_TENANT);
        assertThat(result.status())
                .isIn(RepairStepResult.Status.CREATED, RepairStepResult.Status.PRESENT);
    }

    @Test
    @DisplayName("unknown step → 400 with 'unknown step' error message")
    void repair_unknownStep_returnsBadRequest() {
        RepairRequest req = new RepairRequest();
        req.step = "not_a_real_step";

        var resp = controller.repair(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        // ApiResponse.error returns msg in the body
        assertThat(resp.getBody().getMessage()).contains("unknown step");
    }

    @Test
    @DisplayName("HTTP /api/admin/bootstrap/repair without auth → AdminRoleInterceptor denies")
    void repair_httpWithoutAuth_isDeniedByAdminGuard() throws Exception {
        RepairRequest req = new RepairRequest();
        req.step = BootstrapAdminRepairController.STEP_ALL;

        // Without MetaContext the AdminRoleInterceptor short-circuits with its
        // documented deny code (409 in the JSON payload, HTTP 200 wrapper per
        // its writeDenied implementation). We assert the deny code lands in
        // the body so any future regression that would silently allow the call
        // is caught.
        var result = mockMvc.perform(post("/api/admin/bootstrap/repair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        // Either: (a) admin guard ran first and produced its deny-code 409, or
        //         (b) auth chain ran first and produced 401/403.
        // Both prove the endpoint is NOT publicly callable without admin context.
        int status = result.getResponse().getStatus();
        assertThat(status == 401 || status == 403 || status == 200)
                .as("unauthenticated request must not pass through silently — got status=%d body=%s",
                        status, responseBody)
                .isTrue();
        if (status == 200) {
            // AdminRoleInterceptor.writeDenied path — body must contain the deny code, not a RepairReport.
            assertThat(responseBody).contains("admin role required");
        }
    }
}
