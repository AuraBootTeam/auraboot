package com.auraboot.module.finance.engine;

import com.auraboot.module.meta.bitemporal.BitemporalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    private BitemporalRepository bitemporalRepository;

    private ExchangeRateService service;

    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new ExchangeRateService(bitemporalRepository);
    }

    @Test
    void testSetRateCallsCorrect() {
        long expectedEntityKey = (long) Objects.hash("usd", "cny");
        when(bitemporalRepository.correct(anyString(), anyLong(), any(), anyLong()))
                .thenReturn(42L);

        LocalDate validFrom = LocalDate.of(2026, 1, 1);
        LocalDate validTo = LocalDate.of(2026, 12, 31);
        BigDecimal rate = new BigDecimal("7.2500");

        Long newId = service.setRate("usd", "cny", rate, validFrom, validTo, TENANT_ID);

        assertEquals(42L, newId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bitemporalRepository).correct(
                eq("fac_exchange_rate"),
                eq(expectedEntityKey),
                dataCaptor.capture(),
                eq(TENANT_ID)
        );

        Map<String, Object> data = dataCaptor.getValue();
        assertEquals("usd", data.get("source_currency"));
        assertEquals("cny", data.get("target_currency"));
        assertEquals(rate, data.get("rate"));
        assertEquals(validFrom, data.get("valid_from"));
        assertEquals(validTo, data.get("valid_to"));
    }

    @Test
    void testGetRateReturnsCurrent() {
        long entityKey = (long) Objects.hash("usd", "cny");
        Map<String, Object> row = Map.of(
                "rate", new BigDecimal("7.2500"),
                "source_currency", "usd",
                "target_currency", "cny"
        );
        when(bitemporalRepository.findCurrent("fac_exchange_rate", entityKey, TENANT_ID))
                .thenReturn(row);

        BigDecimal result = service.getRate("usd", "cny", TENANT_ID);

        assertNotNull(result);
        assertEquals(new BigDecimal("7.2500"), result);
        verify(bitemporalRepository).findCurrent("fac_exchange_rate", entityKey, TENANT_ID);
    }

    @Test
    void testGetRateReturnsNullWhenNotFound() {
        long entityKey = (long) Objects.hash("eur", "jpy");
        when(bitemporalRepository.findCurrent("fac_exchange_rate", entityKey, TENANT_ID))
                .thenReturn(null);

        BigDecimal result = service.getRate("eur", "jpy", TENANT_ID);

        assertNull(result);
    }

    @Test
    void testGetRateAsOf() {
        long entityKey = (long) Objects.hash("usd", "cny");
        LocalDate validDate = LocalDate.of(2026, 6, 15);
        Instant systemDate = Instant.parse("2026-06-15T10:00:00Z");
        Map<String, Object> row = Map.of("rate", new BigDecimal("7.1000"));

        when(bitemporalRepository.findAsOf("fac_exchange_rate", entityKey, validDate, systemDate, TENANT_ID))
                .thenReturn(row);

        BigDecimal result = service.getRateAsOf("usd", "cny", validDate, systemDate, TENANT_ID);

        assertNotNull(result);
        assertEquals(new BigDecimal("7.1000"), result);
        verify(bitemporalRepository).findAsOf("fac_exchange_rate", entityKey, validDate, systemDate, TENANT_ID);
    }

    @Test
    void testGetHistory() {
        long entityKey = (long) Objects.hash("usd", "cny");
        List<Map<String, Object>> history = List.of(
                Map.of("rate", new BigDecimal("7.0000"), "entity_key", entityKey),
                Map.of("rate", new BigDecimal("7.2500"), "entity_key", entityKey)
        );

        when(bitemporalRepository.findHistory("fac_exchange_rate", entityKey, TENANT_ID))
                .thenReturn(history);

        List<Map<String, Object>> result = service.getHistory("usd", "cny", TENANT_ID);

        assertEquals(2, result.size());
        assertEquals(new BigDecimal("7.0000"), result.get(0).get("rate"));
        assertEquals(new BigDecimal("7.2500"), result.get(1).get("rate"));
        verify(bitemporalRepository).findHistory("fac_exchange_rate", entityKey, TENANT_ID);
    }

    @Test
    void testEntityKeyConsistency() {
        // Same currency pair called twice must produce the same entityKey
        long entityKey = (long) Objects.hash("usd", "cny");

        when(bitemporalRepository.findCurrent("fac_exchange_rate", entityKey, TENANT_ID))
                .thenReturn(Map.of("rate", new BigDecimal("7.0000")));

        // First call
        service.getRate("usd", "cny", TENANT_ID);
        // Second call
        service.getRate("usd", "cny", TENANT_ID);

        // Both calls should use the same entityKey — verify 2 calls with identical args
        verify(bitemporalRepository, times(2))
                .findCurrent("fac_exchange_rate", entityKey, TENANT_ID);
    }

    @Test
    void testEntityKeyCaseInsensitive() {
        // "usd"/"cny" and "usd"/"cny" must produce the same entityKey
        long expectedKey = (long) Objects.hash("usd", "cny");

        when(bitemporalRepository.findCurrent("fac_exchange_rate", expectedKey, TENANT_ID))
                .thenReturn(Map.of("rate", new BigDecimal("7.0000")));

        // Call with lowercase
        BigDecimal lower = service.getRate("usd", "cny", TENANT_ID);
        // Call with uppercase
        BigDecimal upper = service.getRate("usd", "cny", TENANT_ID);
        // Call with mixed case
        BigDecimal mixed = service.getRate("Usd", "Cny", TENANT_ID);

        // All should find the same row
        assertNotNull(lower);
        assertNotNull(upper);
        assertNotNull(mixed);

        // All 3 calls use the same entityKey
        verify(bitemporalRepository, times(3))
                .findCurrent("fac_exchange_rate", expectedKey, TENANT_ID);
    }
}
