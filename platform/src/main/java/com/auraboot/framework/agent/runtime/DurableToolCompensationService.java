package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dispatches compensation-required durable tool executions to domain handlers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DurableToolCompensationService {

    private final DurableToolExecutionLedger ledger;
    private final List<DurableToolCompensationHandler> handlers;

    @Value("${agent.tool-execution.compensation.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(cron = "${agent.tool-execution.compensation.cron:30 * * * * *}")
    public int processDue() {
        return processDue(batchSize);
    }

    int processDue(int limit) {
        int processed = 0;
        for (DurableToolExecutionRecord record : ledger.findCompensationRequired(limit)) {
            if (compensate(record)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean compensate(DurableToolExecutionRecord record) {
        DurableToolCompensationHandler handler = handlers == null ? null : handlers.stream()
                .filter(candidate -> candidate.supports(record))
                .findFirst()
                .orElse(null);
        if (handler == null) {
            log.info("Durable tool compensation pending without handler: key={}",
                    LogSanitizer.safe(record == null ? null : record.executionKey()));
            return false;
        }
        try {
            DurableToolCompensationResult result = handler.compensate(record);
            if (result != null && result.compensated()) {
                ledger.markCompensated(record, result.rawResult());
            } else {
                ledger.markCompensationRequired(record,
                        "compensation handler did not complete: "
                                + safeMessage(result == null ? null : result.message()));
            }
            return true;
        } catch (Exception e) {
            ledger.markCompensationRequired(record, "compensation failed: " + safeMessage(e.getMessage()));
            return true;
        }
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
