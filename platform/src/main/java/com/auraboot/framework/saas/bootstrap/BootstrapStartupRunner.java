package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Unified startup-time bootstrap runner (Phase 2.3 of the bootstrap-unified plan,
 * see {@code docs/plans/2026-05/bootstrap-unified.md}).
 *
 * <p>Replaces the two legacy startup paths:
 * <ul>
 *   <li>{@code AdminBootstrapRunner} — first-run admin/tenant auto-create when
 *       {@code AURABOOT_BOOTSTRAP_ENABLED=true}</li>
 *   <li>{@code BootstrapStartupListener} — seed-config-driven bootstrap when
 *       {@code auraboot.saas.bootstrap.mode=seed}</li>
 * </ul>
 *
 * <p>Both are now expressed as a single idempotent call to
 * {@link BootstrapRepairService#repairAll}, which guarantees the bootstrap invariants
 * regardless of whether the DB is empty, partially seeded, or fully provisioned.
 * Restarts are safe: if every invariant already holds, every step returns
 * {@link RepairStepResult.Status#PRESENT} and no rows are written.
 *
 * <p><b>Activation</b>: gated by {@code auraboot.bootstrap.enabled} (env var
 * {@code AURABOOT_BOOTSTRAP_ENABLED}). The default in {@code application.yml} is
 * {@code false} (prod-safe). Profiles {@code dev} / {@code community} / {@code test}
 * override to {@code true}. The {@code oss-reset-and-init.sh} script still exports
 * {@code AURABOOT_BOOTSTRAP_ENABLED=false} on line 152 — this becomes vestigial
 * once the script's wizard call is replaced (Phase 2.4) but is harmless today.
 *
 * <p><b>Defaults</b>: when no override is supplied, options are loaded from
 * {@code classpath:bootstrap/bootstrap-seed-config.json} (legacy seed-config
 * mechanism — preserved). Missing JSON falls back to hard-coded dev defaults.
 *
 * <p><b>Failure handling</b>: any step returning
 * {@link RepairStepResult.Status#ERROR} (other than the documented non-fatal
 * {@code builtin_plugins} step, which mirrors pre-2.3 semantics) causes this
 * runner to throw, failing application startup loudly. Silent skips are
 * forbidden — see hard rule §5 of the Phase 2.3 spec.
 *
 * <p><b>Coexistence with the {@code /api/bootstrap/setup} wizard</b>: the
 * {@link BootstrapEngineService#execute} entry point retains its
 * "already initialized" guard. Hitting the wizard after this runner has run
 * is a no-op-with-error from the wizard's perspective (intentional — the
 * wizard is for empty installations only). This runner bypasses the wizard
 * wrapper and calls {@link BootstrapRepairService} directly.
 */
@Slf4j
@Component
@Order(2) // After PlatformSeedRunner (@Order(1)).
@ConditionalOnProperty(
        name = "auraboot.bootstrap.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
public class BootstrapStartupRunner implements ApplicationRunner {

    private static final String SEED_CONFIG_RESOURCE = "bootstrap/bootstrap-seed-config.json";

    private final BootstrapRepairService bootstrapRepairService;
    private final ObjectMapper objectMapper;

    /**
     * Phase 3: when {@code true}, the demo profile of built-in plugins
     * (core-meta, core-bpm, core-aurabot, page-manager, crm-starter, showcase,
     * agent-control-plane, acp-showcase, workflow-demo) is imported in addition
     * to the always-on core profile (org-management, platform-admin).
     *
     * <p>Default {@code false} — prod-safe. Override via env
     * {@code AURABOOT_DEMO_SEED=true} or property
     * {@code auraboot.bootstrap.demo-seed=true}. The {@code dev} / {@code community}
     * profiles default this to {@code true} (see {@code application-dev.yml}).
     */
    @Value("${auraboot.bootstrap.demo-seed:false}")
    private boolean demoSeed;

    @Override
    public void run(ApplicationArguments args) {
        log.info("BootstrapStartupRunner: auraboot.bootstrap.enabled=true, demo-seed={} → running repairAll() for bootstrap invariants",
                demoSeed);

        BootstrapRepairService.RepairOptions opts = loadOptions();
        RepairReport report;
        try {
            report = bootstrapRepairService.repairAll(opts);
        } catch (RuntimeException e) {
            log.error("BootstrapStartupRunner: repairAll threw — failing startup", e);
            throw e;
        }

        // One INFO line per step — operators can grep for "BootstrapStartupRunner: <step>".
        for (RepairStepResult step : report.steps()) {
            switch (step.status()) {
                case PRESENT, CREATED, REPAIRED ->
                        log.info("BootstrapStartupRunner: {} {} — {}",
                                step.stepName(), step.status(), step.detail());
                case ERROR ->
                        log.error("BootstrapStartupRunner: {} ERROR — {}",
                                step.stepName(), step.detail());
            }
        }

        log.info("BootstrapStartupRunner: summary present={}, created={}, repaired={}, anyError={}",
                report.totalPresent(), report.totalCreated(), report.totalRepaired(), report.anyError());

        // The builtin_plugins step is allowed to ERROR non-fatally (pre-2.3 semantics
        // from AdminBootstrapRunner: built-in plugin import failures were warned and
        // skipped because they can be retried via the admin UI). Any OTHER ERROR
        // step fails startup.
        boolean fatal = report.steps().stream()
                .filter(s -> s.status() == RepairStepResult.Status.ERROR)
                .anyMatch(s -> !BootstrapRepairService.STEP_BUILTIN_PLUGINS.equals(s.stepName()));
        if (fatal) {
            throw new IllegalStateException(
                    "BootstrapStartupRunner: one or more invariants failed to repair — "
                            + "see prior ERROR log lines. Refusing to continue startup.");
        }
    }

    /**
     * Load default repair options from {@code bootstrap/bootstrap-seed-config.json}
     * if present (legacy seed-config mechanism, preserved from
     * {@code BootstrapStartupListener}). Falls back to hard-coded dev defaults if
     * the JSON is missing or unreadable. The fallback values match
     * {@code AdminBootstrapRunner}'s constants for behavior parity.
     */
    private BootstrapRepairService.RepairOptions loadOptions() {
        try {
            ClassPathResource resource = new ClassPathResource(SEED_CONFIG_RESOURCE);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    BootstrapRequest req = objectMapper.readValue(is, BootstrapRequest.class);
                    log.info("BootstrapStartupRunner: loaded options from {} (companyName={}, adminEmail={}, mode={})",
                            SEED_CONFIG_RESOURCE, req.getCompanyName(), req.getAdminEmail(), req.getSystemMode());
                    BootstrapRepairService.RepairOptions base =
                            BootstrapRepairService.RepairOptions.fromBootstrapRequest(req);
                    // Startup-time demo-seed flag overrides whatever the seed-config JSON says
                    // (env var takes precedence over checked-in defaults).
                    return BootstrapRepairService.RepairOptions.of(
                            base.adminEmail(), base.adminPassword(), base.adminDisplayName(),
                            base.companyName(), base.systemMode(), base.instanceUrl(),
                            demoSeed);
                }
            }
        } catch (Exception e) {
            log.warn("BootstrapStartupRunner: could not load {}, using hard-coded defaults: {}",
                    SEED_CONFIG_RESOURCE, e.getMessage());
        }
        return BootstrapRepairService.RepairOptions.of(
                "admin@auraboot.com",
                "Test2026x",
                "Admin",
                "AuraBoot Dev",
                "single",
                "http://localhost:6443",
                demoSeed);
    }
}
