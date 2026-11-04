package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.SchemaValidatePhase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandInputOriginTest {
    @Test void normalizationAndDerivationDoNotChangeTopLevelSubmittedKeysOrCallerMap() {
        var metadata = mock(MetaModelService.class);
        when(metadata.getModelDefinition("orders")).thenReturn(Optional.of(ModelDefinition.builder()
                .code("orders").fields(List.of(FieldDefinition.builder().code("issued_at").dataType("date").build())).build()));
        var request = new CommandExecuteRequest(); request.setPayload(Map.of("issued_at","2026-11-04"));
        var command = new CommandDefinition(); command.setModelCode("orders");
        var context = CommandPipelineContext.builder().request(request).command(command).build();
        new SchemaValidatePhase(metadata,new PayloadTemporalNormalizer(new SimpleMeterRegistry(),metadata)).execute(context);
        assertThat(context.getPayload()).containsEntry("issued_at",LocalDate.of(2026,11,4));
        context.getPayload().put("generated_status","approved");
        assertThat(context.submittedInputKeys()).containsExactly("issued_at");
        assertThat(request.getPayload()).containsExactly(Map.entry("issued_at","2026-11-04"));
        assertThatThrownBy(()->context.submittedInputKeys().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void originCannotBeRecapturedAfterClearingOrRewritingTheInput() {
        var input = new HashMap<String,Object>(); input.put("cost",null);
        var context = CommandPipelineContext.builder().build(); context.captureSubmittedInput(input);
        input.clear();
        assertThat(context.submittedInputKeys()).containsExactly("cost");
        assertThatThrownBy(()->context.captureSubmittedInput(Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test void payloadCannotForgeTheHostOrigin() {
        var context = CommandPipelineContext.builder().build();
        context.captureSubmittedInput(Map.of("submittedInputOrigin",Map.of("keys",List.of()),"cost",99));
        assertThat(context.submittedInputKeys()).contains("cost","submittedInputOrigin");
    }
}
