package com.auraboot.module.aps.engine;

import com.auraboot.module.aps.dto.*;
import com.auraboot.module.aps.visualization.GanttDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for all 4 APS scheduling strategies
 * and the SchedulingEngine dispatcher.
 *
 * Fixture: 3 jobs, 2 resources (SMT-1, REFLOW-1)
 *   Job A: SMT, 60min, product P1, arrival 8:00, due 12:00
 *   Job B: SMT, 45min, product P2, arrival 8:30, due 10:00
 *   Job C: REFLOW, 30min, product P1, arrival 9:00, due 11:00
 *
 * Setup times: "1-2" -> 30min, "2-1" -> 30min (product change on SMT costs 30 min)
 */
class ApsComprehensiveTest {

    private static final LocalDateTime SCHEDULE_START = LocalDateTime.of(2026, 3, 1, 8, 0);

    private List<ScheduleJob> jobs;
    private List<ResourceInfo> resources;
    private Map<String, Integer> setupTimes;

    private ForwardFifoStrategy fifoStrategy;
    private ForwardEddStrategy eddStrategy;
    private BackwardStrategy backwardStrategy;
    private BottleneckFirstStrategy bottleneckStrategy;

    @BeforeEach
    void setUp() {
        // Resources
        resources = List.of(
                ResourceInfo.builder().id(1L).name("SMT-1").type("smt").build(),
                ResourceInfo.builder().id(2L).name("REFLOW-1").type("reflow").build()
        );

        // Jobs
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

        jobs = List.of(jobA, jobB, jobC);

        // Setup times: switching product on same resource
        setupTimes = Map.of("1-2", 30, "2-1", 30);

        // Strategies
        fifoStrategy = new ForwardFifoStrategy();
        eddStrategy = new ForwardEddStrategy();
        backwardStrategy = new BackwardStrategy();
        bottleneckStrategy = new BottleneckFirstStrategy();
    }

    private ScheduleRequest buildRequest() {
        return ScheduleRequest.builder()
                .jobs(new ArrayList<>(jobs))
                .resources(resources)
                .setupTimes(setupTimes)
                .scheduleStart(SCHEDULE_START)
                .build();
    }

    // ========== Forward FIFO Strategy ==========

    @Test
    void testFifoSchedulesInArrivalOrder() {
        ScheduleResult result = fifoStrategy.schedule(buildRequest());

        // Find SMT operations (Job A and Job B)
        List<ScheduledOperation> smtOps = result.getOperations().stream()
                .filter(op -> "smt".equals(op.getOperationName()))
                .sorted(Comparator.comparing(ScheduledOperation::getStartTime))
                .toList();

        assertEquals(2, smtOps.size());
        // Job A arrives at 8:00, Job B at 8:30 → A should be first
        assertEquals(1L, smtOps.get(0).getJobId(), "Job A (arrival 8:00) should be scheduled before Job B (arrival 8:30)");
        assertEquals(2L, smtOps.get(1).getJobId(), "Job B should be scheduled second");
        assertTrue(smtOps.get(0).getStartTime().isBefore(smtOps.get(1).getStartTime()));
    }

    @Test
    void testFifoResourceAssignment() {
        ScheduleResult result = fifoStrategy.schedule(buildRequest());

        // Job C requires REFLOW type → should be assigned to REFLOW-1 (id=2)
        ScheduledOperation jobCOp = result.getOperations().stream()
                .filter(op -> op.getJobId() == 3L)
                .findFirst().orElseThrow();

        assertEquals(2L, jobCOp.getResourceId(), "Job C should be on REFLOW-1");
        assertEquals("REFLOW-1", jobCOp.getResourceName());

        // Job A and B should be on SMT-1 (id=1)
        result.getOperations().stream()
                .filter(op -> op.getJobId() == 1L || op.getJobId() == 2L)
                .forEach(op -> assertEquals(1L, op.getResourceId(),
                        "SMT jobs should be on SMT-1"));
    }

    @Test
    void testFifoSetupTimeOnProductChange() {
        ScheduleResult result = fifoStrategy.schedule(buildRequest());

        // FIFO order: A (P1) then B (P2) on SMT-1
        // Switching from P1 to P2 should add 30 min setup
        List<ScheduledOperation> smtOps = result.getOperations().stream()
                .filter(op -> op.getResourceId() == 1L)
                .sorted(Comparator.comparing(ScheduledOperation::getStartTime))
                .toList();

        assertEquals(2, smtOps.size());
        ScheduledOperation opA = smtOps.get(0);
        ScheduledOperation opB = smtOps.get(1);

        // Job A: no setup (first job), starts at 8:00, ends at 9:00
        assertEquals(0, opA.getSetupTimeMin());
        assertEquals(SCHEDULE_START, opA.getStartTime());
        assertEquals(SCHEDULE_START.plusMinutes(60), opA.getEndTime());

        // Job B: 30 min setup (P1→P2), arrives 8:30 but A ends at 9:00
        // So B starts at 9:00 + 30min setup = 9:30, ends at 9:30 + 45 = 10:15
        assertEquals(30, opB.getSetupTimeMin());
        assertEquals(LocalDateTime.of(2026, 3, 1, 9, 30), opB.getStartTime());
        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 15), opB.getEndTime());
    }

    @Test
    void testFifoDueDateConflict() {
        // Create a scenario where processing makes a job late
        // Job B has due 10:00, but with setup it ends at 10:15 → conflict
        ScheduleResult result = fifoStrategy.schedule(buildRequest());

        // Job B (due 10:00) should finish at 10:15 → past due conflict
        Optional<ScheduleConflict> jobBConflict = result.getConflicts().stream()
                .filter(c -> c.getJobId() == 2L)
                .findFirst();

        assertTrue(jobBConflict.isPresent(), "Job B should have a due date conflict");
        assertEquals("Past due", jobBConflict.get().getReason());
        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0), jobBConflict.get().getRequestedBy());
        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 15), jobBConflict.get().getAchievableBy());
    }

    // ========== Forward EDD Strategy ==========

    @Test
    void testEddSchedulesEarliestDueDateFirst() {
        // EDD sorts by due date, NOT by arrival time.
        // Even with different arrival times, the earlier due date gets scheduled first.
        ScheduleRequest request = buildRequest();
        ScheduleResult result = eddStrategy.schedule(request);

        assertEquals("forward_edd", result.getStrategy());

        // EDD order on SMT: B (due 10:00) before A (due 12:00)
        // despite B arriving later (8:30 vs 8:00)
        List<ScheduledOperation> smtOps = result.getOperations().stream()
                .filter(op -> "smt".equals(op.getOperationName()))
                .sorted(Comparator.comparing(ScheduledOperation::getStartTime))
                .toList();

        assertEquals(2, smtOps.size());
        assertEquals(2L, smtOps.get(0).getJobId(), "Job B (due 10:00) should be first in EDD despite later arrival");
        assertEquals(1L, smtOps.get(1).getJobId(), "Job A (due 12:00) should be second in EDD");
    }

    // ========== Backward Strategy ==========

    @Test
    void testBackwardFromDueDate() {
        ScheduleResult result = backwardStrategy.schedule(buildRequest());

        assertEquals("backward", result.getStrategy());

        // Backward scheduling: each job ends at its due date
        // Jobs are sorted by due date descending (latest first)
        // Job A (due 12:00, 60min): 11:00-12:00
        ScheduledOperation jobAOp = result.getOperations().stream()
                .filter(op -> op.getJobId() == 1L).findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 3, 1, 12, 0), jobAOp.getEndTime());
        assertEquals(LocalDateTime.of(2026, 3, 1, 11, 0), jobAOp.getStartTime());

        // Job C (due 11:00, 30min): 10:30-11:00
        ScheduledOperation jobCOp = result.getOperations().stream()
                .filter(op -> op.getJobId() == 3L).findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 3, 1, 11, 0), jobCOp.getEndTime());
        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 30), jobCOp.getStartTime());
    }

    @Test
    void testBackwardConflict() {
        // Create a job that can't start in time (due too early for processing time)
        ScheduleJob tightJob = ScheduleJob.builder()
                .id(10L).code("tight").productName("P1").productId(1L)
                .operationName("smt").requiredResourceType("smt")
                .processingTimeMin(120)
                .dueDate(LocalDateTime.of(2026, 3, 1, 8, 30)) // Due 8:30 but needs 120 min from 8:00
                .build();

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(List.of(tightJob))
                .resources(resources)
                .scheduleStart(SCHEDULE_START)
                .build();

        ScheduleResult result = backwardStrategy.schedule(request);

        // Start would be 8:30 - 120min = 6:30, which is before schedule start 8:00 → conflict
        Optional<ScheduleConflict> conflict = result.getConflicts().stream()
                .filter(c -> c.getJobId() == 10L)
                .findFirst();

        assertTrue(conflict.isPresent(), "Should have a conflict for tight job");
        assertEquals("Cannot start in time", conflict.get().getReason());
    }

    // ========== Bottleneck First Strategy ==========

    @Test
    void testBottleneckIdentifiesMostLoadedResource() {
        ScheduleRequest request = buildRequest();

        // SMT has 60 + 45 = 105 min total load, REFLOW has 30 min
        // So SMT should be identified as bottleneck
        ResourceInfo bottleneck = bottleneckStrategy.identifyBottleneck(request);

        assertEquals("smt", bottleneck.getType(), "SMT with 105min total should be the bottleneck");
        assertEquals(1L, bottleneck.getId());
    }

    @Test
    void testBottleneckGroupsSameProduct() {
        // Add another P1 job for SMT to verify product grouping
        ScheduleJob jobD = ScheduleJob.builder()
                .id(4L).code("JOB-D").productName("Product P1").productId(1L)
                .operationName("smt").requiredResourceType("smt")
                .processingTimeMin(30)
                .dueDate(LocalDateTime.of(2026, 3, 1, 11, 0))
                .build();

        List<ScheduleJob> extendedJobs = new ArrayList<>(jobs);
        extendedJobs.add(jobD);

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(extendedJobs)
                .resources(resources)
                .setupTimes(setupTimes)
                .scheduleStart(SCHEDULE_START)
                .build();

        ScheduleResult result = bottleneckStrategy.schedule(request);

        // Get bottleneck (SMT) operations sorted by start time
        List<ScheduledOperation> smtOps = result.getOperations().stream()
                .filter(op -> op.getResourceId() == 1L)
                .sorted(Comparator.comparing(ScheduledOperation::getStartTime))
                .toList();

        // With groupByProductWithEdd, jobs with same productId should be grouped together
        // to minimize changeover: either [P2(B), P1(A), P1(D)] or [P1(A), P1(D), P2(B)]
        // Since grouping sorts by earliest due date in group:
        //   P2 group (B due 10:00) earliest due = 10:00
        //   P1 group (A due 12:00, D due 11:00) earliest due = 11:00
        // So P2 first, then P1 group
        // Jobs within P1 group sorted by due: D (11:00) then A (12:00)

        // Check that consecutive same-product jobs have 0 setup time
        boolean foundConsecutiveSameProduct = false;
        for (int i = 0; i < smtOps.size() - 1; i++) {
            if (smtOps.get(i).getProductName().equals(smtOps.get(i + 1).getProductName())) {
                foundConsecutiveSameProduct = true;
                assertEquals(0, smtOps.get(i + 1).getSetupTimeMin(),
                        "Same product consecutive ops should have 0 setup time");
            }
        }
        assertTrue(foundConsecutiveSameProduct, "Should have at least one pair of same-product consecutive operations");
    }

    @Test
    void testBottleneckBufferTime() {
        ScheduleResult result = bottleneckStrategy.schedule(buildRequest());

        // Bottleneck (SMT) jobs should start 30 min after schedule start (buffer)
        List<ScheduledOperation> smtOps = result.getOperations().stream()
                .filter(op -> op.getResourceId() == 1L)
                .sorted(Comparator.comparing(ScheduledOperation::getStartTime))
                .toList();

        assertFalse(smtOps.isEmpty());
        LocalDateTime expectedBottleneckStart = SCHEDULE_START.plusMinutes(30);
        assertEquals(expectedBottleneckStart, smtOps.get(0).getStartTime(),
                "First bottleneck job should start 30 min after schedule start (buffer)");
    }

    // ========== SchedulingEngine ==========

    @Test
    void testEngineDispatchesToCorrectStrategy() {
        Map<String, SchedulingStrategy> strategyMap = new HashMap<>();
        strategyMap.put("forwardFifo", fifoStrategy);
        strategyMap.put("forwardEdd", eddStrategy);
        strategyMap.put("backward", backwardStrategy);
        strategyMap.put("bottleneckFirst", bottleneckStrategy);

        GanttDataBuilder ganttBuilder = new GanttDataBuilder();
        SchedulingEngine engine = new SchedulingEngine(strategyMap, ganttBuilder);

        ScheduleResult fifoResult = engine.schedule(buildRequest(), "forwardFifo");
        assertEquals("forward_fifo", fifoResult.getStrategy());

        ScheduleResult eddResult = engine.schedule(buildRequest(), "forwardEdd");
        assertEquals("forward_edd", eddResult.getStrategy());

        ScheduleResult backwardResult = engine.schedule(buildRequest(), "backward");
        assertEquals("backward", backwardResult.getStrategy());

        ScheduleResult bottleneckResult = engine.schedule(buildRequest(), "bottleneckFirst");
        assertEquals("bottleneck_first", bottleneckResult.getStrategy());
    }

    @Test
    void testEngineThrowsOnUnknownStrategy() {
        Map<String, SchedulingStrategy> strategyMap = new HashMap<>();
        strategyMap.put("forwardFifo", fifoStrategy);
        GanttDataBuilder ganttBuilder = new GanttDataBuilder();
        SchedulingEngine engine = new SchedulingEngine(strategyMap, ganttBuilder);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.schedule(buildRequest(), "nonExistent"));
        assertTrue(ex.getMessage().contains("Unknown strategy"));
    }

    @Test
    void testEngineGetAvailableStrategies() {
        Map<String, SchedulingStrategy> strategyMap = new LinkedHashMap<>();
        strategyMap.put("forwardFifo", fifoStrategy);
        strategyMap.put("forwardEdd", eddStrategy);
        strategyMap.put("backward", backwardStrategy);
        strategyMap.put("bottleneckFirst", bottleneckStrategy);

        GanttDataBuilder ganttBuilder = new GanttDataBuilder();
        SchedulingEngine engine = new SchedulingEngine(strategyMap, ganttBuilder);

        List<String> strategies = engine.getAvailableStrategies();
        assertEquals(4, strategies.size());

        // Verify each strategy has "name: description" format
        assertTrue(strategies.stream().anyMatch(s -> s.startsWith("forward_fifo:")));
        assertTrue(strategies.stream().anyMatch(s -> s.startsWith("forward_edd:")));
        assertTrue(strategies.stream().anyMatch(s -> s.startsWith("backward:")));
        assertTrue(strategies.stream().anyMatch(s -> s.startsWith("bottleneck_first:")));
    }

    // ========== Additional edge case tests ==========

    @Test
    void testFifoNoResourceMatchReturnsConflict() {
        // Job requires a resource type that doesn't exist
        ScheduleJob orphanJob = ScheduleJob.builder()
                .id(99L).code("orphan").productName("P1").productId(1L)
                .operationName("aoi").requiredResourceType("aoi")
                .processingTimeMin(20)
                .dueDate(LocalDateTime.of(2026, 3, 1, 12, 0))
                .build();

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(List.of(orphanJob))
                .resources(resources)
                .scheduleStart(SCHEDULE_START)
                .build();

        ScheduleResult result = fifoStrategy.schedule(request);

        assertEquals(0, result.getOperations().size());
        assertEquals(1, result.getConflicts().size());
        assertTrue(result.getConflicts().get(0).getReason().contains("No available resource"));
    }

    @Test
    void testEddNoSetupWhenSameProduct() {
        // Two jobs for same product on same resource → no setup time
        ScheduleJob job1 = ScheduleJob.builder()
                .id(1L).code("J1").productName("P1").productId(1L)
                .operationName("smt").requiredResourceType("smt")
                .processingTimeMin(30)
                .arrivalTime(SCHEDULE_START)
                .dueDate(LocalDateTime.of(2026, 3, 1, 10, 0))
                .build();

        ScheduleJob job2 = ScheduleJob.builder()
                .id(2L).code("J2").productName("P1").productId(1L)
                .operationName("smt").requiredResourceType("smt")
                .processingTimeMin(30)
                .arrivalTime(SCHEDULE_START)
                .dueDate(LocalDateTime.of(2026, 3, 1, 11, 0))
                .build();

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(List.of(job1, job2))
                .resources(resources)
                .setupTimes(setupTimes)
                .scheduleStart(SCHEDULE_START)
                .build();

        ScheduleResult result = eddStrategy.schedule(request);

        List<ScheduledOperation> ops = result.getOperations().stream()
                .filter(op -> op.getResourceId() == 1L)
                .sorted(Comparator.comparing(ScheduledOperation::getStartTime))
                .toList();

        assertEquals(2, ops.size());
        assertEquals(0, ops.get(0).getSetupTimeMin());
        assertEquals(0, ops.get(1).getSetupTimeMin(), "Same product should have no setup time");
        // J1 ends at 8:30, J2 starts at 8:30 (no gap)
        assertEquals(ops.get(0).getEndTime(), ops.get(1).getStartTime());
    }
}
