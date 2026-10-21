package com.auraboot.framework.application.config;

import com.auraboot.framework.permission.capability.Capability;
import com.auraboot.framework.permission.capability.CapabilityGroup;
import com.auraboot.framework.permission.capability.CapabilityResolver;
import com.auraboot.framework.permission.capability.CapabilitySelectionPreview;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.auraboot.framework.rbac.dto.RoleResponse;
import com.auraboot.framework.rbac.entity.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RoleCapabilityOpenApiContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void declaredCapabilitiesWithoutOptionalDisplayMetadataKeepNullsAndConcreteCollections() {
        var declaration = CapabilityDefinitionDTO.builder().code("sales.cap.read")
                .includes(List.of("sales.customer.read", "sales.customer.export")).build();
        var groups = new CapabilityResolver().resolve(List.of(declaration), declaration.getIncludes(),
                Set.of("sales.customer.read"));
        var group = groups.getFirst();
        var capability = group.getCapabilities().getFirst();
        var json = mapper.valueToTree(capability);
        assertThat(group.getGroup()).isNull();
        assertThat(json.size()).isEqualTo(16);
        for (String field : List.of("group", "description", "tier", "unlockedMenus")) {
            assertThat(json.has(field)).as(field).isTrue();
            assertThat(json.get(field).isNull()).as(field).isTrue();
        }
        assertThat(json.path("label").asText()).isEqualTo("sales.cap.read");
        assertThat(json.path("authorizationState").asText()).isEqualTo("partial");
        assertThat(capability.getMissingCodes()).containsExactly("sales.customer.export");
        assertThat(capability.getLocalizedLabels()).isEmpty();
        assertThat(capability.getLocalizedDescriptions()).isEmpty();
        assertThat(capability.getDisplayGroupOrder()).isEqualTo(10000);
        assertThat(capability.getDisplayOrder()).isEqualTo(100);
    }

    @Test
    void derivedCapabilitiesAndEmptyPreviewUseTheSameRequiredWireFields() {
        var groups = new CapabilityResolver().resolve(List.of(), List.of("sales.customer.read"), Set.of());
        var capability = groups.getFirst().getCapabilities().getFirst();
        assertThat(capability.isConventionDerived()).isTrue();
        assertThat(capability.getAuthorizationState()).isEqualTo("none");
        assertThat(mapper.valueToTree(new CapabilitySelectionPreview(List.of(), List.of(), List.of(),
                List.of(), List.of())).size()).isEqualTo(5);
        var models = new ModelConverters(true);
        var capabilitySchema = models.readAll(Capability.class).get("Capability");
        assertThat(capabilitySchema.getRequired()).hasSize(16);
        var json = Json31.mapper().valueToTree(capabilitySchema);
        for (String field : List.of("group", "description", "tier", "unlockedMenus")) {
            assertThat(json.at("/properties/" + field + "/type").toString()).as(field).contains("null");
        }
        assertThat(json.at("/properties/authorizationState/enum").toString()).contains("none", "partial", "full");
        assertThat(models.readAll(CapabilityGroup.class).get("CapabilityGroup").getRequired())
                .containsExactlyInAnyOrder("group", "capabilities");
        assertThat(models.readAll(CapabilitySelectionPreview.class).get("CapabilitySelectionPreview").getRequired())
                .containsExactlyInAnyOrder("grantedCodes", "revokedCodes", "preservedCodes", "resultingCapabilities", "relatedMenus");
    }

    @Test
    void roleProjectionRetainsNullableDatabaseColumnsAndOmitsInternalIdentities() {
        var role = new Role();
        role.setId(9007199254740993L);
        role.setTenantId(9007199254740995L);
        role.setPid("role-pid");
        role.setName("Role name");
        role.setIsDefault(null);
        role.setIsSystem(null);
        var json = mapper.valueToTree(RoleResponse.from(role));
        assertThat(json.size()).isEqualTo(13);
        assertThat(json.path("pid").asText()).isEqualTo("role-pid");
        assertThat(json.has("id")).isFalse();
        assertThat(json.has("tenantId")).isFalse();
        var schema = new ModelConverters(true).readAll(RoleResponse.class).get("RoleResponse");
        assertThat(schema.getRequired()).hasSize(13);
        var schemaJson = Json31.mapper().valueToTree(schema);
        for (String field : List.of("code", "description", "type", "scopeType", "defaultDataScopeType",
                "priority", "status", "isDefault", "isSystem", "createdAt", "updatedAt")) {
            assertThat(json.get(field).isNull()).as(field).isTrue();
            assertThat(schemaJson.at("/properties/" + field + "/type").toString()).as(field).contains("null");
        }
    }
}
