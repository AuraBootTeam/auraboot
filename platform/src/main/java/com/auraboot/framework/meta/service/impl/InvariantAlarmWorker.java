package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.InvariantDefinition;
import com.auraboot.framework.meta.mapper.InvariantDefinitionMapper;
import com.auraboot.framework.meta.service.InvariantEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Invariant Alarm Worker.
 * Periodically evaluates ALWAYS-type invariants against model records.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvariantAlarmWorker {

    private final InvariantDefinitionMapper invariantMapper;
    private final InvariantEngine invariantEngine;

    /**
     * Check ALWAYS invariants.
     * Scheduled via DatabaseSchedulerEngine (sys-invariant-alarm, interval 5min).
     */
    public void checkAlwaysInvariants() {
        try {
            List<InvariantDefinition> alwaysInvariants = invariantMapper.findAllPublishedAlways();
            if (alwaysInvariants == null || alwaysInvariants.isEmpty()) {
                return;
            }

            // Collect unique tenant+model combinations
            Set<String> processed = new HashSet<>();
            for (InvariantDefinition inv : alwaysInvariants) {
                String key = inv.getTenantId() + ":" + inv.getModelCode();
                if (processed.add(key)) {
                    try {
                        invariantEngine.evaluateAlwaysInvariants(inv.getTenantId(), inv.getModelCode());
                    } catch (Exception e) {
                        log.error("ALWAYS invariant check failed for tenant={}, model={}: {}",
                                inv.getTenantId(), inv.getModelCode(), e.getMessage());
                    }
                }
            }

            log.debug("ALWAYS invariant check completed: {} tenant/model combinations processed", processed.size());
        } catch (Exception e) {
            log.error("ALWAYS invariant worker failed: {}", e.getMessage());
        }
    }
}
