package com.auraboot.framework.view.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SavedViewDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesPublicPidWithoutInternalIds() throws Exception {
        SavedViewDTO dto = SavedViewDTO.builder()
                .id(1L)
                .tenantId(2L)
                .pid("view-pid")
                .name("My View")
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(dto));

        assertThat(json.has("pid")).isTrue();
        assertThat(json.get("pid").asText()).isEqualTo("view-pid");
        assertThat(json.has("id")).isFalse();
        assertThat(json.has("tenantId")).isFalse();
    }
}
