package com.auraboot.framework.semantic.service;

import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.mapper.*;
import com.auraboot.framework.semantic.parser.SemanticYamlValidator;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SemanticPublishService using mocked mappers.
 *
 * <p>Verifies orchestration shape (parse → validate → insert dim/metric/lineage).
 * End-to-end persistence tests against real PG are deferred to W4 integration.
 */
class SemanticPublishServiceTest {

    private SemanticYamlParser parser;
    private SemanticYamlValidator validator;
    private AbSemanticModelMapper modelMapper;
    private AbSemanticDimensionMapper dimensionMapper;
    private AbSemanticMetricMapper metricMapper;
    private AbSemanticLineageEdgeMapper lineageMapper;
    private SemanticPublishService service;

    @BeforeEach
    void setup() {
        parser = new SemanticYamlParser();
        validator = new SemanticYamlValidator();
        modelMapper = mock(AbSemanticModelMapper.class);
        dimensionMapper = mock(AbSemanticDimensionMapper.class);
        metricMapper = mock(AbSemanticMetricMapper.class);
        lineageMapper = mock(AbSemanticLineageEdgeMapper.class);

        // Default: no existing rows
        when(modelMapper.findByCode(anyLong(), anyString(), anyString(), anyString())).thenReturn(null);
        when(dimensionMapper.listByModel(anyLong(), anyString())).thenReturn(Collections.emptyList());
        when(metricMapper.listActiveByModel(anyLong(), anyString())).thenReturn(Collections.emptyList());

        service = new SemanticPublishService(parser, validator,
                modelMapper, dimensionMapper, metricMapper, lineageMapper);
    }

    private byte[] loadYaml(String relativePath) throws IOException {
        try (var in = new ClassPathResource("semantic/" + relativePath).getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void publishSales_createsModelDimensionsMetricsAndLineage() throws IOException {
        byte[] yaml = loadYaml("valid/sales.semantic.yml");

        String pid = service.publishFromYaml(yaml, "sales", 1L, 100L);

        assertThat(pid).isNotBlank().hasSize(26);  // ULID
        verify(modelMapper, times(1)).insert(any(AbSemanticModel.class));
        // sales.yml has 4 dimensions
        verify(dimensionMapper, times(4))
                .insert(any(com.auraboot.framework.semantic.entity.AbSemanticDimension.class));
        // sales.yml has 4 metrics
        verify(metricMapper, times(4))
                .insert(any(com.auraboot.framework.semantic.entity.AbSemanticMetric.class));
        // Lineage:
        //  - 4 metric→model edges
        //  - simple metric (total_sales) → 1 measure_ref
        //  - ratio metric (paid_conversion_rate) → 2 measure_refs
        //  - cumulative metric (ytd_sales) → 1 measure_ref
        //  - derived metric (avg_order_value) → 2 metric placeholders
        //  Total = 4 + 1 + 2 + 1 + 2 = 10
        verify(lineageMapper, times(10))
                .insert(any(com.auraboot.framework.semantic.entity.AbSemanticLineageEdge.class));
    }

    @Test
    void publishCrm_handlesConversionMetric() throws IOException {
        byte[] yaml = loadYaml("valid/crm.semantic.yml");

        service.publishFromYaml(yaml, "crm", 1L, 100L);

        verify(modelMapper, times(1)).insert(any(AbSemanticModel.class));
        // crm.yml has 4 dimensions, 4 metrics
        verify(dimensionMapper, times(4))
                .insert(any(com.auraboot.framework.semantic.entity.AbSemanticDimension.class));
        verify(metricMapper, times(4))
                .insert(any(com.auraboot.framework.semantic.entity.AbSemanticMetric.class));
        // Conversion metric should emit 2 measure_refs (base_measure + conversion_measure)
        verify(lineageMapper, atLeast(4))
                .insert(any(com.auraboot.framework.semantic.entity.AbSemanticLineageEdge.class));
    }

    @Test
    void publishingSameYamlTwiceIsIdempotent() throws IOException {
        byte[] yaml = loadYaml("valid/inventory.semantic.yml");

        // First call: no existing
        String pid1 = service.publishFromYaml(yaml, "inv", 1L, 100L);
        assertThat(pid1).hasSize(26);

        // Capture the inserted record's SHA so the 2nd call sees it
        AbSemanticModel existing = new AbSemanticModel();
        existing.setPid(pid1);
        existing.setVersion("0.1");
        existing.setYamlSha(sha256(new String(yaml, StandardCharsets.UTF_8)));
        when(modelMapper.findByCode(eq(1L), eq("inv"), eq("inventory"), eq("0.1")))
                .thenReturn(existing);

        // Second call: SHA matches, should short-circuit
        reset(modelMapper);
        when(modelMapper.findByCode(eq(1L), eq("inv"), eq("inventory"), eq("0.1")))
                .thenReturn(existing);

        String pid2 = service.publishFromYaml(yaml, "inv", 1L, 100L);

        assertThat(pid2).isEqualTo(pid1);
        verify(modelMapper, never()).insert(any(AbSemanticModel.class));
        verify(modelMapper, never()).updateById(any(AbSemanticModel.class));
    }

    @Test
    void existingModelWithDifferentShaTriggersUpdate() throws IOException {
        byte[] yaml = loadYaml("valid/sales.semantic.yml");

        AbSemanticModel stale = new AbSemanticModel();
        stale.setId(99L);
        stale.setPid("OLDPID0000000000000000000A");
        stale.setVersion("0.1");
        stale.setYamlSha("different-sha");
        when(modelMapper.findByCode(eq(1L), eq("sales"), eq("sales"), eq("0.1")))
                .thenReturn(stale);
        // Existing dims/metrics to be soft-deleted
        when(dimensionMapper.listByModel(1L, "OLDPID0000000000000000000A"))
                .thenReturn(new ArrayList<>(Collections.singletonList(new com.auraboot.framework.semantic.entity.AbSemanticDimension())));
        when(metricMapper.listActiveByModel(1L, "OLDPID0000000000000000000A"))
                .thenReturn(new ArrayList<>(Collections.singletonList(new com.auraboot.framework.semantic.entity.AbSemanticMetric())));

        String pid = service.publishFromYaml(yaml, "sales", 1L, 100L);

        assertThat(pid).isEqualTo("OLDPID0000000000000000000A");
        verify(modelMapper, never()).insert(any(AbSemanticModel.class));
        verify(modelMapper, times(1)).updateById(any(AbSemanticModel.class));
        verify(lineageMapper, times(1))
                .softDeleteAllFrom(1L, "OLDPID0000000000000000000A");
    }

    @Test
    void malformedYamlPropagatesParseException() {
        byte[] bad = "version: 0.1\nbroken:".getBytes(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.publishFromYaml(bad, "x", 1L, 100L))
                .isInstanceOf(com.auraboot.framework.semantic.exception.SemanticYamlInvalidException.class);
        verifyNoInteractions(dimensionMapper, metricMapper, lineageMapper);
    }

    @Test
    void schemaInvalidYamlPropagatesAndNoPersistence() throws IOException {
        byte[] bad = loadYaml("invalid/schema/bad-metric-type.semantic.yml");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.publishFromYaml(bad, "x", 1L, 100L))
                .isInstanceOf(com.auraboot.framework.semantic.exception.SemanticYamlInvalidException.class);
        verifyNoInteractions(dimensionMapper, metricMapper, lineageMapper);
    }

    private String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
