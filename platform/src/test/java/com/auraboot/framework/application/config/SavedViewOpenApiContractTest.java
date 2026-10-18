package com.auraboot.framework.application.config;

import com.auraboot.framework.view.dto.PublicSharedViewResponse;
import com.auraboot.framework.tenant.dto.MemberSearchOption;
import com.auraboot.framework.view.dto.SavedViewAuditEventDTO;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.ViewShareResponse;
import com.auraboot.framework.view.entity.ViewConfig;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Producer metadata checks; real HTTP, authorization and persisted overlays have separate evidence. */
class SavedViewOpenApiContractTest {
    private JsonNode schema(Class<?> type) {
        return Json31.mapper().valueToTree(new ModelConverters(true).readAll(type).get(type.getSimpleName()));
    }

    @Test
    void stableViewIdentityIsRequiredAndInternalDatabaseIdsAreAbsent() {
        var node = schema(SavedViewDTO.class);
        assertThat(node.path("required").toString()).contains("pid", "name", "modelCode", "scope", "viewType");
        assertThat(node.at("/properties/id").isMissingNode()).isTrue();
        assertThat(node.at("/properties/tenantId").isMissingNode()).isTrue();
        for (var field : new String[]{"description", "pageKey", "ownerId", "teamId", "roleId", "ownerName", "teamName", "roleName"}) {
            assertThat(node.at("/properties/" + field + "/type").toString()).contains("null");
        }
    }

    @Test
    void optionalConfigPropertiesAreNullableButFilterValuesRemainArbitraryJson() {
        var node = schema(ViewConfig.class);
        assertThat(node.at("/properties/density/type").toString()).contains("null");
        assertThat(node.at("/properties/showRowNumbers/type").toString()).contains("null");
        var filters = schema(ViewConfig.FilterConfig.class);
        assertThat(filters.at("/properties/value").isObject()).isTrue();
        assertThat(filters.at("/properties/value/type").isMissingNode()).isTrue();
        assertThat(filters.at("/properties/value/$ref").isMissingNode()).isTrue();
    }

    @Test
    void auditMetadataDescribesJsonRatherThanJacksonImplementationMethods() {
        var metadata = schema(SavedViewAuditEventDTO.class).at("/properties/metadata");
        assertThat(metadata.isObject()).isTrue();
        assertThat(metadata.path("$ref").isMissingNode()).isTrue();
        assertThat(metadata.path("properties").isMissingNode()).isTrue();
    }

    @Test
    void collaboratorSearchPreservesNoEmailAccountsAndMissingOptionalIdentityMetadata() {
        var node = schema(MemberSearchOption.UserIdentity.class);
        for (var field : new String[]{"email", "realName", "avatar"}) {
            assertThat(node.at("/properties/" + field + "/type").toString()).contains("null");
        }
    }

    @Test
    void editorCapabilityReasonsSurviveThePersistedConfigurationRoundTrip() throws Exception {
        var mapper = Json31.mapper();
        var config = mapper.readValue("{\"meta\":{\"capabilityStatus\":\"degraded\",\"capabilityReasonCodes\":[\"UNKNOWN_FIELD\"]}}", ViewConfig.class);
        assertThat(config.getMeta().getCapabilityReasonCodes()).containsExactly("UNKNOWN_FIELD");
        assertThat(mapper.valueToTree(config).at("/meta/capabilityReasonCodes/0").asText()).isEqualTo("UNKNOWN_FIELD");
    }

    @Test
    void publicSharingExposesConfigurationAndLinkMetadataWithoutInventedRecordPayloads() throws Exception {
        var response = new PublicSharedViewResponse("Owned view", "ab_team", "table", Map.of("custom", Map.of("value", 1)));
        var json = Json31.mapper().valueToTree(response);
        assertThat(json.path("name").asText()).isEqualTo("Owned view");
        assertThat(json.at("/viewConfig/custom/value").asInt()).isEqualTo(1);
        assertThat(json.has("records")).isFalse();
        assertThat(json.has("columns")).isFalse();
        assertThat(json.has("viewName")).isFalse();
        var link = schema(ViewShareResponse.class);
        assertThat(link.at("/properties/expiresAt/type").toString()).contains("null");
        assertThat(link.at("/properties/expiresAt/format").asText()).isEqualTo("date-time");
        assertThat(link.path("required").toString()).contains("token", "shareUrl", "passwordProtected");
    }
}
