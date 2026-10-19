package com.auraboot.framework.meta.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiFieldProcessorBoundaryTest {
    @Test
    void enabledFieldGenerationDelegatesTheOriginalRequestAndResult() {
        var generator = mock(AiFieldGenerator.class);
        var facade = new AiFieldProcessor(generator);
        ReflectionTestUtils.setField(facade, "aiEnabled", true);
        var request = AiFieldProcessor.AiGenerationRequest.builder().operation("summarize").build();
        var result = AiFieldProcessor.AiGenerationResult.builder().success(true).content("summary").build();
        when(generator.generate(request)).thenReturn(result);
        assertThat(facade.process(request)).isSameAs(result);
        verify(generator).generate(request);
    }

    @Test
    void disabledFieldGenerationNeverCallsTheModelAdapter() {
        var generator = mock(AiFieldGenerator.class);
        var facade = new AiFieldProcessor(generator);
        assertThat(facade.process(AiFieldProcessor.AiGenerationRequest.builder().build()).isSuccess()).isFalse();
        verifyNoInteractions(generator);
    }
}
