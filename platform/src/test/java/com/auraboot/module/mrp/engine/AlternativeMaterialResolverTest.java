package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.dto.AlternativeMaterialDto;
import com.auraboot.module.mrp.port.BomQueryPort;
import com.auraboot.module.mrp.port.InventoryQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlternativeMaterialResolverTest {

    @Mock
    private BomQueryPort bomPort;

    @Mock
    private InventoryQueryPort inventoryPort;

    private AlternativeMaterialResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AlternativeMaterialResolver(bomPort, inventoryPort);
    }

    @Test
    void testPrimaryMaterialSufficient() {
        Long bomLineId = 100L;
        Long primaryId = 1L;
        BigDecimal required = new BigDecimal("50");

        when(inventoryPort.getAvailableQty(primaryId)).thenReturn(new BigDecimal("100"));

        ResolvedMaterial result = resolver.resolve(bomLineId, primaryId, required);

        assertEquals(primaryId, result.getMaterialId());
        assertFalse(result.isAlternative());
        assertEquals(0, BigDecimal.ONE.compareTo(result.getConversionFactor()));
    }

    @Test
    void testFallbackToFirstAlternative() {
        Long bomLineId = 100L;
        Long primaryId = 1L;
        Long alt1Id = 10L;
        BigDecimal required = new BigDecimal("100");

        when(inventoryPort.getAvailableQty(primaryId)).thenReturn(new BigDecimal("30"));
        when(inventoryPort.getAvailableQty(alt1Id)).thenReturn(new BigDecimal("200"));

        AlternativeMaterialDto alt1 = AlternativeMaterialDto.builder()
            .id(201L).bomLineId(bomLineId).materialId(alt1Id).materialName("Alt1")
            .priority(1).conversionFactor(BigDecimal.ONE).build();
        when(bomPort.getAlternatives(bomLineId)).thenReturn(List.of(alt1));

        ResolvedMaterial result = resolver.resolve(bomLineId, primaryId, required);

        assertEquals(alt1Id, result.getMaterialId());
        assertTrue(result.isAlternative());
    }

    @Test
    void testFallbackByPriority() {
        Long bomLineId = 100L;
        Long primaryId = 1L;
        Long alt1Id = 10L;
        Long alt2Id = 20L;
        BigDecimal required = new BigDecimal("100");

        when(inventoryPort.getAvailableQty(primaryId)).thenReturn(new BigDecimal("30"));
        when(inventoryPort.getAvailableQty(alt1Id)).thenReturn(new BigDecimal("50"));
        when(inventoryPort.getAvailableQty(alt2Id)).thenReturn(new BigDecimal("200"));

        AlternativeMaterialDto alt1 = AlternativeMaterialDto.builder()
            .id(201L).bomLineId(bomLineId).materialId(alt1Id).materialName("Alt1")
            .priority(1).conversionFactor(BigDecimal.ONE).build();
        AlternativeMaterialDto alt2 = AlternativeMaterialDto.builder()
            .id(202L).bomLineId(bomLineId).materialId(alt2Id).materialName("Alt2")
            .priority(2).conversionFactor(BigDecimal.ONE).build();
        when(bomPort.getAlternatives(bomLineId)).thenReturn(List.of(alt1, alt2));

        ResolvedMaterial result = resolver.resolve(bomLineId, primaryId, required);

        assertEquals(alt2Id, result.getMaterialId());
        assertTrue(result.isAlternative());
    }

    @Test
    void testAllInsufficientReturnsPrimary() {
        Long bomLineId = 100L;
        Long primaryId = 1L;
        Long alt1Id = 10L;
        BigDecimal required = new BigDecimal("100");

        when(inventoryPort.getAvailableQty(primaryId)).thenReturn(new BigDecimal("30"));
        when(inventoryPort.getAvailableQty(alt1Id)).thenReturn(new BigDecimal("50"));

        AlternativeMaterialDto alt1 = AlternativeMaterialDto.builder()
            .id(201L).bomLineId(bomLineId).materialId(alt1Id).materialName("Alt1")
            .priority(1).conversionFactor(BigDecimal.ONE).build();
        when(bomPort.getAlternatives(bomLineId)).thenReturn(List.of(alt1));

        ResolvedMaterial result = resolver.resolve(bomLineId, primaryId, required);

        assertEquals(primaryId, result.getMaterialId());
        assertFalse(result.isAlternative());
    }

    @Test
    void testConversionFactorApplied() {
        // alt has conversionFactor=1.5, required=100 -> check 150 units of alt available
        Long bomLineId = 100L;
        Long primaryId = 1L;
        Long altId = 10L;
        BigDecimal required = new BigDecimal("100");

        when(inventoryPort.getAvailableQty(primaryId)).thenReturn(new BigDecimal("20"));
        // Alt available = 160 >= 100*1.5=150 -> sufficient
        when(inventoryPort.getAvailableQty(altId)).thenReturn(new BigDecimal("160"));

        AlternativeMaterialDto alt = AlternativeMaterialDto.builder()
            .id(201L).bomLineId(bomLineId).materialId(altId).materialName("AltConv")
            .priority(1).conversionFactor(new BigDecimal("1.5")).build();
        when(bomPort.getAlternatives(bomLineId)).thenReturn(List.of(alt));

        ResolvedMaterial result = resolver.resolve(bomLineId, primaryId, required);

        assertEquals(altId, result.getMaterialId());
        assertTrue(result.isAlternative());
        assertEquals(0, new BigDecimal("1.5").compareTo(result.getConversionFactor()));
    }
}
