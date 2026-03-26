package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for GeneticAlgorithmStrategy.
 *
 * Fixture: 3 jobs, 2 resources (SMT-1, REFLOW-1) — same as ApsComprehensiveTest.
 */
class GeneticAlgorithmStrategyTest {

    private static final LocalDateTime SCHEDULE_START = LocalDateTime.of(2026, 3, 1, 8, 0);

    private GeneticAlgorithmStrategy strategy;
    private List<ResourceInfo> resources;
    private Map<String, Integer> setupTimes;

    @BeforeEach
    void setUp() {
        strategy = new GeneticAlgorithmStrategy();

        resources = List.of(
                ResourceInfo.builder().id(1L).name("SMT-1").type("smt").build(),
                ResourceInfo.builder().id(2L).name("REFLOW-1").type("reflow").build()
        );

        setupTimes = Map.of("1-2", 30, "2-1", 30);
    }

    private ScheduleRequest buildBasicRequest() {
        ScheduleJob jobA = ScheduleJob.builder()
                .id(1L).code("JOB-A").productName("Product P1").productId(1L)
                .operationName("smt").requiredResourceType("smt")
                .processingTimeMin(60)
                .arrivalTime(LocalDateTime.of(2026, 3, 1, 8, 0))
                .dueDate(LocalDateTime.of(2026, 3, 1, 12, 0))
                .build();

        ScheduleJob jobB = ScheduleJob.builder()
                .id(2L).code("JOB-B").productName("Product P2").productId(2L)
                .operationName("smt").requiredResourceType("smt")
                .processingTimeMin(45)
                .arrivalTime(LocalDateTime.of(2026, 3, 1, 8, 30))
                .dueDate(LocalDateTime.of(2026, 3, 1, 10, 0))
                .build();

        ScheduleJob jobC = ScheduleJob.builder()
                .id(3L).code("JOB-C").productName("Product P1").productId(1L)
                .operationName("reflow").requiredResourceType("reflow")
                .processingTimeMin(30)
                .arrivalTime(LocalDateTime.of(2026, 3, 1, 9, 0))
                .dueDate(LocalDateTime.of(2026, 3, 1, 11, 0))
                .build();

        return ScheduleRequest.builder()
                .jobs(new ArrayList<>(List.of(jobA, jobB, jobC)))
                .resources(resources)
                .setupTimes(setupTimes)
                .scheduleStart(SCHEDULE_START)
                .build();
    }

    // ========== Test 1: shouldReturnCorrectName ==========

    @Test
    void shouldReturnCorrectName() {
        assertEquals("genetic", strategy.name());
        assertNotNull(strategy.description());
        assertTrue(strategy.description().contains("Genetic"));
    }

    // ========== Test 2: shouldScheduleAllJobs ==========

    @Test
    void shouldScheduleAllJobs() {
        ScheduleResult result = strategy.schedule(buildBasicRequest());

        assertNotNull(result);
        assertEquals("genetic", result.getStrategy());
        assertNotNull(result.getOperations());

        // All 3 jobs should be scheduled
        Set<Long> scheduledJobIds = new HashSet<>();
        for (ScheduledOperation op : result.getOperations()) {
            scheduledJobIds.add(op.getJobId());
        }
        assertTrue(scheduledJobIds.contains(1L), "Job A should be scheduled");
        assertTrue(scheduledJobIds.contains(2L), "Job B should be scheduled");
        assertTrue(scheduledJobIds.contains(3L), "Job C should be scheduled");
        assertEquals(3, scheduledJobIds.size(), "All 3 jobs should be scheduled");

        // Every operation should have valid times
        for (ScheduledOperation op : result.getOperations()) {
            assertNotNull(op.getStartTime(), "Start time should not be null");
            assertNotNull(op.getEndTime(), "End time should not be null");
            assertTrue(op.getEndTime().isAfter(op.getStartTime()),
                    "End time should be after start time for job " + op.getJobId());
            assertTrue(op.getProcessingTimeMin() > 0, "Processing time should be positive");
        }

        // Earliest completion should be set
        assertNotNull(result.getEarliestCompletion());
    }

    // ========== Test 3: shouldRespectResourceCapacity ==========

    @Test
    void shouldRespectResourceCapacity() {
        ScheduleResult result = strategy.schedule(buildBasicRequest());

        // Group operations by resource
        Map<Long, List<ScheduledOperation>> byResource = new HashMap<>();
        for (ScheduledOperation op : result.getOperations()) {
            byResource.computeIfAbsent(op.getResourceId(), k -> new ArrayList<>()).add(op);
        }

        // For each resource, verify no overlapping operations
        for (Map.Entry<Long, List<ScheduledOperation>> entry : byResource.entrySet()) {
            List<ScheduledOperation> ops = new ArrayList<>(entry.getValue());
            ops.sort(Comparator.comparing(ScheduledOperation::getStartTime));

            for (int i = 0; i < ops.size() - 1; i++) {
                ScheduledOperation current = ops.get(i);
                ScheduledOperation next = ops.get(i + 1);
                assertFalse(current.getEndTime().isAfter(next.getStartTime()),
                        String.format("Operations on resource %d overlap: %s ends at %s but %s starts at %s",
                                entry.getKey(), current.getJobCode(), current.getEndTime(),
                                next.getJobCode(), next.getStartTime()));
            }
        }
    }

    // ========== Test 4: shouldProduceBetterOrEqualToFifo ==========

    @Test
    void shouldProduceBetterOrEqualToFifo() {
        // Create a non-trivial request where GA can potentially optimize
        List<ScheduleJob> manyJobs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            // Alternate between SMT and REFLOW jobs with varying processing times
            String type = (i % 2 == 0) ? "reflow" : "smt";
            long productId = (i % 3) + 1; // 3 different products for setup time variation
            manyJobs.add(ScheduleJob.builder()
                    .id((long) i)
                    .code("JOB-" + i)
                    .productName("P" + productId)
                    .productId(productId)
                    .operationName(type)
                    .requiredResourceType(type)
                    .processingTimeMin(20 + (i * 5))
                    .dueDate(SCHEDULE_START.plusHours(3 + i))
                    .build());
        }

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(manyJobs)
                .resources(resources)
                .setupTimes(Map.of("1-2", 20, "2-1", 20, "1-3", 15, "3-1", 15, "2-3", 10, "3-2", 10))
                .scheduleStart(SCHEDULE_START)
                .build();

        ScheduleResult gaResult = strategy.schedule(request);
        ScheduleResult fifoResult = new ForwardFifoStrategy().schedule(request);

        assertNotNull(gaResult.getEarliestCompletion());
        assertNotNull(fifoResult.getEarliestCompletion());

        // GA should produce a schedule with makespan <= FIFO makespan
        // (or at worst equal, since FIFO is a valid permutation)
        long gaMakespan = java.time.Duration.between(SCHEDULE_START, gaResult.getEarliestCompletion()).toMinutes();
        long fifoMakespan = java.time.Duration.between(SCHEDULE_START, fifoResult.getEarliestCompletion()).toMinutes();

        assertTrue(gaMakespan <= fifoMakespan,
                String.format("GA makespan (%d min) should be <= FIFO makespan (%d min)",
                        gaMakespan, fifoMakespan));
    }

    // ========== Test 5: shouldTerminateWithinTimeout ==========

    @Test
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void shouldTerminateWithinTimeout() {
        // Create a larger workload: 100 jobs, 5 resources
        List<ResourceInfo> manyResources = List.of(
                ResourceInfo.builder().id(1L).name("SMT-1").type("smt").build(),
                ResourceInfo.builder().id(2L).name("SMT-2").type("smt").build(),
                ResourceInfo.builder().id(3L).name("REFLOW-1").type("reflow").build(),
                ResourceInfo.builder().id(4L).name("AOI-1").type("aoi").build(),
                ResourceInfo.builder().id(5L).name("PACK-1").type("pack").build()
        );

        String[] types = {"smt", "reflow", "aoi", "pack"};
        List<ScheduleJob> jobs = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String type = types[(i - 1) % types.length];
            jobs.add(ScheduleJob.builder()
                    .id((long) i)
                    .code("JOB-" + i)
                    .productName("P" + (i % 10))
                    .productId((long) (i % 10))
                    .operationName(type)
                    .requiredResourceType(type)
                    .processingTimeMin(10 + (i % 30))
                    .dueDate(SCHEDULE_START.plusHours(5 + (i / 10)))
                    .build());
        }

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(jobs)
                .resources(manyResources)
                .setupTimes(Map.of())
                .scheduleStart(SCHEDULE_START)
                .build();

        ScheduleResult result = strategy.schedule(request);

        assertNotNull(result);
        assertEquals("genetic", result.getStrategy());
        // All 100 jobs should be scheduled
        assertEquals(100, result.getOperations().size(), "All 100 jobs should be scheduled");
    }

    // ========== Test 6: shouldHandleSingleJob ==========

    @Test
    void shouldHandleSingleJob() {
        ScheduleJob singleJob = ScheduleJob.builder()
                .id(1L).code("solo").productName("P1").productId(1L)
                .operationName("smt").requiredResourceType("smt")
                .processingTimeMin(60)
                .dueDate(SCHEDULE_START.plusHours(4))
                .build();

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(List.of(singleJob))
                .resources(resources)
                .scheduleStart(SCHEDULE_START)
                .build();

        ScheduleResult result = strategy.schedule(request);

        assertEquals(1, result.getOperations().size());
        ScheduledOperation op = result.getOperations().get(0);
        assertEquals(1L, op.getJobId());
        assertEquals("SMT-1", op.getResourceName());
        assertEquals(SCHEDULE_START, op.getStartTime());
        assertEquals(SCHEDULE_START.plusMinutes(60), op.getEndTime());
    }

    // ========== Test 7: shouldAssignJobsToCorrectResourceType ==========

    @Test
    void shouldAssignJobsToCorrectResourceType() {
        ScheduleResult result = strategy.schedule(buildBasicRequest());

        for (ScheduledOperation op : result.getOperations()) {
            if ("smt".equals(op.getOperationName())) {
                assertEquals("SMT-1", op.getResourceName(),
                        "SMT job " + op.getJobCode() + " should be on SMT-1 (only SMT resource)");
            } else if ("reflow".equals(op.getOperationName())) {
                assertEquals("REFLOW-1", op.getResourceName(),
                        "REFLOW job " + op.getJobCode() + " should be on REFLOW-1 (only REFLOW resource)");
            }
        }
    }
}
