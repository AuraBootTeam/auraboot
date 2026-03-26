package com.auraboot.module.mrp.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.module.mrp.dto.BomLineDto;
import com.auraboot.module.mrp.dto.DemandEntry;
import com.auraboot.module.mrp.dto.MrpResult;
import com.auraboot.module.mrp.dto.PlannedOrderDto;
import com.auraboot.module.mrp.port.BomQueryPort;
import com.auraboot.module.mrp.port.InventoryQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MrpPlanningEngineTest {

    @Mock private BomQueryPort bomPort;
    @Mock private InventoryQueryPort inventoryPort;

    private MrpPlanningEngine engine;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 1L, "test-user-pid", "test-user");
        BomExplosionService bomExplosion = new BomExplosionService(bomPort);
        NettingService netting = new NettingService(inventoryPort);

        LotForLotStrategy lfl = new LotForLotStrategy();
        LotSizingStrategyFactory factory = new LotSizingStrategyFactory(List.of(lfl));

        AlternativeMaterialResolver altResolver = new AlternativeMaterialResolver(bomPort, inventoryPort);
        LeadTimeService leadTimeService = new LeadTimeService(bomPort);

        TenantClock tenantClock = mock(TenantClock.class);
        lenient().when(tenantClock.businessDate(any())).thenReturn(LocalDate.of(2026, 1, 1));

        engine = new MrpPlanningEngine(bomExplosion, netting, factory, altResolver, leadTimeService, bomPort, inventoryPort, tenantClock);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void testFullMrpWith2LevelBom() {
        // A(1) is finished good, has BOM: B(2) qty=2, C(3) qty=3
        // B and C are purchased materials (no BOM)
        // Demand: A=100, needDate=2026-05-01
        Long matA = 1L, matB = 2L, matC = 3L;
        LocalDate needDate = LocalDate.of(2026, 5, 1);

        // LLC: A=0, B=1, C=1
        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of(matA, 0, matB, 1, matC, 1));

        // Inventory: all zero
        when(inventoryPort.getOnHandQty(anyLong(), any())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAllocatedQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(anyLong())).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(anyLong())).thenReturn(BigDecimal.ZERO);

        // BOM: A has children B(qty=2) and C(qty=3)
        when(bomPort.hasBom(matA)).thenReturn(true);
        when(bomPort.hasBom(matB)).thenReturn(false);
        when(bomPort.hasBom(matC)).thenReturn(false);

        when(bomPort.getBomLines(eq(matA), any())).thenReturn(List.of(
            BomLineDto.builder().id(101L).childMaterialId(matB).childMaterialName("B").quantityPer(new BigDecimal("2")).lossRate(BigDecimal.ZERO).build(),
            BomLineDto.builder().id(102L).childMaterialId(matC).childMaterialName("C").quantityPer(new BigDecimal("3")).lossRate(BigDecimal.ZERO).build()
        ));

        when(bomPort.getLotSizingPolicy(anyLong())).thenReturn("lfl");
        when(bomPort.getMoq(anyLong())).thenReturn(BigDecimal.ONE);
        when(bomPort.getLeadTime(anyLong())).thenReturn(30);

        List<DemandEntry> demands = List.of(new DemandEntry(matA, "A", new BigDecimal("100"), needDate));

        MrpResult result = engine.runMrp(demands, 90);

        // Should have 3 planned orders: A(PRODUCTION), B(PURCHASE), C(PURCHASE)
        assertEquals(3, result.getPlannedOrders().size());

        PlannedOrderDto orderA = result.getPlannedOrders().stream()
            .filter(o -> o.getMaterialId().equals(matA)).findFirst().orElseThrow();
        assertEquals("production", orderA.getOrderType());
        assertEquals(0, new BigDecimal("100").compareTo(orderA.getOrderQty()));

        PlannedOrderDto orderB = result.getPlannedOrders().stream()
            .filter(o -> o.getMaterialId().equals(matB)).findFirst().orElseThrow();
        assertEquals("purchase", orderB.getOrderType());
        // A=100, B qty_per=2 -> B=200
        assertEquals(0, new BigDecimal("200").compareTo(orderB.getOrderQty()));

        PlannedOrderDto orderC = result.getPlannedOrders().stream()
            .filter(o -> o.getMaterialId().equals(matC)).findFirst().orElseThrow();
        assertEquals("purchase", orderC.getOrderType());
        // A=100, C qty_per=3 -> C=300
        assertEquals(0, new BigDecimal("300").compareTo(orderC.getOrderQty()));
    }

    @Test
    void testMrpSkipsWhenStockSufficient() {
        Long matA = 1L;
        LocalDate needDate = LocalDate.of(2026, 5, 1);

        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of(matA, 0));

        // Sufficient stock: onHand=200 >= demand=100
        when(inventoryPort.getOnHandQty(matA, null)).thenReturn(new BigDecimal("200"));
        when(inventoryPort.getAllocatedQty(matA)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(matA)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(matA)).thenReturn(BigDecimal.ZERO);

        List<DemandEntry> demands = List.of(new DemandEntry(matA, "A", new BigDecimal("100"), needDate));

        MrpResult result = engine.runMrp(demands, 90);

        assertTrue(result.getPlannedOrders().isEmpty());
    }

    @Test
    void testMrpGeneratesPastDueException() {
        Long matA = 1L;
        // Need date is only 5 days from TenantClock's "today" (2026-01-01),
        // lead time is 30 days -> order date in the past -> past due
        LocalDate needDate = LocalDate.of(2026, 1, 6);

        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of(matA, 0));

        when(inventoryPort.getOnHandQty(matA, null)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAllocatedQty(matA)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(matA)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(matA)).thenReturn(BigDecimal.ZERO);

        when(bomPort.hasBom(matA)).thenReturn(false);
        when(bomPort.getLotSizingPolicy(matA)).thenReturn("lfl");
        when(bomPort.getMoq(matA)).thenReturn(BigDecimal.ONE);
        when(bomPort.getLeadTime(matA)).thenReturn(30);

        List<DemandEntry> demands = List.of(new DemandEntry(matA, "A", new BigDecimal("100"), needDate));

        MrpResult result = engine.runMrp(demands, 90);

        assertEquals(1, result.getPlannedOrders().size());
        assertTrue(result.getExceptions().stream().anyMatch(e -> "past_due".equals(e.getType())));
    }
}
