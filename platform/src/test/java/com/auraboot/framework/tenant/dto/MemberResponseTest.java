package com.auraboot.framework.tenant.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MemberResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesPublicPidsWithoutInternalIds() throws Exception {
        MemberResponse response = new MemberResponse();
        response.setId(327410003776507904L);
        response.setPid("member_e2e_viewer");
        response.setUserId(327410003776507905L);
        response.setTenantId(100L);
        response.setStatus("active");

        MemberResponse.UserInfo user = new MemberResponse.UserInfo();
        user.setId(327410003776507906L);
        user.setPid("user_e2e_viewer");
        user.setEmail("viewer@example.com");
        response.setUser(user);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertEquals("member_e2e_viewer", json.path("pid").asText());
        assertEquals("user_e2e_viewer", json.path("user").path("pid").asText());
        assertEquals("viewer@example.com", json.path("user").path("email").asText());
        assertFalse(json.has("id"));
        assertFalse(json.has("userId"));
        assertFalse(json.has("tenantId"));
        assertFalse(json.path("user").has("id"));
    }
}
