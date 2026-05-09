package com.auraboot.framework.meta.formula;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossTableFormulaServiceTest {

    @Mock private DynamicDataService dynamicDataService;
    @InjectMocks private CrossTableFormulaService service;

    private void mockList(String model, List<Map<String, Object>> rows) {
        when(dynamicDataService.list(eq(model), any(DynamicQueryRequest.class)))
            .thenReturn(PaginationResult.of(rows, (long) rows.size(), 1, rows.size()));
    }

    // ----- LOOKUP -----
    @Test
    void lookup_returns_null_when_any_arg_null() {
        assertThat(service.lookup(null, "f", "v", "r")).isNull();
        assertThat(service.lookup("m", null, "v", "r")).isNull();
        assertThat(service.lookup("m", "f", null, "r")).isNull();
        assertThat(service.lookup("m", "f", "v", null)).isNull();
    }

    @Test
    void lookup_finds_first_match() {
        mockList("m", List.of(
            Map.of("code", "A", "name", "Alpha"),
            Map.of("code", "B", "name", "Beta")));
        assertThat(service.lookup("m", "code", "B", "name")).isEqualTo("Beta");
    }

    @Test
    void lookup_returns_null_when_no_match() {
        mockList("m", List.of(Map.of("code", "A", "name", "Alpha")));
        assertThat(service.lookup("m", "code", "Z", "name")).isNull();
    }

    @Test
    void lookup_returns_null_on_service_exception() {
        when(dynamicDataService.list(any(), any())).thenThrow(new RuntimeException("boom"));
        assertThat(service.lookup("m", "f", "v", "r")).isNull();
    }

    @Test
    void lookup_returns_null_when_service_returns_null() {
        when(dynamicDataService.list(any(), any())).thenReturn(null);
        assertThat(service.lookup("m", "f", "v", "r")).isNull();
    }

    // ----- VLOOKUP -----
    @Test
    void vlookup_exact_match() {
        mockList("m", List.of(Map.of("code", "alpha", "name", "Alpha")));
        assertThat(service.vlookup("alpha", "m", "code", "name", true)).isEqualTo("Alpha");
        assertThat(service.vlookup("ALPHA", "m", "code", "name", true)).isNull();
    }

    @Test
    void vlookup_fuzzy_match() {
        mockList("m", List.of(Map.of("code", "alphabet", "name", "Bet")));
        assertThat(service.vlookup("phab", "m", "code", "name", false)).isEqualTo("Bet");
    }

    @Test
    void vlookup_skips_null_field_values() {
        mockList("m", java.util.Arrays.asList(
            new java.util.HashMap<String, Object>() {{ put("code", null); put("name", "x"); }},
            Map.of("code", "B", "name", "Beta")));
        assertThat(service.vlookup("B", "m", "code", "name", true)).isEqualTo("Beta");
    }

    @Test
    void vlookup_returns_null_for_null_args() {
        assertThat(service.vlookup(null, "m", "f", "r", true)).isNull();
        assertThat(service.vlookup("v", null, "f", "r", true)).isNull();
        assertThat(service.vlookup("v", "m", null, "r", true)).isNull();
        assertThat(service.vlookup("v", "m", "f", null, true)).isNull();
    }

    // ----- RELATED -----
    @Test
    void related_collects_matching_records() {
        mockList("m", List.of(
            Map.of("orderId", "1", "amount", 10),
            Map.of("orderId", "1", "amount", 20),
            Map.of("orderId", "2", "amount", 30)));
        assertThat(service.related("m", "orderId", "1", "amount"))
            .containsExactly(10, 20);
    }

    @Test
    void related_returns_empty_for_null_args() {
        assertThat(service.related(null, "f", "v", "r")).isEmpty();
        assertThat(service.related("m", null, "v", "r")).isEmpty();
        assertThat(service.related("m", "f", null, "r")).isEmpty();
        assertThat(service.related("m", "f", "v", null)).isEmpty();
    }

    @Test
    void related_returns_empty_on_exception() {
        when(dynamicDataService.list(any(), any())).thenThrow(new RuntimeException("x"));
        assertThat(service.related("m", "f", "v", "r")).isEmpty();
    }

    // ----- COUNTIF -----
    @Test
    void countIf_counts_matches() {
        mockList("m", List.of(
            Map.of("status", "open"),
            Map.of("status", "open"),
            Map.of("status", "closed")));
        assertThat(service.countIf("m", "status", "open")).isEqualTo(2L);
    }

    @Test
    void countIf_returns_zero_for_null_args() {
        assertThat(service.countIf(null, "f", "v")).isEqualTo(0L);
        assertThat(service.countIf("m", null, "v")).isEqualTo(0L);
        assertThat(service.countIf("m", "f", null)).isEqualTo(0L);
    }

    @Test
    void countIf_returns_zero_on_exception() {
        when(dynamicDataService.list(any(), any())).thenThrow(new RuntimeException());
        assertThat(service.countIf("m", "f", "v")).isEqualTo(0L);
    }

    // ----- SUMIF -----
    @Test
    void sumIf_sums_numbers_and_parses_strings() {
        mockList("m", List.of(
            Map.of("status", "open", "amt", 10),
            Map.of("status", "open", "amt", "20"),
            Map.of("status", "open", "amt", "abc"),
            Map.of("status", "closed", "amt", 999)));
        assertThat(service.sumIf("m", "status", "open", "amt")).isEqualTo(30.0);
    }

    @Test
    void sumIf_returns_zero_for_null_args() {
        assertThat(service.sumIf(null, "f", "v", "s")).isEqualTo(0.0);
        assertThat(service.sumIf("m", null, "v", "s")).isEqualTo(0.0);
        assertThat(service.sumIf("m", "f", null, "s")).isEqualTo(0.0);
        assertThat(service.sumIf("m", "f", "v", null)).isEqualTo(0.0);
    }

    @Test
    void sumIf_returns_zero_on_exception() {
        when(dynamicDataService.list(any(), any())).thenThrow(new RuntimeException());
        assertThat(service.sumIf("m", "f", "v", "s")).isEqualTo(0.0);
    }
}
