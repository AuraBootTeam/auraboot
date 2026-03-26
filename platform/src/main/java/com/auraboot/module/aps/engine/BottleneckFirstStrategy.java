package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component("bottleneckFirst")
public class BottleneckFirstStrategy implements SchedulingStrategy {

    private static final int DEFAULT_BUFFER_MINUTES = 30;

    @Override
    public String name() { return "bottleneck_first"; }

    @Override
    public String description() { return "Drum-Buffer-Rope: schedule bottleneck first, others around it"; }

    @Override
    public ScheduleResult schedule(ScheduleRequest request) {
        LocalDateTime start = request.getScheduleStart() != null ? request.getScheduleStart() : LocalDateTime.now();

        // 1. Identify bottleneck resource
        ResourceInfo bottleneck = identifyBottleneck(request);

        // 2. Separate bottleneck and non-bottleneck jobs
        List<ScheduleJob> bottleneckJobs = request.getJobs().stream()
            .filter(j -> isForResource(j, bottleneck))
            .collect(Collectors.toList());
        List<ScheduleJob> otherJobs = request.getJobs().stream()
            .filter(j -> !isForResource(j, bottleneck))
            .collect(Collectors.toList());

        List<ScheduledOperation> operations = new ArrayList<>();
        List<ScheduleConflict> conflicts = new ArrayList<>();

        // 3. Schedule bottleneck jobs — sort by EDD, group by product to minimize changeover
        List<ScheduleJob> sortedBottleneck = groupByProductWithEdd(bottleneckJobs);
        LocalDateTime bottleneckTime = start.plusMinutes(DEFAULT_BUFFER_MINUTES); // Buffer before bottleneck
        Long lastProductId = null;

        for (ScheduleJob job : sortedBottleneck) {
            int setupTime = 0;
            if (lastProductId != null && !lastProductId.equals(job.getProductId()) && request.getSetupTimes() != null) {
                String key = lastProductId + "-" + job.getProductId();
                setupTime = request.getSetupTimes().getOrDefault(key, 0);
            }

            LocalDateTime jobStart = bottleneckTime.plusMinutes(setupTime);
            LocalDateTime jobEnd = jobStart.plusMinutes(job.getProcessingTimeMin());

            operations.add(ScheduledOperation.builder()
                .jobId(job.getId()).jobCode(job.getCode()).productName(job.getProductName())
                .operationName(job.getOperationName())
                .resourceId(bottleneck.getId()).resourceName(bottleneck.getName())
                .startTime(jobStart).endTime(jobEnd)
                .setupTimeMin(setupTime).processingTimeMin(job.getProcessingTimeMin())
                .build());

            bottleneckTime = jobEnd;
            lastProductId = job.getProductId();

            if (job.getDueDate() != null && jobEnd.isAfter(job.getDueDate())) {
                conflicts.add(ScheduleConflict.builder()
                    .jobId(job.getId()).reason("Past due on bottleneck")
                    .requestedBy(job.getDueDate()).achievableBy(jobEnd).build());
            }
        }

        // 4. Schedule non-bottleneck jobs forward from start
        Map<Long, LocalDateTime> otherResourceAvail = new HashMap<>();
        for (ResourceInfo res : request.getResources()) {
            if (!res.getId().equals(bottleneck.getId())) {
                otherResourceAvail.put(res.getId(), start);
            }
        }

        otherJobs.sort(Comparator.comparing(j -> j.getDueDate() != null ? j.getDueDate() : LocalDateTime.MAX));
        for (ScheduleJob job : otherJobs) {
            ResourceInfo selectedResource = findNonBottleneckResource(job, request.getResources(), bottleneck, otherResourceAvail);
            if (selectedResource == null) {
                conflicts.add(ScheduleConflict.builder()
                    .jobId(job.getId())
                    .reason("No available non-bottleneck resource of type " + job.getRequiredResourceType())
                    .build());
                continue;
            }

            LocalDateTime jobStart = otherResourceAvail.getOrDefault(selectedResource.getId(), start);
            LocalDateTime jobEnd = jobStart.plusMinutes(job.getProcessingTimeMin());

            operations.add(ScheduledOperation.builder()
                .jobId(job.getId()).jobCode(job.getCode()).productName(job.getProductName())
                .operationName(job.getOperationName())
                .resourceId(selectedResource.getId()).resourceName(selectedResource.getName())
                .startTime(jobStart).endTime(jobEnd)
                .setupTimeMin(0).processingTimeMin(job.getProcessingTimeMin())
                .build());

            otherResourceAvail.put(selectedResource.getId(), jobEnd);
        }

        Map<Long, Double> utilization = calculateUtilization(operations, request, start);

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

    ResourceInfo identifyBottleneck(ScheduleRequest request) {
        // Find resource with the longest total processing time
        Map<String, Long> loadByType = new HashMap<>();
        for (ScheduleJob job : request.getJobs()) {
            String type = job.getRequiredResourceType();
            if (type != null) {
                loadByType.merge(type, (long) job.getProcessingTimeMin(), Long::sum);
            }
        }

        String bottleneckType = loadByType.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        if (bottleneckType == null) {
            return request.getResources().get(0);
        }

        return request.getResources().stream()
            .filter(r -> r.getType().equals(bottleneckType))
            .findFirst()
            .orElse(request.getResources().get(0));
    }

    private boolean isForResource(ScheduleJob job, ResourceInfo resource) {
        if (job.getRequiredResourceId() != null) {
            return job.getRequiredResourceId().equals(resource.getId());
        }
        return resource.getType().equals(job.getRequiredResourceType());
    }

    private List<ScheduleJob> groupByProductWithEdd(List<ScheduleJob> jobs) {
        // Group by product, sort groups by earliest due date, within group sort by due date
        Map<Long, List<ScheduleJob>> byProduct = jobs.stream()
            .collect(Collectors.groupingBy(j -> j.getProductId() != null ? j.getProductId() : 0L));

        // Sort groups by earliest due date in group
        List<Map.Entry<Long, List<ScheduleJob>>> sortedGroups = new ArrayList<>(byProduct.entrySet());
        sortedGroups.sort(Comparator.comparing(entry ->
            entry.getValue().stream()
                .map(j -> j.getDueDate() != null ? j.getDueDate() : LocalDateTime.MAX)
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.MAX)));

        List<ScheduleJob> result = new ArrayList<>();
        for (var entry : sortedGroups) {
            List<ScheduleJob> group = new ArrayList<>(entry.getValue());
            group.sort(Comparator.comparing(j -> j.getDueDate() != null ? j.getDueDate() : LocalDateTime.MAX));
            result.addAll(group);
        }
        return result;
    }

    private ResourceInfo findNonBottleneckResource(ScheduleJob job, List<ResourceInfo> resources,
                                                    ResourceInfo bottleneck, Map<Long, LocalDateTime> availability) {
        return resources.stream()
            .filter(r -> !r.getId().equals(bottleneck.getId()))
            .filter(r -> job.getRequiredResourceId() != null
                ? r.getId().equals(job.getRequiredResourceId())
                : r.getType().equals(job.getRequiredResourceType()))
            .min(Comparator.comparing(r -> availability.getOrDefault(r.getId(), LocalDateTime.MAX)))
            .orElse(null);
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
