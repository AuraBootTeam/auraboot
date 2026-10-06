package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityDefinitionDTOTest {
    @Test
    void rejectsUnknownPropertiesEvenWhenOtherPluginDtosAreLenient() {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        assertThatThrownBy(() -> mapper.readValue(
                "{\"code\":\"qo.cap.output\",\"includes\":[\"qo.quote.output.read\"],\"permissions\":[\"model.qo_quote_common.read\"]}",
                CapabilityDefinitionDTO.class)).hasMessageContaining("Unknown capability declaration property: permissions");
    }

    @Test
    void serializedDeclarationCanBeImportedWithoutComputedProperties() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CapabilityDefinitionDTO declaration = CapabilityDefinitionDTO.builder()
                .code("example.cap.read").group("Example").nameEn("Read example")
                .includes(List.of("model.example.read")).build();
        String json = mapper.writeValueAsString(declaration);
        assertThat(mapper.readTree(json).has("valid")).isFalse();
        assertThat(mapper.readValue(json, CapabilityDefinitionDTO.class)).isEqualTo(declaration);
    }

    @Test
    void rejectsEmptyDuplicateAndBlankDependencies() {
        assertThat(CapabilityDefinitionDTO.builder().code("cap").includes(List.of()).build().isValid()).isFalse();
        assertThat(CapabilityDefinitionDTO.builder().code("cap").includes(List.of("read", "read")).build().isValid()).isFalse();
        assertThat(CapabilityDefinitionDTO.builder().code("cap").includes(List.of(" ")).build().isValid()).isFalse();
        assertThat(CapabilityDefinitionDTO.builder().code("cap").includes(List.of("read", "write")).build().isValid()).isTrue();
    }
}
