package com.auraboot.framework.application.config;

import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.user.controller.SessionController;
import com.auraboot.framework.user.dto.ActiveSessionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionOpenApiContractTest {
    @Test
    void controllerKeepsTheFivePublicFieldsAndScopesTheLookupToTheCaller() {
        var service = mock(SessionManagementService.class);
        var session = new UserSession();
        session.setPid("public-session-pid");
        session.setUserId(42L);
        session.setTokenHash("private-hash-never-on-wire");
        session.setCreatedAt(Instant.parse("2026-10-18T01:02:03Z"));
        session.setLastActiveAt(Instant.parse("2026-10-18T04:05:06Z"));
        when(service.getActiveSessions(42L)).thenReturn(List.of(session));
        var response = new SessionController(service).getActiveSessions(42L);
        verify(service).getActiveSessions(42L);
        var json = new ObjectMapper().valueToTree(response);
        var item = json.at("/data/0");
        assertThat(item.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("pid", "deviceInfo", "ipAddress", "createdAt", "lastActiveAt");
        assertThat(item.get("pid").asText()).isEqualTo("public-session-pid");
        assertThat(item.get("deviceInfo").asText()).isEqualTo("Unknown");
        assertThat(item.get("ipAddress").asText()).isEqualTo("Unknown");
        assertThat(item.get("createdAt").asText()).isEqualTo("2026-10-18T01:02:03Z");
        assertThat(item.get("lastActiveAt").asText()).isEqualTo("2026-10-18T04:05:06Z");
        assertThat(json.toString()).doesNotContain("private-hash", "userId", "tokenHash");
    }

    @Test
    void schemaRequiresEachActualFieldAndRetainsDateTimeStrings() {
        var schema = Json31.mapper().valueToTree(new ModelConverters(true)
                .readAll(ActiveSessionResponse.class).get("ActiveSessionResponse"));
        var required = new java.util.HashSet<String>();
        schema.get("required").forEach(field -> required.add(field.asText()));
        assertThat(required).isEqualTo(Set.of("pid", "deviceInfo", "ipAddress", "createdAt", "lastActiveAt"));
        for (var field : new String[]{"createdAt", "lastActiveAt"}) {
            assertThat(schema.at("/properties/" + field + "/type").asText()).isEqualTo("string");
            assertThat(schema.at("/properties/" + field + "/format").asText()).isEqualTo("date-time");
        }
    }
}
