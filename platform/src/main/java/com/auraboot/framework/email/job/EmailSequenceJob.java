package com.auraboot.framework.email.job;

import com.auraboot.framework.email.service.EmailSequenceExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that drives email sequence execution.
 *
 * <p>Runs every {@code aura.email.sequence.check-interval-seconds} seconds
 * (default 60 s). Delegates to {@link EmailSequenceExecutor#processDueEnrollments()}.
 *
 * @since 6.5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSequenceJob {

    private final EmailSequenceExecutor emailSequenceExecutor;

    /**
     * Executes due sequence enrollments on a fixed-delay schedule.
     *
     * <p>Using {@code fixedDelayString} ensures that the next run starts only
     * after the previous one has finished, preventing overlapping executions.
     */
    @Scheduled(fixedDelayString = "${aura.email.sequence.check-interval-seconds:60}000")
    public void runSequenceExecution() {
        log.debug("EmailSequenceJob triggered — processing due enrollments");
        try {
            emailSequenceExecutor.processDueEnrollments();
        } catch (Exception e) {
            log.error("EmailSequenceJob encountered unexpected error", e);
        }
    }
}
