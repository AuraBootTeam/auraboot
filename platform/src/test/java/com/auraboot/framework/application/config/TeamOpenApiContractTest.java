package com.auraboot.framework.application.config;

import com.auraboot.framework.organization.dto.TeamMemberResponse;
import com.auraboot.framework.organization.dto.TeamResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TeamOpenApiContractTest {
    private final ObjectMapper wire = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void teamWithoutDescriptionOrLeaderSerializesTheDeclaredNulls() {
        var value = new TeamResponse();
        value.setPid("team-pid");
        value.setCode("SALES");
        value.setName("Sales");
        value.setStatus("active");
        value.setCreatedAt(Instant.parse("2026-10-18T00:00:00Z"));
        value.setUpdatedAt(value.getCreatedAt());
        var json = wire.valueToTree(value);
        var schema = Json31.mapper().valueToTree(new ModelConverters(true).readAll(TeamResponse.class).get("TeamResponse"));
        for (var field : new String[]{"description", "leaderId", "leaderName"}) {
            assertThat(json.has(field)).isTrue();
            assertThat(json.get(field).isNull()).isTrue();
            assertThat(schema.at("/properties/" + field + "/type").toString()).contains("null");
        }
        assertThat(schema.get("required").toString()).contains("pid", "code", "name", "status", "memberCount", "createdAt", "updatedAt");
        assertThat(schema.at("/properties/createdAt/format").asText()).isEqualTo("date-time");
    }

    @Test
    void membershipRetainsTheNumericLegacyUserIdAndNullableRelatedIdentity() {
        var value = new TeamMemberResponse();
        value.setPid("team-membership-pid");
        value.setUserId(42L);
        value.setRole("member");
        value.setJoinedAt(Instant.parse("2026-10-18T00:00:00Z"));
        var json = wire.valueToTree(value);
        var schema = Json31.mapper().valueToTree(new ModelConverters(true).readAll(TeamMemberResponse.class).get("TeamMemberResponse"));
        assertThat(json.get("userId").isIntegralNumber()).isTrue();
        assertThat(json.get("userId").longValue()).isEqualTo(42L);
        assertThat(json.get("pid").asText()).isEqualTo("team-membership-pid");
        assertThat(schema.at("/properties/userId/type").toString()).contains("integer");
        assertThat(schema.at("/properties/userId/format").asText()).isEqualTo("int64");
        for (var field : new String[]{"userPid", "memberPid", "userName", "userEmail"}) {
            assertThat(json.has(field)).isTrue();
            assertThat(json.get(field).isNull()).isTrue();
            assertThat(schema.at("/properties/" + field + "/type").toString()).contains("null");
        }
        assertThat(schema.get("required").toString()).contains("pid", "userId", "role", "joinedAt");
    }
}
