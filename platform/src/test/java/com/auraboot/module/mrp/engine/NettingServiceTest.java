package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.port.InventoryQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NettingServiceTest {

    @Mock
    private InventoryQueryPort inventoryPort;

    private NettingService service;

    @BeforeEach
    void setUp() {
        service = new NettingService(inventoryPort);
    }

    @Test
    void testNetDemandBasic() {
        // gross=100, onHand=30, allocated=10, inTransit=20, safety=5
        // net = 100 - (30-10) - 20 + 5 = 65
        Long materialId = 1L;
        when(inventoryPort.getOnHandQty(materialId, null)).thenReturn(new BigDecimal("30"));
        when(inventoryPort.getAllocatedQty(materialId)).thenReturn(new BigDecimal("10"));
        when(inventoryPort.getInTransitQty(materialId)).thenReturn(new BigDecimal("20"));
        when(inventoryPort.getSafetyStock(materialId)).thenReturn(new BigDecimal("5"));

        BigDecimal net = service.calculateNetDemand(materialId, new BigDecimal("100"));

        assertEquals(0, new BigDecimal("65").compareTo(net));
    }

    @Test
    void testNetDemandSufficientStock() {
        // gross=50, onHand=100, allocated=0, inTransit=0, safety=0
        // net = 50 - (100-0) - 0 + 0 = -50 -> clamped to 0
        Long materialId = 2L;
        when(inventoryPort.getOnHandQty(materialId, null)).thenReturn(new BigDecimal("100"));
        when(inventoryPort.getAllocatedQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(materialId)).thenReturn(BigDecimal.ZERO);

        BigDecimal net = service.calculateNetDemand(materialId, new BigDecimal("50"));

        assertEquals(0, BigDecimal.ZERO.compareTo(net));
    }

    @Test
    void testNetDemandWithSafetyStock() {
        // gross=50, onHand=60, allocated=0, inTransit=0, safety=20
        // net = 50 - (60-0) - 0 + 20 = 10
        Long materialId = 3L;
        when(inventoryPort.getOnHandQty(materialId, null)).thenReturn(new BigDecimal("60"));
        when(inventoryPort.getAllocatedQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(materialId)).thenReturn(new BigDecimal("20"));

        BigDecimal net = service.calculateNetDemand(materialId, new BigDecimal("50"));

        assertEquals(0, new BigDecimal("10").compareTo(net));
    }

    @Test
    void testNetDemandNullSafetyStock() {
        // safetyStock returns null -> treated as 0
        Long materialId = 4L;
        when(inventoryPort.getOnHandQty(materialId, null)).thenReturn(new BigDecimal("80"));
        when(inventoryPort.getAllocatedQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(materialId)).thenReturn(null);

        BigDecimal net = service.calculateNetDemand(materialId, new BigDecimal("50"));

        // 50 - 80 - 0 + 0 = -30 -> clamped to 0
        assertEquals(0, BigDecimal.ZERO.compareTo(net));
    }

    @Test
    void testNeedsReplenishmentTrue() {
        Long materialId = 5L;
        when(inventoryPort.getOnHandQty(materialId, null)).thenReturn(new BigDecimal("10"));
        when(inventoryPort.getAllocatedQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(materialId)).thenReturn(BigDecimal.ZERO);

        assertTrue(service.needsReplenishment(materialId, new BigDecimal("100")));
    }

    @Test
    void testNeedsReplenishmentFalse() {
        Long materialId = 6L;
        when(inventoryPort.getOnHandQty(materialId, null)).thenReturn(new BigDecimal("200"));
        when(inventoryPort.getAllocatedQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getInTransitQty(materialId)).thenReturn(BigDecimal.ZERO);
        when(inventoryPort.getSafetyStock(materialId)).thenReturn(BigDecimal.ZERO);

        assertFalse(service.needsReplenishment(materialId, new BigDecimal("50")));
    }
}
