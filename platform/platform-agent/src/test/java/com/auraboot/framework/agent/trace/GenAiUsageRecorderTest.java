package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.observability.GenAiPricing;
import com.auraboot.framework.observability.GenAiPricingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenAiUsageRecorderTest {

    @Test
    void unknownModelIsPersistedAsUnpricedAndDiagnosticEstimateIsIgnored() {
        GenAiUsageMapper mapper = mock(GenAiUsageMapper.class);
        ObjectProvider<GenAiPricingProperties> properties = mock(ObjectProvider.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenAiUsageRecorder recorder = new GenAiUsageRecorder(mapper, properties, registry);

        recorder.record(7L, "trace-1", "vendor", "future-model",
                120, 30, 0, 0, new BigDecimal("99.00"));

        ArgumentCaptor<GenAiUsageRecord> inserted =
                ArgumentCaptor.forClass(GenAiUsageRecord.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(inserted.getValue().getPricingVersion())
                .isEqualTo(GenAiPricing.UNPRICED_VERSION);
        assertThat(registry.counter(
                GenAiUsageRecorder.METRIC_UNPRICED, "provider", "vendor").count())
                .isEqualTo(1.0);
    }

    @Test
    void deploymentRatePricesUnknownBuiltInModel() {
        GenAiUsageMapper mapper = mock(GenAiUsageMapper.class);
        GenAiPricingProperties configured = new GenAiPricingProperties();
        GenAiPricingProperties.RateConfig rate = new GenAiPricingProperties.RateConfig();
        rate.setInput(new BigDecimal("0.40"));
        rate.setOutput(new BigDecimal("1.20"));
        configured.getModels().put("qwen-plus", rate);
        ObjectProvider<GenAiPricingProperties> properties = mock(ObjectProvider.class);
        when(properties.getIfAvailable()).thenReturn(configured);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenAiUsageRecorder recorder = new GenAiUsageRecorder(mapper, properties, registry);

        recorder.record(7L, "trace-2", "qianwen", "qwen-plus-2026",
                1_000_000, 0, 0, 0, null);

        ArgumentCaptor<GenAiUsageRecord> inserted =
                ArgumentCaptor.forClass(GenAiUsageRecord.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getAmount()).isEqualByComparingTo("0.400000");
        assertThat(inserted.getValue().getPricingVersion())
                .isEqualTo(GenAiPricing.PRICING_VERSION);
        assertThat(registry.find(GenAiUsageRecorder.METRIC_UNPRICED).counter()).isNull();
    }

    @Test
    void failedLedgerWriteIncrementsFailureCounter() {
        GenAiUsageMapper mapper = mock(GenAiUsageMapper.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(mapper).insert(any(GenAiUsageRecord.class));
        ObjectProvider<GenAiPricingProperties> properties = mock(ObjectProvider.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenAiUsageRecorder recorder = new GenAiUsageRecorder(mapper, properties, registry);

        recorder.record(7L, "trace-3", "openai", "gpt-4o",
                100, 20, 0, 0, null);

        assertThat(registry.counter(
                GenAiUsageRecorder.METRIC_WRITE_FAILURE, "provider", "openai").count())
                .isEqualTo(1.0);
    }
}
