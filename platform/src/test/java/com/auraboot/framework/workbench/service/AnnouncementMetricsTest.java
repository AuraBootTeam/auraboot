package com.auraboot.framework.workbench.service;

import com.auraboot.framework.workbench.mapper.AnnouncementMetricsMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnouncementMetricsTest {
    @Test void persistedFactsUseOnlyFiniteStageTagsAndNoBusinessIdentities() {
        var facts=mock(AnnouncementMetricsMapper.class);when(facts.publicationPending()).thenReturn(3L);
        when(facts.publicationDelay()).thenReturn(1.5);var registry=new SimpleMeterRegistry();new AnnouncementMetrics(registry,facts);
        assertThat(registry.get("auraboot_announcement_pending").tag("stage","publication").gauge().value()).isEqualTo(3);
        assertThat(registry.get("auraboot_announcement_publication_delay_24h_seconds").gauge().value()).isEqualTo(1.5);
        assertThat(registry.getMeters()).hasSize(30).allSatisfy(meter->assertThat(meter.getId().getTags()).hasSize(1));
    }
    @Test void datasourceFailureDoesNotReportAHealthyZero() {
        var facts=mock(AnnouncementMetricsMapper.class);when(facts.deliveryPending()).thenThrow(new IllegalStateException("Unavailable"));
        var registry=new SimpleMeterRegistry();new AnnouncementMetrics(registry,facts);
        assertThat(registry.get("auraboot_announcement_pending").tag("stage","delivery").gauge().value()).isNaN();
    }
}
