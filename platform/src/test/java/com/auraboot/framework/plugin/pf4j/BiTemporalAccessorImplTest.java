package com.auraboot.framework.plugin.pf4j;

import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BiTemporalAccessorImplTest {

    @Mock private BiTemporalService biTemporalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BiTemporalAccessorImpl accessor() {
        return new BiTemporalAccessorImpl(biTemporalService, objectMapper);
    }

    private BiTemporalRecord sampleRecord() {
        BiTemporalRecord r = new BiTemporalRecord();
        r.setId(11L);
        r.setEntityType("order");
        r.setEntityId("o-1");
        r.setVersionNo(2);
        r.setValidFrom(LocalDateTime.of(2024, 1, 1, 0, 0));
        r.setValidTo(LocalDateTime.of(2024, 12, 31, 0, 0));
        r.setPayload(objectMapper.valueToTree(Map.of("amount", 100)));
        return r;
    }

    @Test
    void put_returns_summary_map() {
        when(biTemporalService.put(eq("order"), eq("o-1"), any(), any(), any(), eq(7L)))
            .thenReturn(sampleRecord());

        Map<String, Object> r = accessor().put("order", "o-1",
            LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 12, 31, 0, 0),
            Map.of("amount", 100), 7L);

        assertThat(r).containsEntry("id", 11L)
            .containsEntry("entityType", "order")
            .containsEntry("entityId", "o-1")
            .containsEntry("versionNo", 2);
    }

    @Test
    void getAsOf_returns_payload_map_with_metadata() {
        when(biTemporalService.getAsOf(eq("order"), eq("o-1"), any(), any())).thenReturn(sampleRecord());
        Map<String, Object> r = accessor().getAsOf("order", "o-1",
            LocalDateTime.of(2024, 6, 1, 0, 0), null);
        assertThat(r).containsEntry("amount", 100)
            .containsEntry("__versionNo", 2)
            .containsEntry("__entityId", "o-1")
            .containsKey("__validFrom").containsKey("__validTo");
    }

    @Test
    void getAsOf_returns_null_when_record_missing() {
        when(biTemporalService.getAsOf(any(), any(), any(), any())).thenReturn(null);
        assertThat(accessor().getAsOf("o", "x", null, null)).isNull();
    }

    @Test
    void getCurrent_returns_payload_map_or_null() {
        when(biTemporalService.getCurrent(eq("order"), eq("o-1"))).thenReturn(sampleRecord());
        Map<String, Object> r = accessor().getCurrent("order", "o-1");
        assertThat(r).containsEntry("amount", 100);

        when(biTemporalService.getCurrent(eq("order"), eq("missing"))).thenReturn(null);
        assertThat(accessor().getCurrent("order", "missing")).isNull();
    }

    @Test
    void getCurrent_with_null_payload_returns_empty_map_in_payload_branch() {
        BiTemporalRecord r = sampleRecord();
        r.setPayload(null);
        when(biTemporalService.getCurrent(eq("order"), eq("o-1"))).thenReturn(r);
        Map<String, Object> result = accessor().getCurrent("order", "o-1");
        assertThat(result).isEmpty();
    }

    @Test
    void correct_returns_summary_map_and_delegates() {
        BiTemporalRecord rec = sampleRecord();
        when(biTemporalService.correct(eq("order"), eq("o-1"), any(), any(), any(), eq(7L))).thenReturn(rec);
        Map<String, Object> r = accessor().correct("order", "o-1",
            LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 12, 31, 0, 0),
            Map.of("amount", 99), 7L);
        assertThat(r).containsEntry("id", 11L).containsEntry("versionNo", 2);
        verify(biTemporalService).correct(eq("order"), eq("o-1"), any(), any(), any(), eq(7L));
    }
}
