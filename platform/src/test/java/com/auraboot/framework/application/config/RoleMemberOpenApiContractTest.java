package com.auraboot.framework.application.config;

import com.auraboot.framework.rbac.dto.AddRoleMembersRequest;
import com.auraboot.framework.rbac.dto.RoleMemberDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RoleMemberOpenApiContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void candidatesSerializeNullEnrichmentAndAssignmentFieldsWithoutLosingPublicIdentity() {
        var json = mapper.valueToTree(new RoleMemberDTO(9007199254740993L, "member-pid",
                null, null, null, null, null, null, null));
        assertThat(json.size()).isEqualTo(9);
        assertThat(json.path("memberId").asLong()).isEqualTo(9007199254740993L);
        assertThat(json.path("memberPid").asText()).isEqualTo("member-pid");
        for (String field : new String[]{"userName", "email", "departmentName", "positionName",
                "assignedAt", "effectiveDate", "expiryDate"}) {
            assertThat(json.has(field)).as(field).isTrue();
            assertThat(json.get(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    void assignedMembersRetainIdentityAndNullableWindowWithAccurateSchema() {
        var member = new RoleMemberDTO(1L, "member-pid", "Display name", null,
                "Sales", null, Instant.parse("2026-01-02T03:04:05Z"),
                LocalDate.parse("2026-01-01"), null);
        var json = mapper.valueToTree(member);
        assertThat(json.path("memberPid").asText()).isEqualTo("member-pid");
        assertThat(json.path("assignedAt").isNull()).isFalse();
        assertThat(json.path("expiryDate").isNull()).isTrue();
        var schema = new ModelConverters(true).readAll(RoleMemberDTO.class).get("RoleMemberDTO");
        assertThat(schema.getRequired()).containsExactlyInAnyOrder("memberId", "memberPid", "userName",
                "email", "departmentName", "positionName", "assignedAt", "effectiveDate", "expiryDate");
        var schemaJson = Json31.mapper().valueToTree(schema);
        for (String field : new String[]{"userName", "email", "departmentName", "positionName",
                "assignedAt", "effectiveDate", "expiryDate"}) {
            assertThat(schemaJson.at("/properties/" + field + "/type").toString()).as(field).contains("null");
        }
        assertThat(schemaJson.at("/properties/memberPid/type").toString()).doesNotContain("null");
    }

    @Test
    void assignmentRequestAcceptsMissingOrExplicitNullDatesAndSchemaPreservesThatDistinction() throws Exception {
        assertThat(mapper.readValue("{\"memberPids\":[\"member-pid\"]}", AddRoleMembersRequest.class).getEffectiveDate()).isNull();
        assertThat(mapper.readValue("{\"effectiveDate\":null,\"expiryDate\":null}", AddRoleMembersRequest.class).getExpiryDate()).isNull();
        var json = Json31.mapper().valueToTree(new ModelConverters(true).readAll(AddRoleMembersRequest.class).get("AddRoleMembersRequest"));
        assertThat(json.at("/properties/effectiveDate/type").toString()).contains("null");
        assertThat(json.at("/properties/expiryDate/type").toString()).contains("null");
    }
}
