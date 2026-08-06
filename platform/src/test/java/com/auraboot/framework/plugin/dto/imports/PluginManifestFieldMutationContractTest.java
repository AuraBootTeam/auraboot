package com.auraboot.framework.plugin.dto.imports;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PluginManifestFieldMutationContractTest {

    @Test
    void validFieldMutationContractsPassServerValidation() {
        PluginManifestExtended manifest = manifestWith(FieldDefinitionDTO.builder()
                .code("qdp_revision")
                .dataType("string")
                .immutable(true)
                .immutableWhen(FieldDefinitionDTO.ImmutableWhen.builder()
                        .field("status")
                        .in(List.of("released", "superseded"))
                        .build())
                .allowedWriterCommands(List.of("crm:release_qdp", "crm:supersede_qdp"))
                .build());

        assertThat(manifest.getValidationErrors()).isEmpty();
    }

    @Test
    void emptyWriterContractIsRejectedByServerValidation() {
        PluginManifestExtended manifest = manifestWith(FieldDefinitionDTO.builder()
                .code("qdp_revision")
                .dataType("string")
                .allowedWriterCommands(List.of())
                .build());

        assertThat(manifest.getValidationErrors())
                .contains("fields[0]: allowedWriterCommands must not be empty");
    }

    @Test
    void malformedConditionalLockIsRejectedByServerValidation() {
        PluginManifestExtended manifest = manifestWith(FieldDefinitionDTO.builder()
                .code("qdp_revision")
                .dataType("string")
                .immutableWhen(FieldDefinitionDTO.ImmutableWhen.builder()
                        .field("status")
                        .in(List.of())
                        .build())
                .build());

        assertThat(manifest.getValidationErrors())
                .contains("fields[0]: immutableWhen requires field and non-empty in states");
    }

    private PluginManifestExtended manifestWith(FieldDefinitionDTO field) {
        return PluginManifestExtended.builder()
                .pluginId("com.auraboot.qdp")
                .namespace("crm")
                .version("1.0.0")
                .fields(List.of(field))
                .build();
    }
}
