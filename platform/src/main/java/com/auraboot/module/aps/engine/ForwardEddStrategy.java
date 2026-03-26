package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component("forwardEdd")
public class ForwardEddStrategy extends ForwardFifoStrategy {

    @Override
    public String name() { return "forward_edd"; }

    @Override
    public String description() { return "Forward scheduling with Earliest Due Date first"; }

    @Override
    public ScheduleResult schedule(ScheduleRequest request) {
        // Sort jobs by due date ascending (earliest due date first)
        List<ScheduleJob> sorted = new ArrayList<>(request.getJobs());
        sorted.sort(Comparator.comparing(j -> j.getDueDate() != null ? j.getDueDate() : LocalDateTime.MAX));

        // Delegate to parent's scheduleInOrder to avoid FIFO re-sort
        ScheduleResult result = scheduleInOrder(sorted, request);
        return ScheduleResult.builder()
            .strategy(name())
            .operations(result.getOperations())
            .conflicts(result.getConflicts())
            .resourceUtilization(result.getResourceUtilization())
            .earliestCompletion(result.getEarliestCompletion())
            .build();
    }
}
