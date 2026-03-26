package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component("backward")
public class BackwardStrategy implements SchedulingStrategy {

    @Override
    public String name() { return "backward"; }

    @Override
    public String description() { return "Backward scheduling from due dates"; }

    @Override
    public ScheduleResult schedule(ScheduleRequest request) {
        LocalDateTime now = request.getScheduleStart() != null ? request.getScheduleStart() : LocalDateTime.now();

        // Sort by due date descending (latest first, schedule backward)
        List<ScheduleJob> sorted = new ArrayList<>(request.getJobs());
        sorted.sort(Comparator.comparing((ScheduleJob j) ->
            j.getDueDate() != null ? j.getDueDate() : LocalDateTime.MAX).reversed());

        // Track resource latest occupied start time and last product for setup time
        Map<Long, LocalDateTime> resourceLatestOccupied = new HashMap<>();
        Map<Long, Long> lastProductOnResource = new HashMap<>();

        List<ScheduledOperation> operations = new ArrayList<>();
        List<ScheduleConflict> conflicts = new ArrayList<>();

        for (ScheduleJob job : sorted) {
            ResourceInfo selectedResource = findResource(job, request.getResources());
            if (selectedResource == null) {
                conflicts.add(ScheduleConflict.builder()
                    .jobId(job.getId())
                    .reason("No available resource of type " + job.getRequiredResourceType())
                    .build());
                continue;
            }

            LocalDateTime dueDate = job.getDueDate() != null ? job.getDueDate() : now.plusDays(7);
            LocalDateTime endTime = dueDate;

            // If resource is already occupied at this time, push backward
            LocalDateTime occupied = resourceLatestOccupied.get(selectedResource.getId());
            if (occupied != null && occupied.isBefore(endTime)) {
                endTime = occupied;
            }

            // Calculate setup time for product change
            int setupTime = calculateSetupTime(request.getSetupTimes(),
                lastProductOnResource.get(selectedResource.getId()), job.getProductId());

            LocalDateTime startTime = endTime.minusMinutes(job.getProcessingTimeMin() + setupTime);

            // Check if start is before now
            if (startTime.isBefore(now)) {
                conflicts.add(ScheduleConflict.builder()
                    .jobId(job.getId())
                    .reason("Cannot start in time")
                    .requestedBy(job.getDueDate())
                    .achievableBy(now.plusMinutes(job.getProcessingTimeMin() + setupTime))
                    .build());
                // Still schedule at now (forward from now)
                startTime = now;
                endTime = startTime.plusMinutes(job.getProcessingTimeMin() + setupTime);
            }

            operations.add(ScheduledOperation.builder()
                .jobId(job.getId()).jobCode(job.getCode()).productName(job.getProductName())
                .operationName(job.getOperationName())
                .resourceId(selectedResource.getId()).resourceName(selectedResource.getName())
                .startTime(startTime).endTime(endTime)
                .setupTimeMin(setupTime).processingTimeMin(job.getProcessingTimeMin())
                .build());

            // Use startTime so next backward job ends before this job starts
            resourceLatestOccupied.put(selectedResource.getId(), startTime);
            lastProductOnResource.put(selectedResource.getId(), job.getProductId());
        }

        Map<Long, Double> utilization = calculateUtilization(operations, request, now);

        return ScheduleResult.builder()
            .strategy(name())
            .operations(operations)
            .conflicts(conflicts)
            .resourceUtilization(utilization)
            .earliestCompletion(operations.stream()
                .map(ScheduledOperation::getEndTime)
                .max(LocalDateTime::compareTo).orElse(null))
            .build();
    }

    private int calculateSetupTime(Map<String, Integer> setupTimes, Long lastProductId, Long currentProductId) {
        if (setupTimes == null || lastProductId == null || lastProductId.equals(currentProductId)) {
            return 0;
        }
        String key = lastProductId + "-" + currentProductId;
        return setupTimes.getOrDefault(key, 0);
    }

    private ResourceInfo findResource(ScheduleJob job, List<ResourceInfo> resources) {
        if (job.getRequiredResourceId() != null) {
            return resources.stream()
                .filter(r -> r.getId().equals(job.getRequiredResourceId()))
                .findFirst().orElse(null);
        }
        return resources.stream()
            .filter(r -> r.getType().equals(job.getRequiredResourceType()))
            .findFirst().orElse(null);
    }

    private Map<Long, Double> calculateUtilization(List<ScheduledOperation> operations,
                                                    ScheduleRequest request, LocalDateTime start) {
        Map<Long, Double> utilization = new HashMap<>();
        if (operations.isEmpty()) return utilization;

        LocalDateTime end = operations.stream()
            .map(ScheduledOperation::getEndTime).max(LocalDateTime::compareTo).orElse(start);
        long totalMinutes = ChronoUnit.MINUTES.between(start, end);
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
