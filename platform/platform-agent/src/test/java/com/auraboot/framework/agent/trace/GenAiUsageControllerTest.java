package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.dto.GenAiUsageSummary;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link GenAiUsageController} (deep-review DR-20260701 R5-A1 test gap).
 * The read endpoint must scope the rollup to the current tenant from {@link MetaContext}.
 */
@ExtendWith(MockitoExtension.class)
class GenAiUsageControllerTest {

    @Mock
    private GenAiUsageMapper genAiUsageMapper;

    @InjectMocks
    private GenAiUsageController controller;

    @BeforeEach
    void setUp() {
        MetaContext.setSystemTenantContext(42L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("summary() rolls up usage for the current tenant only")
    void summaryIsTenantScoped() {
        List<GenAiUsageSummary> expected = new ArrayList<>();
        when(genAiUsageMapper.summaryByModel(42L)).thenReturn(expected);

        List<GenAiUsageSummary> result = controller.summary();

        assertThat(result).isSameAs(expected);
        verify(genAiUsageMapper).summaryByModel(42L);
    }
}
