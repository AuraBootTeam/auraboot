package com.auraboot.module.mrp.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.module.mrp.dto.BomLineDto;
import com.auraboot.module.mrp.dto.DemandEntry;
import com.auraboot.module.mrp.dto.MrpResult;
import com.auraboot.module.mrp.port.BomQueryPort;
import com.auraboot.module.mrp.port.InventoryQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MrpEdgeCaseTest {

    @Mock private BomQueryPort bomPort;
    @Mock private InventoryQueryPort inventoryPort;

    private BomExplosionService bomExplosionService;
    private NettingService nettingService;
    private MrpPlanningEngine engine;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 1L, "test-user-pid", "test-user");
        bomExplosionService = new BomExplosionService(bomPort);
        nettingService = new NettingService(inventoryPort);

        LotForLotStrategy lfl = new LotForLotStrategy();
        FixedOrderQtyStrategy foq = new FixedOrderQtyStrategy();
        EconomicOrderQtyStrategy eoq = new EconomicOrderQtyStrategy();
        LotSizingStrategyFactory factory = new LotSizingStrategyFactory(List.of(lfl, foq, eoq));

        AlternativeMaterialResolver altResolver = new AlternativeMaterialResolver(bomPort, inventoryPort);
        LeadTimeService leadTimeService = new LeadTimeService(bomPort);

        TenantClock tenantClock = mock(TenantClock.class);
        lenient().when(tenantClock.businessDate(any())).thenReturn(LocalDate.of(2026, 1, 1));

        engine = new MrpPlanningEngine(bomExplosionService, nettingService, factory, altResolver, leadTimeService, bomPort, inventoryPort, tenantClock);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void testEmptyBomReturnsNoChildren() {
        when(bomPort.getBomLines(eq(1L), any())).thenReturn(List.of());

        List<ChildDemand> result = bomExplosionService.explodeOneLevel(1L, BigDecimal.TEN, LocalDate.now());

        assertTrue(result.isEmpty());
    }

    @Test
    void testZeroDemandSkipped() {
        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of(1L, 0));
        when(inventoryPort.getOnHandQty(1L, null)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAllocatedQty(1L)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(1L)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(1L)).thenReturn(BigDecimal.ZERO);

        List<DemandEntry> demands = List.of(new DemandEntry(1L, "A", BigDecimal.ZERO, LocalDate.now().plusDays(30)));
        MrpResult result = engine.runMrp(demands, 90);

        assertTrue(result.getPlannedOrders().isEmpty());
    }

    @Test
    void testNegativeSafetyStockTreatedAsZero() {
        // safety stock = -1 (invalid) -> calculateNetDemand should still work
        // net = 50 - (100-0) - 0 + (-1) = -51 -> clamped to 0
        when(inventoryPort.getOnHandQty(1L, null)).thenReturn(new BigDecimal("100"));
        when(inventoryPort.getAllocatedQty(1L)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(1L)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(1L)).thenReturn(new BigDecimal("-1"));

        BigDecimal net = nettingService.calculateNetDemand(1L, new BigDecimal("50"));
        assertEquals(0, BigDecimal.ZERO.compareTo(net));
    }

    @Test
    void testMultipleDemandSourcesSameMaterial() {
        // 3 demands for material 1 -> quantities aggregated correctly
        Long matId = 1L;
        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of(matId, 0));

        when(inventoryPort.getOnHandQty(matId, null)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getAllocatedQty(matId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(matId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(matId)).thenReturn(BigDecimal.ZERO);

        when(bomPort.hasBom(matId)).thenReturn(false);
        when(bomPort.getLotSizingPolicy(matId)).thenReturn("lfl");
        when(bomPort.getMoq(matId)).thenReturn(BigDecimal.ONE);
        when(bomPort.getLeadTime(matId)).thenReturn(10);

        LocalDate needDate = LocalDate.now().plusDays(30);
        List<DemandEntry> demands = List.of(
            new DemandEntry(matId, "A", new BigDecimal("30"), needDate),
            new DemandEntry(matId, "A", new BigDecimal("50"), needDate),
            new DemandEntry(matId, "A", new BigDecimal("20"), needDate)
        );

        MrpResult result = engine.runMrp(demands, 90);

        assertEquals(1, result.getPlannedOrders().size());
        // 30 + 50 + 20 = 100
        assertEquals(0, new BigDecimal("100").compareTo(result.getPlannedOrders().get(0).getOrderQty()));
    }

    @Test
    void testAlternativeMaterialWithPartialStock() {
        // primary has 50, need 100, alt1 has 60 -> both insufficient -> return primary
        AlternativeMaterialResolver resolver = new AlternativeMaterialResolver(bomPort, inventoryPort);

        when(inventoryPort.getAvailableQty(1L)).thenReturn(new BigDecimal("50"));
        when(inventoryPort.getAvailableQty(10L)).thenReturn(new BigDecimal("60"));

        when(bomPort.getAlternatives(100L)).thenReturn(List.of(
            com.auraboot.module.mrp.dto.AlternativeMaterialDto.builder()
                .id(201L).bomLineId(100L).materialId(10L).materialName("Alt1")
                .priority(1).conversionFactor(BigDecimal.ONE).build()
        ));

        ResolvedMaterial result = resolver.resolve(100L, 1L, new BigDecimal("100"));
        assertEquals(1L, result.getMaterialId());
        assertFalse(result.isAlternative());
    }

    @Test
    void testLossRateZeroNoAdjustment() {
        // lossRate=0 -> no adjustment, just quantity * qtyPer
        when(bomPort.getBomLines(eq(1L), any())).thenReturn(List.of(
            BomLineDto.builder().id(101L).childMaterialId(2L).childMaterialName("B")
                .quantityPer(new BigDecimal("3")).lossRate(BigDecimal.ZERO).build()
        ));

        List<ChildDemand> result = bomExplosionService.explodeOneLevel(1L, new BigDecimal("10"), LocalDate.now());
        assertEquals(0, new BigDecimal("30").compareTo(result.get(0).getQuantity()));
    }

    @Test
    void testLossRateNullNoAdjustment() {
        // lossRate=null -> no adjustment
        when(bomPort.getBomLines(eq(1L), any())).thenReturn(List.of(
            BomLineDto.builder().id(101L).childMaterialId(2L).childMaterialName("B")
                .quantityPer(new BigDecimal("5")).lossRate(null).build()
        ));

        List<ChildDemand> result = bomExplosionService.explodeOneLevel(1L, new BigDecimal("4"), LocalDate.now());
        assertEquals(0, new BigDecimal("20").compareTo(result.get(0).getQuantity()));
    }

    @Test
    void testMoqZeroReturnsExactNetDemand() {
        // moq=0 -> lot sizing returns exact net demand
        LotForLotStrategy lfl = new LotForLotStrategy();
        BigDecimal result = lfl.calculate(new BigDecimal("73"), BigDecimal.ZERO, Map.of());
        assertEquals(0, new BigDecimal("73").compareTo(result));
    }

    @Test
    void testAllStrategiesHandleZeroNetDemand() {
        LotForLotStrategy lfl = new LotForLotStrategy();
        FixedOrderQtyStrategy foq = new FixedOrderQtyStrategy();
        EconomicOrderQtyStrategy eoq = new EconomicOrderQtyStrategy();

        assertEquals(0, BigDecimal.ZERO.compareTo(lfl.calculate(BigDecimal.ZERO, BigDecimal.ZERO, Map.of())));
        assertEquals(0, BigDecimal.ZERO.compareTo(foq.calculate(BigDecimal.ZERO, BigDecimal.ZERO, Map.of("fixedOrderQty", new BigDecimal("100")))));
        // EOQ with zero netDemand returns zero (no order needed)
        BigDecimal eoqResult = eoq.calculate(BigDecimal.ZERO, BigDecimal.ZERO, Map.of(
            "annualDemand", new BigDecimal("1000"),
            "orderCost", new BigDecimal("100"),
            "holdingCostPerUnit", new BigDecimal("10")
        ));
        assertEquals(0, BigDecimal.ZERO.compareTo(eoqResult));
    }

    @Test
    void testMrpRunWithNoSalesOrders() {
        when(bomPort.computeLowLevelCodes()).thenReturn(Map.of());

        MrpResult result = engine.runMrp(List.of(), 90);

        assertTrue(result.getPlannedOrders().isEmpty());
        assertTrue(result.getExceptions().isEmpty());
    }
}
