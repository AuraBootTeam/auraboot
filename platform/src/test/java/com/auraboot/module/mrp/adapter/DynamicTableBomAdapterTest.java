package com.auraboot.module.mrp.adapter;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.auraboot.module.mrp.dto.BomLineDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for DynamicTableBomAdapter bi-temporal integration.
 */
@ExtendWith(MockitoExtension.class)
class DynamicTableBomAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BiTemporalService biTemporalService;

    @InjectMocks
    private DynamicTableBomAdapter adapter;

    private MockedStatic<MetaContext> metaContextMock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        metaContextMock = mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(1L);
        // Inject bi-temporal service (optional field)
        ReflectionTestUtils.setField(adapter, "biTemporalService", biTemporalService);
    }

    @AfterEach
    void tearDown() {
        metaContextMock.close();
    }

    @Test
    void getBomLines_withBiTemporalOverlay_overridesQuantity() {
        // Given: base BOM lines from dynamic table
        LocalDate effectiveDate = LocalDate.of(2026, 3, 1);

        // Mock base query - return 2 BOM lines
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(100L), eq(1L)))
                .thenReturn(List.of(
                        BomLineDto.builder()
                                .id(10L)
                                .parentMaterialId(100L)
                                .childMaterialId(200L)
                                .quantityPer(new BigDecimal("5"))
                                .lossRate(new BigDecimal("2"))
                                .refDesignator("R1")
                                .build(),
                        BomLineDto.builder()
                                .id(20L)
                                .parentMaterialId(100L)
                                .childMaterialId(300L)
                                .quantityPer(new BigDecimal("10"))
                                .lossRate(new BigDecimal("1"))
                                .refDesignator("C1")
                                .build()
                ));

        // Mock bi-temporal records - only line 10 has a versioned record
        BiTemporalRecord btRecord = new BiTemporalRecord();
        btRecord.setEntityType("bom_line");
        btRecord.setEntityId("10");
        btRecord.setPayload(objectMapper.valueToTree(
                java.util.Map.of("materialId", "200", "quantity", "8.5", "lossRate", "3.0", "unit", "pcs")
        ));

        when(biTemporalService.getAllByTypeAsOf(eq("bom_line"), eq(effectiveDate.atStartOfDay())))
                .thenReturn(List.of(btRecord));

        // When
        List<BomLineDto> result = adapter.getBomLines(100L, effectiveDate);

        // Then
        assertEquals(2, result.size());

        // Line 10: overridden by bi-temporal data
        BomLineDto line10 = result.stream().filter(l -> l.getId() == 10L).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("8.5").compareTo(line10.getQuantityPer()));
        assertEquals(0, new BigDecimal("3.0").compareTo(line10.getLossRate()));

        // Line 20: unchanged (no bi-temporal record)
        BomLineDto line20 = result.stream().filter(l -> l.getId() == 20L).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("10").compareTo(line20.getQuantityPer()));
        assertEquals(0, new BigDecimal("1").compareTo(line20.getLossRate()));
    }

    @Test
    void getBomLines_noBiTemporalRecords_fallsBackToBase() {
        // Given: base BOM lines exist but no bi-temporal records
        LocalDate effectiveDate = LocalDate.of(2026, 3, 1);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(100L), eq(1L)))
                .thenReturn(List.of(
                        BomLineDto.builder()
                                .id(10L)
                                .parentMaterialId(100L)
                                .childMaterialId(200L)
                                .quantityPer(new BigDecimal("5"))
                                .lossRate(BigDecimal.ZERO)
                                .build()
                ));

        when(biTemporalService.getAllByTypeAsOf(eq("bom_line"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        // When
        List<BomLineDto> result = adapter.getBomLines(100L, effectiveDate);

        // Then: uses base data unchanged
        assertEquals(1, result.size());
        assertEquals(0, new BigDecimal("5").compareTo(result.get(0).getQuantityPer()));
    }

    @Test
    void getBomLines_nullEffectiveDate_skipsBiTemporal() {
        // Given: effectiveDate is null
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(100L), eq(1L)))
                .thenReturn(List.of(
                        BomLineDto.builder()
                                .id(10L)
                                .parentMaterialId(100L)
                                .childMaterialId(200L)
                                .quantityPer(new BigDecimal("5"))
                                .lossRate(BigDecimal.ZERO)
                                .build()
                ));

        // When
        List<BomLineDto> result = adapter.getBomLines(100L, null);

        // Then: bi-temporal service is never called
        verify(biTemporalService, never()).getAllByTypeAsOf(anyString(), any());
        assertEquals(1, result.size());
    }

    @Test
    void getBomLines_noBiTemporalService_fallsBackToBase() {
        // Given: biTemporalService is null (not injected)
        ReflectionTestUtils.setField(adapter, "biTemporalService", null);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(100L), eq(1L)))
                .thenReturn(List.of(
                        BomLineDto.builder()
                                .id(10L)
                                .parentMaterialId(100L)
                                .childMaterialId(200L)
                                .quantityPer(new BigDecimal("5"))
                                .lossRate(BigDecimal.ZERO)
                                .build()
                ));

        // When
        List<BomLineDto> result = adapter.getBomLines(100L, LocalDate.of(2026, 3, 1));

        // Then: uses base data, no errors
        assertEquals(1, result.size());
    }

    @Test
    void getBomLines_emptyBaseBomLines_skipsBiTemporal() {
        // Given: no base BOM lines
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(100L), eq(1L)))
                .thenReturn(List.of());

        // When
        List<BomLineDto> result = adapter.getBomLines(100L, LocalDate.of(2026, 3, 1));

        // Then: bi-temporal service is never called
        verify(biTemporalService, never()).getAllByTypeAsOf(anyString(), any());
        assertTrue(result.isEmpty());
    }
}
