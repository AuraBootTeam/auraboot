package com.auraboot.framework.bpm.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Re-registers previously deployed BPMN process definitions into SmartEngine's
 * in-memory repository on application startup.
 *
 * <p>SmartEngine keeps deployed process metadata in-process only; the BPMN XML
 * is persisted by AuraBoot in {@code ab_bpm_process_definition.bpmn_content}
 * and {@code status='deployed'}. After a backend restart the DB rows remain,
 * but the engine's cache is empty, so any runtime call (e.g. {@code startProcess})
 * fails with {@code Process definition version not found} until each process is
 * manually re-deployed via the admin API.
 *
 * <p>This listener closes that gap by iterating every {@code status='deployed'}
 * row across all tenants at {@link ApplicationReadyEvent} time and calling
 * {@code RepositoryCommandService#deploy} for each. Per-process failures are
 * logged and isolated so a single malformed BPMN cannot block the module from
 * finishing startup.
 *
 * <p>Idempotency: re-running this listener is safe because
 * {@link #isAlreadyCached(RepositoryQueryService, String)} skips any process
 * whose id is already present in the engine cache. That makes both cold-start
 * and any hypothetical second invocation side-effect-free.
 */
@Slf4j
@Component
@RequiredArgsConstructor
// Run after BpmModuleConfiguration's own ApplicationReadyEvent verification so
// SmartEngine wiring has already been validated before we try to re-register.
@Order(100)
public class BpmStartupReDeployer {

    private final SmartEngine smartEngine;
    private final BpmProcessDefinitionMapper processDefinitionMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void reDeployPersistedProcessesOnStartup() {
        List<BpmProcessDefinition> deployed;
        try {
            deployed = processDefinitionMapper.findAllDeployedAcrossTenants();
        } catch (Exception e) {
            // If the query itself fails the module still needs to start; surface the
            // root cause but don't abort backend boot.
            log.error("BPM startup re-deploy: failed to query deployed process definitions; " +
                    "SmartEngine cache will be empty until processes are re-deployed manually", e);
            return;
        }

        if (deployed == null || deployed.isEmpty()) {
            log.info("BPM startup re-deploy: no persisted deployed processes found; nothing to register");
            return;
        }

        RepositoryQueryService queryService = smartEngine.getRepositoryQueryService();

        int registered = 0;
        int skipped = 0;
        int failed = 0;

        for (BpmProcessDefinition def : deployed) {
            try {
                DeployOutcome outcome = reDeployOne(def, queryService);
                switch (outcome) {
                    case REGISTERED -> registered++;
                    case ALREADY_CACHED, EMPTY_BPMN -> skipped++;
                }
            } catch (Exception e) {
                failed++;
                log.error("BPM startup re-deploy: failed to register process key={} tenant={} pid={}: {}",
                        def.getProcessKey(), def.getTenantId(), def.getPid(), e.getMessage(), e);
                // continue: one bad BPMN must not block sibling processes
            }
        }

        log.info("BPM startup re-deploy complete: total={} registered={} skipped(alreadyCached)={} failed={}",
                deployed.size(), registered, skipped, failed);
    }

    enum DeployOutcome { REGISTERED, ALREADY_CACHED, EMPTY_BPMN }

    /**
     * Deploy a single process definition into SmartEngine if not already cached.
     */
    DeployOutcome reDeployOne(BpmProcessDefinition def, RepositoryQueryService queryService) {
        String processKey = def.getProcessKey();

        if (!org.springframework.util.StringUtils.hasText(def.getBpmnContent())) {
            log.warn("BPM startup re-deploy: skipping pid={} key={} (empty bpmn_content)",
                    def.getPid(), processKey);
            return DeployOutcome.EMPTY_BPMN;
        }

        if (isAlreadyCached(queryService, processKey)) {
            log.debug("BPM startup re-deploy: process {} already cached, skipping", processKey);
            return DeployOutcome.ALREADY_CACHED;
        }

        // Bind the tenant context so any downstream hook that reads MetaContext
        // sees the owning tenant instead of null. We clear it in finally to avoid
        // leaking across processes.
        Long previousTenant = safeGetTenantId();
        try {
            MetaContext.setSystemTenantContext(def.getTenantId());

            ByteArrayInputStream bpmnStream = new ByteArrayInputStream(
                    def.getBpmnContent().getBytes(StandardCharsets.UTF_8));

            smartEngine.getRepositoryCommandService()
                    .deploy(bpmnStream, String.valueOf(def.getTenantId()));

            log.info("BPM startup re-deploy: registered process key={} tenant={} version={}",
                    processKey, def.getTenantId(), def.getVersion());
            return DeployOutcome.REGISTERED;
        } finally {
            if (previousTenant != null) {
                MetaContext.setSystemTenantContext(previousTenant);
            } else {
                MetaContext.clear();
            }
        }
    }

    private boolean isAlreadyCached(RepositoryQueryService queryService, String processKey) {
        try {
            return queryService.getAllCachedProcessDefinition()
                    .stream()
                    .anyMatch(pd -> processKey.equals(pd.getId()));
        } catch (Exception e) {
            // If the cache query itself fails we conservatively treat the process as
            // not cached so the caller will attempt a deploy. A real "already
            // registered" conflict will then surface as a per-process failure in
            // reDeployPersistedProcessesOnStartup, which is logged and isolated.
            log.warn("BPM startup re-deploy: cache lookup failed for {}: {}", processKey, e.getMessage());
            return false;
        }
    }

    private Long safeGetTenantId() {
        try {
            return MetaContext.getCurrentTenantId();
        } catch (Exception e) {
            return null;
        }
    }
}
