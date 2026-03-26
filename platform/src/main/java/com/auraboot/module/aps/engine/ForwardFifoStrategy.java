package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component("forwardFifo")
public class ForwardFifoStrategy implements SchedulingStrategy {

    @Override
    public String name() { return "forward_fifo"; }

    @Override
    public String description() { return "Forward scheduling with FIFO job ordering"; }

    @Override
    public ScheduleResult schedule(ScheduleRequest request) {
        List<ScheduleJob> sorted = new ArrayList<>(request.getJobs());
        LocalDateTime start = request.getScheduleStart() != null ? request.getScheduleStart() : LocalDateTime.now();
        sorted.sort(Comparator.comparing(j -> j.getArrivalTime() != null ? j.getArrivalTime() : start));
        return scheduleInOrder(sorted, request);
    }

    /**
     * Schedule jobs in the given order without re-sorting.
     * Subclasses can override schedule() to provide their own sort and delegate here.
     */
    protected ScheduleResult scheduleInOrder(List<ScheduleJob> sorted, ScheduleRequest request) {
        LocalDateTime start = request.getScheduleStart() != null ? request.getScheduleStart() : LocalDateTime.now();

        Map<Long, LocalDateTime> resourceAvailability = new HashMap<>();
        Map<Long, Long> lastProductOnResource = new HashMap<>();
        for (ResourceInfo res : request.getResources()) {
            resourceAvailability.put(res.getId(), start);
        }

        List<ScheduledOperation> operations = new ArrayList<>();
        List<ScheduleConflict> conflicts = new ArrayList<>();

        for (ScheduleJob job : sorted) {
            ResourceInfo selectedResource = findBestResource(job, request.getResources(), resourceAvailability);
            if (selectedResource == null) {
                conflicts.add(ScheduleConflict.builder()
                    .jobId(job.getId())
                    .reason("No available resource of type " + job.getRequiredResourceType())
                    .build());
                continue;
            }

            LocalDateTime jobStart = resourceAvailability.get(selectedResource.getId());
            if (job.getArrivalTime() != null && job.getArrivalTime().isAfter(jobStart)) {
                jobStart = job.getArrivalTime();
            }

            int setupTime = calculateSetupTime(request.getSetupTimes(),
                lastProductOnResource.get(selectedResource.getId()), job.getProductId());
            if (setupTime > 0) {
                jobStart = jobStart.plusMinutes(setupTime);
            }

            LocalDateTime jobEnd = jobStart.plusMinutes(job.getProcessingTimeMin());

            operations.add(ScheduledOperation.builder()
                .jobId(job.getId()).jobCode(job.getCode()).productName(job.getProductName())
                .operationName(job.getOperationName())
                .resourceId(selectedResource.getId()).resourceName(selectedResource.getName())
                .startTime(jobStart).endTime(jobEnd)
                .setupTimeMin(setupTime).processingTimeMin(job.getProcessingTimeMin())
                .build());

            resourceAvailability.put(selectedResource.getId(), jobEnd);
            lastProductOnResource.put(selectedResource.getId(), job.getProductId());

            if (job.getDueDate() != null && jobEnd.isAfter(job.getDueDate())) {
                conflicts.add(ScheduleConflict.builder()
                    .jobId(job.getId()).reason("Past due")
                    .requestedBy(job.getDueDate()).achievableBy(jobEnd).build());
            }
        }

        return ScheduleResult.builder()
            .strategy(name())
            .operations(operations)
            .conflicts(conflicts)
            .resourceUtilization(calculateUtilization(operations, request))
            .earliestCompletion(operations.stream()
                .map(ScheduledOperation::getEndTime)
                .max(LocalDateTime::compareTo).orElse(null))
            .build();
    }

    protected ResourceInfo findBestResource(ScheduleJob job, List<ResourceInfo> resources,
                                          Map<Long, LocalDateTime> availability) {
        if (job.getRequiredResourceId() != null) {
            return resources.stream()
                .filter(r -> r.getId().equals(job.getRequiredResourceId()))
                .findFirst().orElse(null);
        }
        return resources.stream()
            .filter(r -> r.getType().equals(job.getRequiredResourceType()))
            .min(Comparator.comparing(r -> availability.getOrDefault(r.getId(), LocalDateTime.MAX)))
            .orElse(null);
    }

    protected int calculateSetupTime(Map<String, Integer> setupTimes, Long lastProductId, Long currentProductId) {
        if (setupTimes == null || lastProductId == null || lastProductId.equals(currentProductId)) {
            return 0;
        }
        String key = lastProductId + "-" + currentProductId;
        return setupTimes.getOrDefault(key, 0);
    }

    protected Map<Long, Double> calculateUtilization(List<ScheduledOperation> operations, ScheduleRequest request) {
        Map<Long, Double> utilization = new HashMap<>();
        if (operations.isEmpty()) return utilization;

        LocalDateTime scheduleEnd = operations.stream()
            .map(ScheduledOperation::getEndTime).max(LocalDateTime::compareTo).orElse(null);
        if (scheduleEnd == null) return utilization;

        LocalDateTime scheduleStart = request.getScheduleStart() != null ? request.getScheduleStart() : LocalDateTime.now();
        long totalMinutes = ChronoUnit.MINUTES.between(scheduleStart, scheduleEnd);
        if (totalMinutes <= 0) return utilization;

        for (ResourceInfo res : request.getResources()) {
            long busyMinutes = operations.stream()
                .filter(op -> op.getResourceId().equals(res.getId()))
                .mapToLong(op -> op.getSetupTimeMin() + op.getProcessingTimeMin())
                .sum();
            utilization.put(res.getId(), Math.round(busyMinutes * 1000.0 / totalMinutes) / 10.0);
        }
        return utilization;
    }
}
