package com.auraboot.framework.auth.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationResponseSecurityTest {

    @Test
    void genericStringFormattingIncludesOnlyExplicitlyAllowedStatusFields() {
        AuthenticationResponse response = new AuthenticationResponse(
                "jwt-secret-marker", 123456789L, "user-pid-marker", "username-marker",
                987654321L, "member");
        response.setMergeToken("merge-secret-marker");
        response.setMergeProvider("provider-marker");

        for (String formatted : new String[] {
                response.toString(), String.format("%s", response), Map.of("result", response).toString()
        }) {
            assertThat(formatted)
                    .contains("tenantStatus=member", "nextAction=ENTER", "mergeRequired=false")
                    .doesNotContain("jwt-secret-marker", "merge-secret-marker", "user-pid-marker",
                            "username-marker", "123456789", "987654321", "provider-marker");
        }
    }

    @Test
    void stringRedactionDoesNotChangeAuthenticationJsonProtocol() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AuthenticationResponse login = new AuthenticationResponse(
                "wire-jwt", 42L, "wire-pid", "wire-user", 7L, "member");
        var json = mapper.readTree(mapper.writeValueAsString(login));
        assertThat(json.path("jwt").asText()).isEqualTo("wire-jwt");
        assertThat(json.path("userId").asText()).isEqualTo("42");
        assertThat(json.path("tenantId").asText()).isEqualTo("7");
        assertThat(json.path("nextAction").asText()).isEqualTo("ENTER");

        AuthenticationResponse merge = AuthenticationResponse.mergeRequired("wire-merge", "wechat");
        var mergeJson = mapper.readTree(mapper.writeValueAsString(merge));
        assertThat(mergeJson.path("mergeToken").asText()).isEqualTo("wire-merge");
        assertThat(mergeJson.path("mergeRequired").asBoolean()).isTrue();
        assertThat(merge.toString()).doesNotContain("wire-merge", "wechat");
    }
}
