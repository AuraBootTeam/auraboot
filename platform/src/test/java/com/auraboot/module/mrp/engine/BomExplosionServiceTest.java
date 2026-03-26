package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.dto.BomLineDto;
import com.auraboot.module.mrp.port.BomQueryPort;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BomExplosionServiceTest {

    @Mock
    private BomQueryPort bomPort;

    private BomExplosionService service;

    @BeforeEach
    void setUp() {
        service = new BomExplosionService(bomPort);
    }

    @Test
    void testExplodeOneLevelBasic() {
        // 1 parent -> 3 children with different qty_per
        Long parentId = 1L;
        BigDecimal parentQty = new BigDecimal("10");
        LocalDate date = LocalDate.of(2026, 4, 1);

        List<BomLineDto> lines = List.of(
            BomLineDto.builder().id(101L).childMaterialId(2L).childMaterialName("Resistor").quantityPer(new BigDecimal("5")).lossRate(BigDecimal.ZERO).build(),
            BomLineDto.builder().id(102L).childMaterialId(3L).childMaterialName("Capacitor").quantityPer(new BigDecimal("3")).lossRate(BigDecimal.ZERO).build(),
            BomLineDto.builder().id(103L).childMaterialId(4L).childMaterialName("IC Chip").quantityPer(new BigDecimal("1")).lossRate(BigDecimal.ZERO).build()
        );
        when(bomPort.getBomLines(parentId, date)).thenReturn(lines);

        List<ChildDemand> demands = service.explodeOneLevel(parentId, parentQty, date);

        assertEquals(3, demands.size());
        // 10 * 5 = 50
        assertEquals(0, new BigDecimal("50").compareTo(demands.get(0).getQuantity()));
        assertEquals(2L, demands.get(0).getMaterialId());
        // 10 * 3 = 30
        assertEquals(0, new BigDecimal("30").compareTo(demands.get(1).getQuantity()));
        // 10 * 1 = 10
        assertEquals(0, new BigDecimal("10").compareTo(demands.get(2).getQuantity()));
    }

    @Test
    void testExplodeWithLossRate() {
        // qty_per=1, lossRate=0.05 -> need 1/(1-0.05) = 1.0526
        Long parentId = 1L;
        BigDecimal parentQty = BigDecimal.ONE;
        LocalDate date = LocalDate.of(2026, 4, 1);

        List<BomLineDto> lines = List.of(
            BomLineDto.builder().id(201L).childMaterialId(5L).childMaterialName("SMD Part").quantityPer(BigDecimal.ONE).lossRate(new BigDecimal("0.05")).build()
        );
        when(bomPort.getBomLines(parentId, date)).thenReturn(lines);

        List<ChildDemand> demands = service.explodeOneLevel(parentId, parentQty, date);

        assertEquals(1, demands.size());
        // 1 * 1 / (1 - 0.05) = 1/0.95 = 1.0526 (scale 4, HALF_UP)
        BigDecimal expected = BigDecimal.ONE.divide(new BigDecimal("0.95"), 4, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(demands.get(0).getQuantity()));
    }

    @Test
    void testExplodeEmptyBom() {
        Long parentId = 1L;
        LocalDate date = LocalDate.of(2026, 4, 1);

        when(bomPort.getBomLines(parentId, date)).thenReturn(List.of());

        List<ChildDemand> demands = service.explodeOneLevel(parentId, BigDecimal.TEN, date);

        assertTrue(demands.isEmpty());
    }

    @Test
    void testComputeLLC() {
        // 3-level tree: A(1)->B(2), A(1)->D(4), B(2)->C(3)
        // LLC: A=0, B=1, D=1, C=2
        Map<Long, Integer> llc = Map.of(1L, 0, 2L, 1, 4L, 1, 3L, 2);
        when(bomPort.computeLowLevelCodes()).thenReturn(llc);

        Map<Long, Integer> result = service.computeLowLevelCodes();

        assertEquals(0, result.get(1L));
        assertEquals(1, result.get(2L));
        assertEquals(1, result.get(4L));
        assertEquals(2, result.get(3L));
    }
}
