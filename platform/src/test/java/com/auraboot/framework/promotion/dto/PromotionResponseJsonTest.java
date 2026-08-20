package com.auraboot.framework.promotion.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesEnvironmentAndActorSnowflakeIdsAsStrings() throws Exception {
        PromotionResponse response = new PromotionResponse();
        response.setSourceEnvId(346054352965865472L);
        response.setTargetEnvId(346054353146220544L);
        response.setCreatedBy(345780496019623936L);
        response.setAppliedBy(345780496019623936L);
        response.setRejectedBy(345780496019623936L);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.path("sourceEnvId").textValue()).isEqualTo("346054352965865472");
        assertThat(json.path("targetEnvId").textValue()).isEqualTo("346054353146220544");
        assertThat(json.path("createdBy").isTextual()).isTrue();
        assertThat(json.path("appliedBy").isTextual()).isTrue();
        assertThat(json.path("rejectedBy").isTextual()).isTrue();
    }
}
