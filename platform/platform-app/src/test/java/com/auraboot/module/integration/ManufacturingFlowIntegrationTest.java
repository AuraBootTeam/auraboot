package com.auraboot.module.integration;

import com.auraboot.module.aps.dto.*;
import com.auraboot.module.aps.engine.BottleneckFirstStrategy;
import com.auraboot.module.aps.engine.ForwardFifoStrategy;
import com.auraboot.module.aps.engine.SchedulingEngine;
import com.auraboot.module.aps.engine.SchedulingStrategy;
import com.auraboot.module.aps.visualization.GanttDataBuilder;
import com.auraboot.module.mrp.dto.*;
import com.auraboot.module.mrp.engine.*;
import com.auraboot.module.mrp.port.BomQueryPort;
import com.auraboot.module.mrp.port.InventoryQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Service-layer integration test that wires real MRP + APS engines
 * with mocked ports/adapters to verify the complete manufacturing
 * flow: demand -> MRP planning -> planned orders -> APS scheduling.
 *
 * <p>Not a Spring Boot test — instantiates real engine instances
 * directly with mocked dependencies for fast execution.
 */
class ManufacturingFlowIntegrationTest {

    // Mocked ports
    private BomQueryPort bomPort;
    private InventoryQueryPort inventoryPort;

    // Real MRP engine components
    private BomExplosionService bomExplosion;
    private NettingService netting;
    private LotSizingStrategyFactory lotSizingFactory;
    private AlternativeMaterialResolver altResolver;
    private LeadTimeService leadTimeService;
    private MrpPlanningEngine mrpEngine;

    // Real APS engine components
    private SchedulingEngine apsEngine;
    private GanttDataBuilder ganttBuilder;

    // Material IDs
    private static final Long MAT_PCBA = 100L;      // Finished PCBA board
    private static final Long MAT_PCB = 200L;        // PCB (subassembly)
    private static final Long MAT_IC = 300L;         // IC chip (raw material)
    private static final Long MAT_RESISTOR = 400L;   // Resistor (raw material)
    private static final Long MAT_CAPACITOR = 500L;  // Capacitor (raw material)

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 1L, "test-user-pid", "test-user");
        bomPort = mock(BomQueryPort.class);
        inventoryPort = mock(InventoryQueryPort.class);

        // Wire up real MRP engine with mocked ports
        bomExplosion = new BomExplosionService(bomPort);
        netting = new NettingService(inventoryPort);
        LotForLotStrategy lflStrategy = new LotForLotStrategy();
        lotSizingFactory = new LotSizingStrategyFactory(List.of(lflStrategy));
        altResolver = new AlternativeMaterialResolver(bomPort, inventoryPort);
        leadTimeService = new LeadTimeService(bomPort);

        TenantClock tenantClock = mock(TenantClock.class);
        lenient().when(tenantClock.businessDate(any())).thenReturn(LocalDate.of(2026, 1, 1));

        mrpEngine = new MrpPlanningEngine(
                bomExplosion, netting, lotSizingFactory, altResolver,
                leadTimeService, bomPort, inventoryPort, tenantClock
        );

        // Wire up real APS engine with real strategies
        ganttBuilder = new GanttDataBuilder();
        ForwardFifoStrategy fifoStrategy = new ForwardFifoStrategy();
        BottleneckFirstStrategy bottleneckStrategy = new BottleneckFirstStrategy();
        Map<String, SchedulingStrategy> strategies = new HashMap<>();
        strategies.put("forward_fifo", fifoStrategy);
        strategies.put("bottleneck_first", bottleneckStrategy);
        apsEngine = new SchedulingEngine(strategies, ganttBuilder);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // ========== Helper methods ==========

    private void setupSingleLevelBom() {
        // PCBA has BOM: IC + Resistor + Capacitor
        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of(
                MAT_PCBA, 0,
                MAT_IC, 1,
                MAT_RESISTOR, 1,
                MAT_CAPACITOR, 1
        ));
        when(bomPort.hasBom(MAT_PCBA)).thenReturn(true);
        when(bomPort.hasBom(MAT_IC)).thenReturn(false);
        when(bomPort.hasBom(MAT_RESISTOR)).thenReturn(false);
        when(bomPort.hasBom(MAT_CAPACITOR)).thenReturn(false);

        when(bomPort.getBomLines(eq(MAT_PCBA), any())).thenReturn(List.of(
                BomLineDto.builder().id(1L).parentMaterialId(MAT_PCBA).childMaterialId(MAT_IC)
                        .childMaterialName("IC-U1").quantityPer(new BigDecimal("2")).build(),
                BomLineDto.builder().id(2L).parentMaterialId(MAT_PCBA).childMaterialId(MAT_RESISTOR)
                        .childMaterialName("R-10K").quantityPer(new BigDecimal("10")).build(),
                BomLineDto.builder().id(3L).parentMaterialId(MAT_PCBA).childMaterialId(MAT_CAPACITOR)
                        .childMaterialName("C-100nF").quantityPer(new BigDecimal("5")).build()
        ));

        // Lot sizing: all LFL
        when(bomPort.getLotSizingPolicy(anyLong())).thenReturn("lfl");
        when(bomPort.getMoq(anyLong())).thenReturn(BigDecimal.ZERO);

        // Lead times
        when(bomPort.getLeadTime(MAT_PCBA)).thenReturn(5);
        when(bomPort.getLeadTime(MAT_IC)).thenReturn(14);
        when(bomPort.getLeadTime(MAT_RESISTOR)).thenReturn(3);
        when(bomPort.getLeadTime(MAT_CAPACITOR)).thenReturn(3);
    }

    private void setupInventoryAllZero() {
        when(inventoryPort.getOnHandQty(anyLong(), any())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAllocatedQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAvailableQty(anyLong())).thenReturn(BigDecimal.ZERO);
    }

    // ========== Test 1: MRP run generates planned orders ==========

    @Test
    void testMrpRunGeneratesPlannedOrders() {
        setupSingleLevelBom();
        setupInventoryAllZero();

        LocalDate needDate = LocalDate.now().plusDays(30);
        List<DemandEntry> demands = List.of(
                new DemandEntry(MAT_PCBA, "PCBA-Board-v1", new BigDecimal("100"), needDate)
        );

        MrpResult result = mrpEngine.runMrp(demands, 90);

        assertNotNull(result);
        assertNotNull(result.getPlannedOrders());
        // Should generate planned orders:
        // 1 PRODUCTION for PCBA (finished good with BOM)
        // 3 PURCHASE orders for IC, Resistor, Capacitor (raw materials)
        assertTrue(result.getPlannedOrders().size() >= 4,
                "Expected at least 4 planned orders (1 production + 3 purchase), got: " + result.getPlannedOrders().size());

        // Verify PCBA gets a PRODUCTION order
        List<PlannedOrderDto> pcbaOrders = result.getPlannedOrders().stream()
                .filter(o -> o.getMaterialId().equals(MAT_PCBA))
                .collect(Collectors.toList());
        assertEquals(1, pcbaOrders.size());
        assertEquals("production", pcbaOrders.get(0).getOrderType());
        assertEquals(0, new BigDecimal("100").compareTo(pcbaOrders.get(0).getOrderQty()));

        // Verify raw materials get PURCHASE orders
        List<PlannedOrderDto> purchaseOrders = result.getPlannedOrders().stream()
                .filter(o -> "purchase".equals(o.getOrderType()))
                .collect(Collectors.toList());
        assertTrue(purchaseOrders.size() >= 3,
                "Expected at least 3 purchase orders for raw materials");
    }

    // ========== Test 2: MRP BOM explosion cascades demands ==========

    @Test
    void testMrpBomExplosion() {
        // Setup 2-level BOM: PCBA -> PCB (subassembly) -> IC, Resistor
        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of(
                MAT_PCBA, 0,
                MAT_PCB, 1,
                MAT_IC, 2,
                MAT_RESISTOR, 2
        ));
        when(bomPort.hasBom(MAT_PCBA)).thenReturn(true);
        when(bomPort.hasBom(MAT_PCB)).thenReturn(true);
        when(bomPort.hasBom(MAT_IC)).thenReturn(false);
        when(bomPort.hasBom(MAT_RESISTOR)).thenReturn(false);

        // PCBA -> 1x PCB
        when(bomPort.getBomLines(eq(MAT_PCBA), any())).thenReturn(List.of(
                BomLineDto.builder().id(1L).parentMaterialId(MAT_PCBA).childMaterialId(MAT_PCB)
                        .childMaterialName("PCB-SubAssy").quantityPer(new BigDecimal("1")).build()
        ));
        // PCB -> 3x IC + 20x Resistor
        when(bomPort.getBomLines(eq(MAT_PCB), any())).thenReturn(List.of(
                BomLineDto.builder().id(2L).parentMaterialId(MAT_PCB).childMaterialId(MAT_IC)
                        .childMaterialName("IC-Main").quantityPer(new BigDecimal("3")).build(),
                BomLineDto.builder().id(3L).parentMaterialId(MAT_PCB).childMaterialId(MAT_RESISTOR)
                        .childMaterialName("R-1K").quantityPer(new BigDecimal("20")).build()
        ));

        when(bomPort.getLotSizingPolicy(anyLong())).thenReturn("lfl");
        when(bomPort.getMoq(anyLong())).thenReturn(BigDecimal.ZERO);
        when(bomPort.getLeadTime(anyLong())).thenReturn(7);
        setupInventoryAllZero();

        LocalDate needDate = LocalDate.now().plusDays(60);
        List<DemandEntry> demands = List.of(
                new DemandEntry(MAT_PCBA, "PCBA-v2", new BigDecimal("50"), needDate)
        );

        MrpResult result = mrpEngine.runMrp(demands, 90);

        assertNotNull(result);
        // Expected orders: PCBA (PRODUCTION), PCB (PRODUCTION), IC (PURCHASE), Resistor (PURCHASE)
        assertEquals(4, result.getPlannedOrders().size(),
                "Expected 4 planned orders across 2 BOM levels");

        // Check cascaded quantities
        // PCBA: 50 units → PCB: 50 × 1 = 50 units → IC: 50 × 3 = 150 units, Resistor: 50 × 20 = 1000 units
        PlannedOrderDto icOrder = result.getPlannedOrders().stream()
                .filter(o -> o.getMaterialId().equals(MAT_IC)).findFirst().orElse(null);
        assertNotNull(icOrder, "Should have a planned order for IC");
        assertEquals(0, new BigDecimal("150").compareTo(icOrder.getOrderQty()),
                "IC demand should be 50 × 3 = 150");

        PlannedOrderDto resistorOrder = result.getPlannedOrders().stream()
                .filter(o -> o.getMaterialId().equals(MAT_RESISTOR)).findFirst().orElse(null);
        assertNotNull(resistorOrder, "Should have a planned order for Resistor");
        assertEquals(0, new BigDecimal("1000").compareTo(resistorOrder.getOrderQty()),
                "Resistor demand should be 50 × 20 = 1000");
    }

    // ========== Test 3: APS scheduling with resources ==========

    @Test
    void testApsSchedulingWithResources() {
        LocalDateTime scheduleStart = LocalDateTime.of(2026, 3, 1, 8, 0);

        // 2 resources: SMT line and Reflow oven
        List<ResourceInfo> resources = List.of(
                ResourceInfo.builder().id(1L).name("SMT-Line-1").type("smt")
                        .capacityPerHour(new BigDecimal("60")).build(),
                ResourceInfo.builder().id(2L).name("Reflow-Oven-1").type("reflow")
                        .capacityPerHour(new BigDecimal("30")).build()
        );

        // 3 jobs: 2 SMT jobs + 1 Reflow job
        List<ScheduleJob> jobs = List.of(
                ScheduleJob.builder().id(1L).code("JOB-001").productName("PCBA-A").productId(10L)
                        .operationName("smt").requiredResourceType("smt")
                        .processingTimeMin(60).dueDate(scheduleStart.plusHours(4))
                        .priority(1).build(),
                ScheduleJob.builder().id(2L).code("JOB-002").productName("PCBA-B").productId(20L)
                        .operationName("smt").requiredResourceType("smt")
                        .processingTimeMin(45).dueDate(scheduleStart.plusHours(5))
                        .priority(2).build(),
                ScheduleJob.builder().id(3L).code("JOB-003").productName("PCBA-A").productId(10L)
                        .operationName("reflow").requiredResourceType("reflow")
                        .processingTimeMin(30).dueDate(scheduleStart.plusHours(6))
                        .priority(1).build()
        );

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(jobs)
                .resources(resources)
                .scheduleStart(scheduleStart)
                .build();

        ScheduleResult result = apsEngine.schedule(request, "forward_fifo");

        assertNotNull(result);
        assertEquals("forward_fifo", result.getStrategy());
        assertEquals(3, result.getOperations().size(), "All 3 jobs should be scheduled");

        // Verify SMT jobs go to SMT resource
        List<ScheduledOperation> smtOps = result.getOperations().stream()
                .filter(op -> "smt".equals(op.getOperationName()))
                .collect(Collectors.toList());
        assertEquals(2, smtOps.size());
        assertTrue(smtOps.stream().allMatch(op -> op.getResourceId().equals(1L)),
                "All SMT jobs should be on SMT resource (id=1)");

        // Verify REFLOW job goes to REFLOW resource
        List<ScheduledOperation> reflowOps = result.getOperations().stream()
                .filter(op -> "reflow".equals(op.getOperationName()))
                .collect(Collectors.toList());
        assertEquals(1, reflowOps.size());
        assertEquals(2L, reflowOps.get(0).getResourceId());

        // Verify no time overlaps on same resource
        for (ResourceInfo res : resources) {
            List<ScheduledOperation> resOps = result.getOperations().stream()
                    .filter(op -> op.getResourceId().equals(res.getId()))
                    .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                    .collect(Collectors.toList());
            for (int i = 1; i < resOps.size(); i++) {
                assertFalse(resOps.get(i).getStartTime().isBefore(resOps.get(i - 1).getEndTime()),
                        "Operations on resource " + res.getName() + " should not overlap");
            }
        }

        // Verify resource utilization is present
        assertNotNull(result.getResourceUtilization());
        assertFalse(result.getResourceUtilization().isEmpty());
    }

    // ========== Test 4: APS Gantt data generated ==========

    @Test
    void testApsGanttDataGenerated() {
        LocalDateTime scheduleStart = LocalDateTime.of(2026, 3, 1, 8, 0);

        List<ResourceInfo> resources = List.of(
                ResourceInfo.builder().id(1L).name("SMT-Line-1").type("smt")
                        .capacityPerHour(new BigDecimal("60")).build(),
                ResourceInfo.builder().id(2L).name("AOI-Station-1").type("aoi")
                        .capacityPerHour(new BigDecimal("40")).build()
        );

        List<ScheduleJob> jobs = List.of(
                ScheduleJob.builder().id(1L).code("G-001").productName("Board-X").productId(10L)
                        .operationName("smt").requiredResourceType("smt")
                        .processingTimeMin(30).dueDate(scheduleStart.plusHours(2))
                        .priority(1).build(),
                ScheduleJob.builder().id(2L).code("G-002").productName("Board-X").productId(10L)
                        .operationName("aoi").requiredResourceType("aoi")
                        .processingTimeMin(20).dueDate(scheduleStart.plusHours(3))
                        .priority(1).build()
        );

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(jobs)
                .resources(resources)
                .scheduleStart(scheduleStart)
                .build();

        GanttData ganttData = apsEngine.scheduleWithGantt(request, "forward_fifo");

        assertNotNull(ganttData);
        assertNotNull(ganttData.getStrategy());
        assertEquals("forward_fifo", ganttData.getStrategy());

        // Rows: one per resource that has operations
        assertNotNull(ganttData.getRows());
        assertEquals(2, ganttData.getRows().size(), "Should have 2 resource rows");

        // Tasks: one per job
        assertNotNull(ganttData.getTasks());
        assertEquals(2, ganttData.getTasks().size(), "Should have 2 gantt tasks");

        // Verify task details
        GanttTask smtTask = ganttData.getTasks().stream()
                .filter(t -> "smt".equals(t.getOperationName())).findFirst().orElse(null);
        assertNotNull(smtTask);
        assertEquals("Board-X", smtTask.getProductName());
        assertEquals(1L, smtTask.getResourceId());
        assertEquals("#1890ff", smtTask.getColor(), "SMT should have blue color");

        GanttTask aoiTask = ganttData.getTasks().stream()
                .filter(t -> "aoi".equals(t.getOperationName())).findFirst().orElse(null);
        assertNotNull(aoiTask);
        assertEquals("#52c41a", aoiTask.getColor(), "AOI should have green color");

        // Resource utilization
        assertNotNull(ganttData.getResourceUtilization());
    }

    // ========== Test 5: End-to-end MRP → APS flow ==========

    @Test
    void testEndToEndMrpToAps() {
        // Phase 1: Setup MRP data
        setupSingleLevelBom();

        // Partial inventory: have some IC in stock
        when(inventoryPort.getOnHandQty(eq(MAT_IC), any())).thenReturn(new BigDecimal("100"));
        when(inventoryPort.getOnHandQty(eq(MAT_PCBA), any())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getOnHandQty(eq(MAT_RESISTOR), any())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getOnHandQty(eq(MAT_CAPACITOR), any())).thenReturn(BigDecimal.ZERO);

        when(inventoryPort.getInTransitQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAllocatedQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAvailableQty(anyLong())).thenReturn(BigDecimal.ZERO);

        LocalDate needDate = LocalDate.now().plusDays(30);
        List<DemandEntry> demands = List.of(
                new DemandEntry(MAT_PCBA, "PCBA-Board-v1", new BigDecimal("100"), needDate)
        );

        // Phase 2: Run MRP
        MrpResult mrpResult = mrpEngine.runMrp(demands, 90);

        assertNotNull(mrpResult);
        assertFalse(mrpResult.getPlannedOrders().isEmpty(),
                "MRP should produce planned orders");

        // The PCBA should generate a PRODUCTION order
        List<PlannedOrderDto> productionOrders = mrpResult.getPlannedOrders().stream()
                .filter(o -> "production".equals(o.getOrderType()))
                .collect(Collectors.toList());
        assertFalse(productionOrders.isEmpty(), "Should have at least 1 production order");

        // IC demand: 100 units PCBA × 2 IC/PCBA = 200 IC needed, 100 on hand → net = 100
        PlannedOrderDto icOrder = mrpResult.getPlannedOrders().stream()
                .filter(o -> o.getMaterialId().equals(MAT_IC)).findFirst().orElse(null);
        assertNotNull(icOrder, "Should have a planned order for IC despite partial stock");
        assertEquals(0, new BigDecimal("100").compareTo(icOrder.getOrderQty()),
                "IC net demand should be 200 - 100 = 100 units");

        // Phase 3: Convert production orders to APS schedule jobs
        LocalDateTime scheduleStart = LocalDateTime.of(2026, 3, 1, 8, 0);
        List<ScheduleJob> apsJobs = new ArrayList<>();
        long jobIdCounter = 1;

        for (PlannedOrderDto po : productionOrders) {
            apsJobs.add(ScheduleJob.builder()
                    .id(jobIdCounter++)
                    .code("WO-" + po.getMaterialId())
                    .productName("Product-" + po.getMaterialId())
                    .productId(po.getMaterialId())
                    .operationName("smt")
                    .requiredResourceType("smt")
                    .processingTimeMin(po.getOrderQty().intValue() * 2) // 2 min per unit
                    .dueDate(scheduleStart.plusDays(
                            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), po.getNeedDate())))
                    .priority(1)
                    .build());
        }

        assertFalse(apsJobs.isEmpty(), "Should have APS jobs from production orders");

        // Phase 4: Run APS scheduling
        List<ResourceInfo> resources = List.of(
                ResourceInfo.builder().id(1L).name("SMT-Line-1").type("smt")
                        .capacityPerHour(new BigDecimal("30")).build(),
                ResourceInfo.builder().id(2L).name("SMT-Line-2").type("smt")
                        .capacityPerHour(new BigDecimal("30")).build()
        );

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(apsJobs)
                .resources(resources)
                .scheduleStart(scheduleStart)
                .build();

        ScheduleResult apsResult = apsEngine.schedule(request, "forward_fifo");

        assertNotNull(apsResult);
        assertEquals(apsJobs.size(), apsResult.getOperations().size(),
                "All production jobs should be scheduled");

        // Verify schedule is coherent
        for (ScheduledOperation op : apsResult.getOperations()) {
            assertNotNull(op.getStartTime(), "Each operation should have a start time");
            assertNotNull(op.getEndTime(), "Each operation should have an end time");
            assertTrue(op.getEndTime().isAfter(op.getStartTime()),
                    "End time should be after start time");
            assertTrue(op.getStartTime().compareTo(scheduleStart) >= 0,
                    "Operations should not start before schedule start");
        }

        // Phase 5: Verify Gantt data can be built from the result
        GanttData gantt = ganttBuilder.build(apsResult);
        assertNotNull(gantt);
        assertFalse(gantt.getTasks().isEmpty(), "Gantt should have tasks");
        assertFalse(gantt.getRows().isEmpty(), "Gantt should have resource rows");
    }

    // ========== Test 6: MRP with partial inventory reduces planned order qty ==========

    @Test
    void testMrpNettingReducesDemand() {
        setupSingleLevelBom();

        // IC: 150 on hand, need 200 (100 PCBA × 2 IC each) → net 50
        when(inventoryPort.getOnHandQty(eq(MAT_IC), any())).thenReturn(new BigDecimal("150"));
        // Resistor: 500 on hand, need 1000 (100 PCBA × 10 each) → net 500
        when(inventoryPort.getOnHandQty(eq(MAT_RESISTOR), any())).thenReturn(new BigDecimal("500"));
        // Capacitor: 600 on hand, need 500 (100 PCBA × 5 each) → net 0 (no order needed!)
        when(inventoryPort.getOnHandQty(eq(MAT_CAPACITOR), any())).thenReturn(new BigDecimal("600"));
        // PCBA: 0 on hand
        when(inventoryPort.getOnHandQty(eq(MAT_PCBA), any())).thenReturn(BigDecimal.ZERO);

        when(inventoryPort.getInTransitQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAllocatedQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAvailableQty(anyLong())).thenReturn(BigDecimal.ZERO);

        LocalDate needDate = LocalDate.now().plusDays(30);
        MrpResult result = mrpEngine.runMrp(
                List.of(new DemandEntry(MAT_PCBA, "PCBA-v1", new BigDecimal("100"), needDate)),
                90
        );

        // IC: need 200, have 150 → order 50
        PlannedOrderDto icOrder = result.getPlannedOrders().stream()
                .filter(o -> o.getMaterialId().equals(MAT_IC)).findFirst().orElse(null);
        assertNotNull(icOrder);
        assertEquals(0, new BigDecimal("50").compareTo(icOrder.getOrderQty()),
                "IC net demand should be 200 - 150 = 50");

        // Resistor: need 1000, have 500 → order 500
        PlannedOrderDto resistorOrder = result.getPlannedOrders().stream()
                .filter(o -> o.getMaterialId().equals(MAT_RESISTOR)).findFirst().orElse(null);
        assertNotNull(resistorOrder);
        assertEquals(0, new BigDecimal("500").compareTo(resistorOrder.getOrderQty()),
                "Resistor net demand should be 1000 - 500 = 500");

        // Capacitor: need 500, have 600 → no order
        PlannedOrderDto capOrder = result.getPlannedOrders().stream()
                .filter(o -> o.getMaterialId().equals(MAT_CAPACITOR)).findFirst().orElse(null);
        assertNull(capOrder, "Capacitor should NOT have a planned order (sufficient stock)");
    }

    // ========== Test 7: APS BottleneckFirst strategy identifies bottleneck ==========

    @Test
    void testApsBottleneckFirstStrategy() {
        LocalDateTime scheduleStart = LocalDateTime.of(2026, 3, 1, 8, 0);

        // SMT is the bottleneck (3 jobs × 60 min = 180 min total)
        // Reflow has only 1 job × 30 min = 30 min total
        List<ResourceInfo> resources = List.of(
                ResourceInfo.builder().id(1L).name("SMT-Line").type("smt")
                        .capacityPerHour(new BigDecimal("30")).build(),
                ResourceInfo.builder().id(2L).name("Reflow-Oven").type("reflow")
                        .capacityPerHour(new BigDecimal("60")).build()
        );

        List<ScheduleJob> jobs = List.of(
                ScheduleJob.builder().id(1L).code("BN-001").productName("Board-A").productId(10L)
                        .operationName("smt").requiredResourceType("smt")
                        .processingTimeMin(60).dueDate(scheduleStart.plusHours(6))
                        .priority(1).build(),
                ScheduleJob.builder().id(2L).code("BN-002").productName("Board-B").productId(20L)
                        .operationName("smt").requiredResourceType("smt")
                        .processingTimeMin(60).dueDate(scheduleStart.plusHours(8))
                        .priority(2).build(),
                ScheduleJob.builder().id(3L).code("BN-003").productName("Board-C").productId(30L)
                        .operationName("smt").requiredResourceType("smt")
                        .processingTimeMin(60).dueDate(scheduleStart.plusHours(10))
                        .priority(3).build(),
                ScheduleJob.builder().id(4L).code("BN-004").productName("Board-A").productId(10L)
                        .operationName("reflow").requiredResourceType("reflow")
                        .processingTimeMin(30).dueDate(scheduleStart.plusHours(12))
                        .priority(1).build()
        );

        ScheduleRequest request = ScheduleRequest.builder()
                .jobs(jobs)
                .resources(resources)
                .scheduleStart(scheduleStart)
                .build();

        ScheduleResult result = apsEngine.schedule(request, "bottleneck_first");

        assertNotNull(result);
        assertEquals("bottleneck_first", result.getStrategy());
        assertEquals(4, result.getOperations().size(), "All 4 jobs should be scheduled");

        // Verify SMT resource has higher utilization than REFLOW
        Double smtUtil = result.getResourceUtilization().get(1L);
        Double reflowUtil = result.getResourceUtilization().get(2L);
        assertNotNull(smtUtil, "SMT utilization should be calculated");
        assertNotNull(reflowUtil, "Reflow utilization should be calculated");
        assertTrue(smtUtil > reflowUtil,
                "SMT (bottleneck) should have higher utilization than REFLOW");
    }
}
