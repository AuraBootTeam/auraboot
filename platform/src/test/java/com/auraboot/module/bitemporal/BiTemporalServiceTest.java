package com.auraboot.module.bitemporal;

import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.mapper.BiTemporalMapper;
import com.auraboot.module.bitemporal.service.impl.BiTemporalServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BiTemporalServiceImpl.
 * Tests use mocked mapper to verify service logic in isolation.
 */
@ExtendWith(MockitoExtension.class)
class BiTemporalServiceTest {

    @Mock
    private BiTemporalMapper mapper;

    private BiTemporalServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String ENTITY_TYPE = "bom";
    private static final String ENTITY_ID = "BOM-001";
    private static final Long USER_ID = 42L;
    private static final LocalDateTime VALID_FROM = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    private static final LocalDateTime VALID_TO = BiTemporalRecord.INFINITY;

    @BeforeEach
    void setUp() {
        service = new BiTemporalServiceImpl(mapper);
    }

    @Test
    void put_shouldCreateRecordWithCorrectFields() {
        // Given
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("price", 100);
        payload.put("currency", "cny");

        when(mapper.insert(any(BiTemporalRecord.class))).thenReturn(1);

        // When
        BiTemporalRecord result = service.put(ENTITY_TYPE, ENTITY_ID, VALID_FROM, VALID_TO, payload, USER_ID);

        // Then
        ArgumentCaptor<BiTemporalRecord> captor = ArgumentCaptor.forClass(BiTemporalRecord.class);
        verify(mapper).insert(captor.capture());

        BiTemporalRecord inserted = captor.getValue();
        assertEquals(ENTITY_TYPE, inserted.getEntityType());
        assertEquals(ENTITY_ID, inserted.getEntityId());
        assertEquals(VALID_FROM, inserted.getValidFrom());
        assertEquals(VALID_TO, inserted.getValidTo());
        assertEquals(payload, inserted.getPayload());
        assertEquals(USER_ID, inserted.getCreatedBy());
        assertEquals(1, inserted.getVersionNo());
        assertNotNull(inserted.getTxFrom());
        assertEquals(BiTemporalRecord.INFINITY, inserted.getTxTo());
        assertNotNull(inserted.getCreatedAt());
        assertNotNull(inserted.getUpdatedAt());

        // Result should be the same object
        assertSame(inserted, result);
    }

    @Test
    void put_shouldIncrementVersionWhenHistoryExists() {
        // Given
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("price", 200);

        BiTemporalRecord existing = new BiTemporalRecord();
        existing.setVersionNo(3);
        when(mapper.findCurrent(ENTITY_TYPE, ENTITY_ID)).thenReturn(existing);
        when(mapper.insert(any(BiTemporalRecord.class))).thenReturn(1);

        // When
        service.put(ENTITY_TYPE, ENTITY_ID, VALID_FROM, VALID_TO, payload, USER_ID);

        // Then
        ArgumentCaptor<BiTemporalRecord> captor = ArgumentCaptor.forClass(BiTemporalRecord.class);
        verify(mapper).insert(captor.capture());
        assertEquals(4, captor.getValue().getVersionNo());
    }

    @Test
    void getAsOf_shouldDelegateToMapper() {
        // Given
        LocalDateTime validTime = LocalDateTime.of(2026, 3, 15, 10, 0, 0);
        LocalDateTime txTime = LocalDateTime.of(2026, 3, 15, 12, 0, 0);

        BiTemporalRecord expected = new BiTemporalRecord();
        expected.setId(1L);
        expected.setEntityType(ENTITY_TYPE);
        expected.setEntityId(ENTITY_ID);

        when(mapper.findAsOf(ENTITY_TYPE, ENTITY_ID, validTime, txTime)).thenReturn(expected);

        // When
        BiTemporalRecord result = service.getAsOf(ENTITY_TYPE, ENTITY_ID, validTime, txTime);

        // Then
        assertSame(expected, result);
        verify(mapper).findAsOf(ENTITY_TYPE, ENTITY_ID, validTime, txTime);
    }

    @Test
    void getCurrent_shouldDelegateToMapper() {
        // Given
        BiTemporalRecord expected = new BiTemporalRecord();
        expected.setId(2L);
        when(mapper.findCurrent(ENTITY_TYPE, ENTITY_ID)).thenReturn(expected);

        // When
        BiTemporalRecord result = service.getCurrent(ENTITY_TYPE, ENTITY_ID);

        // Then
        assertSame(expected, result);
        verify(mapper).findCurrent(ENTITY_TYPE, ENTITY_ID);
    }

    @Test
    void getHistory_shouldDelegateToMapper() {
        // Given
        BiTemporalRecord r1 = new BiTemporalRecord();
        r1.setId(1L);
        BiTemporalRecord r2 = new BiTemporalRecord();
        r2.setId(2L);

        when(mapper.findHistory(ENTITY_TYPE, ENTITY_ID)).thenReturn(List.of(r1, r2));

        // When
        List<BiTemporalRecord> result = service.getHistory(ENTITY_TYPE, ENTITY_ID);

        // Then
        assertEquals(2, result.size());
        verify(mapper).findHistory(ENTITY_TYPE, ENTITY_ID);
    }

    @Test
    void correct_shouldCloseOldAndInsertNew() {
        // Given
        ObjectNode newPayload = objectMapper.createObjectNode();
        newPayload.put("price", 300);

        BiTemporalRecord currentRecord = new BiTemporalRecord();
        currentRecord.setId(10L);
        currentRecord.setVersionNo(2);
        currentRecord.setEntityType(ENTITY_TYPE);
        currentRecord.setEntityId(ENTITY_ID);

        // correct() now uses findCurrentForUpdate (REVIEW-BE8-002).
        when(mapper.findCurrentForUpdate(ENTITY_TYPE, ENTITY_ID)).thenReturn(currentRecord);
        when(mapper.closeTxPeriod(eq(10L), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.insert(any(BiTemporalRecord.class))).thenReturn(1);

        // When
        BiTemporalRecord result = service.correct(ENTITY_TYPE, ENTITY_ID, VALID_FROM, VALID_TO, newPayload, USER_ID);

        // Then
        // 1. Verify old record was closed
        ArgumentCaptor<LocalDateTime> txToCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).closeTxPeriod(eq(10L), txToCaptor.capture());
        assertNotNull(txToCaptor.getValue());

        // 2. Verify new record was inserted with incremented version
        ArgumentCaptor<BiTemporalRecord> insertCaptor = ArgumentCaptor.forClass(BiTemporalRecord.class);
        verify(mapper).insert(insertCaptor.capture());

        BiTemporalRecord inserted = insertCaptor.getValue();
        assertEquals(ENTITY_TYPE, inserted.getEntityType());
        assertEquals(ENTITY_ID, inserted.getEntityId());
        assertEquals(VALID_FROM, inserted.getValidFrom());
        assertEquals(VALID_TO, inserted.getValidTo());
        assertEquals(newPayload, inserted.getPayload());
        assertEquals(3, inserted.getVersionNo()); // version incremented from 2
        assertEquals(USER_ID, inserted.getCreatedBy());
    }

    @Test
    void correct_shouldThrowWhenNoCurrentRecord() {
        // Given
        ObjectNode payload = objectMapper.createObjectNode();
        // correct() uses findCurrentForUpdate (REVIEW-BE8-002).
        when(mapper.findCurrentForUpdate(ENTITY_TYPE, ENTITY_ID)).thenReturn(null);

        // When/Then
        assertThrows(IllegalStateException.class, () ->
                service.correct(ENTITY_TYPE, ENTITY_ID, VALID_FROM, VALID_TO, payload, USER_ID));
    }

    @Test
    void terminate_shouldUpdateValidToOnCurrentRecord() {
        // Given
        LocalDateTime terminateTime = LocalDateTime.of(2026, 6, 30, 23, 59, 59);

        BiTemporalRecord currentRecord = new BiTemporalRecord();
        currentRecord.setId(5L);
        currentRecord.setVersionNo(1);
        currentRecord.setEntityType(ENTITY_TYPE);
        currentRecord.setEntityId(ENTITY_ID);
        currentRecord.setValidFrom(VALID_FROM);
        currentRecord.setValidTo(VALID_TO);

        // terminate() now uses findCurrentForUpdate (REVIEW-BE8-002).
        when(mapper.findCurrentForUpdate(ENTITY_TYPE, ENTITY_ID)).thenReturn(currentRecord);
        when(mapper.closeTxPeriod(eq(5L), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.insert(any(BiTemporalRecord.class))).thenReturn(1);

        // When
        service.terminate(ENTITY_TYPE, ENTITY_ID, terminateTime);

        // Then
        // 1. Old tx period should be closed
        verify(mapper).closeTxPeriod(eq(5L), any(LocalDateTime.class));

        // 2. New record should be inserted with valid_to = terminateTime
        ArgumentCaptor<BiTemporalRecord> captor = ArgumentCaptor.forClass(BiTemporalRecord.class);
        verify(mapper).insert(captor.capture());

        BiTemporalRecord inserted = captor.getValue();
        assertEquals(VALID_FROM, inserted.getValidFrom());
        assertEquals(terminateTime, inserted.getValidTo());
        assertEquals(2, inserted.getVersionNo()); // incremented
    }

    @Test
    void terminate_shouldThrowWhenNoCurrentRecord() {
        // Given
        LocalDateTime terminateTime = LocalDateTime.of(2026, 6, 30, 0, 0, 0);
        // terminate() uses findCurrentForUpdate (REVIEW-BE8-002).
        when(mapper.findCurrentForUpdate(ENTITY_TYPE, ENTITY_ID)).thenReturn(null);

        // When/Then
        assertThrows(IllegalStateException.class, () ->
                service.terminate(ENTITY_TYPE, ENTITY_ID, terminateTime));
    }
}
