package com.auraboot.framework.environment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesSnowflakeIdAsStringForJavaScriptClients() throws Exception {
        EnvironmentResponse response = new EnvironmentResponse();
        response.setId(346054353146220544L);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.path("id").isTextual()).isTrue();
        assertThat(json.path("id").textValue()).isEqualTo("346054353146220544");
    }
}
