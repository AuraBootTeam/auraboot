package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginModelOwnershipImportGateTest {
    private ModelDefinition model(boolean immutable) {
        return ModelDefinition.builder().code("shared_bid")
            .fields(List.of(FieldDefinition.builder().code("managed").dataType("boolean").immutable(immutable).build()))
            .extension(Map.of("recordCommandWriters",Map.of("field","managed","commands",
                Map.of("create",List.of("app:create"),"update",List.of("app:save"),"delete",List.of())))).build();
    }
    @Test void freshlyImportedImmutableMarkerIsRequiredEvenWhenReleaseSnapshotWasValid() {
        var models=mock(MetaModelService.class);
        when(models.getModelDefinitionFromDb("shared_bid")).thenReturn(Optional.of(model(false)));
        assertThatThrownBy(()->PluginModelOwnershipImportGate.validate(models,List.of("shared_bid")))
            .hasMessageContaining("Invalid recordCommandWriters");
        verify(models,never()).getModelDefinition(anyString());
    }
    @Test void validBoundFieldsAndOrdinaryModelsPassWithoutChangingReleaseMetadata() {
        var models=mock(MetaModelService.class);
        when(models.getModelDefinitionFromDb("shared_bid")).thenReturn(Optional.of(model(true)));
        when(models.getModelDefinitionFromDb("ordinary")).thenReturn(Optional.of(ModelDefinition.builder().code("ordinary").build()));
        assertThatCode(()->PluginModelOwnershipImportGate.validate(models,List.of("shared_bid","ordinary","shared_bid")))
            .doesNotThrowAnyException();
        verify(models,times(1)).getModelDefinitionFromDb("shared_bid");
        verify(models,never()).getModelDefinition(anyString());
    }
    @Test void missingImportedModelCannotProduceAnAcceptedImport() {
        var models=mock(MetaModelService.class);
        when(models.getModelDefinitionFromDb("shared_bid")).thenReturn(Optional.empty());
        assertThatThrownBy(()->PluginModelOwnershipImportGate.validate(models,List.of("shared_bid")))
            .hasMessageContaining("Imported model metadata is missing");
    }
}
