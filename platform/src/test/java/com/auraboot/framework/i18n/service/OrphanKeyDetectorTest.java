package com.auraboot.framework.i18n.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrphanKeyDetectorTest {

    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks OrphanKeyDetector detector;

    @Test
    void scan_classifiesKeysAgainstValidModelCodes() {
        when(jdbcTemplate.queryForList(contains("SELECT DISTINCT i18n_key"),
                eq(String.class), eq(100L)))
                .thenReturn(List.of(
                        "model.lead.name.label",
                        "model.lead._meta.label",
                        "model.gone.field.label"
                ));
        when(jdbcTemplate.queryForList(contains("SELECT DISTINCT code"),
                eq(String.class), eq(100L)))
                .thenReturn(List.of("lead"));

        OrphanKeyDetector.OrphanKeyScanResult result = detector.scan(100L);

        assertEquals(3, result.totalScanned());
        assertEquals(1, result.orphanCount());
        assertThat(result.orphanKeys()).containsExactly("model.gone.field.label");
    }

    @Test
    void scan_noKeys_emptyResult() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(100L)))
                .thenReturn(List.of());

        OrphanKeyDetector.OrphanKeyScanResult result = detector.scan(100L);

        assertEquals(0, result.totalScanned());
        assertEquals(0, result.orphanCount());
    }

    @Test
    void deleteOrphans_emptyList_returnsZero() {
        assertEquals(0, detector.deleteOrphans(100L, List.of()));
        assertEquals(0, detector.deleteOrphans(100L, null));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteOrphans_batchedDelete() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int deleted = detector.deleteOrphans(100L, List.of("model.gone.a", "model.gone.b"));

        assertEquals(2, deleted);
        verify(jdbcTemplate).update(contains("DELETE FROM ab_i18n_resource"), any(Object[].class));
    }

    @Test
    void deleteOrphans_largeBatch_splitsInto100() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(100, 5);
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 105; i++) keys.add("model.gone." + i);

        int deleted = detector.deleteOrphans(100L, keys);

        assertEquals(105, deleted);
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }
}
