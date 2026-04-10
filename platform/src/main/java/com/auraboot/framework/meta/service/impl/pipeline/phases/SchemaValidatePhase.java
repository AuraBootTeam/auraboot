package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.PayloadTemporalNormalizer;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Basic payload normalization: ensure non-null payload, temporal type conversion.
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class SchemaValidatePhase implements CommandPhase {

    private final MetaModelService metaModelService;
    private final PayloadTemporalNormalizer payloadTemporalNormalizer;

    @Override
    public String name() {
        return "schema_validate";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        Map<String, Object> payload = ctx.getRequest().getPayload() != null
                ? ctx.getRequest().getPayload() : new HashMap<>();
        ctx.setPayload(payload);

        // Temporal normalization — convert date/datetime strings to typed Java objects
        String modelCode = ctx.getCommand().getModelCode();
        if (modelCode != null) {
            metaModelService.getModelDefinition(modelCode)
                    .ifPresent(modelDef -> payloadTemporalNormalizer.normalize(payload, modelDef));
        }
    }
}
