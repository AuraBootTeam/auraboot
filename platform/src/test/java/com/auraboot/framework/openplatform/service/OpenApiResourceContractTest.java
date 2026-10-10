package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiResourceContractTest {
    private Field field(String source, Type type, boolean write, boolean required, boolean nullable) {
        return new Field(source, type, true, write, required, nullable, Set.of(), false, null, Map.of(), null);
    }

    private OpenApiResourceContract contract(Map<String, Field> fields) {
        return new OpenApiResourceContract("customers", "crm_customer", 1,
                Set.of(Operation.GET, Operation.CREATE, Operation.UPDATE),
                Map.of(Operation.GET, "customers.read", Operation.CREATE, "customers.create", Operation.UPDATE, "customers.update"),
                DataPolicy.INSTALLATION_FILTER, fields, 50, 8, 5000, Set.of("created", "updated"));
    }

    private Map<String, Field> fields() {
        return Map.of("pid", field("pid", Type.PID, false, false, false),
                "name", field("crm_name", Type.STRING, true, true, false),
                "phone", field("crm_phone", Type.STRING, true, false, true));
    }

    @Test void mapsAliasesAndPreservesPatchOmissionAndExplicitNull() {
        var definition = contract(fields());
        assertThat(definition.mapWrite(Map.of("name", "Alice"), true)).containsExactly(Map.entry("crm_name", "Alice"));
        var patch = new LinkedHashMap<String, Object>();
        patch.put("phone", null);
        assertThat(definition.mapWrite(patch, false)).containsEntry("crm_phone", null).doesNotContainKey("crm_name");
        assertThatThrownBy(() -> definition.mapWrite(Map.of("phone", "123"), true)).hasMessageContaining("Required");
        assertThatThrownBy(() -> definition.mapWrite(Map.of("pid", "other-record"), false)).hasMessageContaining("read-only");
        assertThatThrownBy(() -> definition.mapWrite(Map.of("_expectedVersion", 1), false)).hasMessageContaining("Unknown");
    }

    @Test void projectsOnlyPublicFieldsAndRejectsNumericPid() {
        var definition = contract(fields());
        assertThat(definition.project(Map.of("pid", "customer-pid", "crm_name", "Alice", "id", 99L, "tenant_id", 7L, "private_notes", "secret")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("pid", "customer-pid", "name", "Alice"));
        assertThatThrownBy(() -> definition.project(Map.of("pid", 9007199254740993L))).hasMessageContaining("String");
        assertThatThrownBy(() -> definition.project(Map.of("pid", "9007199254740993"))).hasMessageContaining("public PID");
    }

    @Test void cannotPublishInternalFieldUnderAnInnocentAlias() {
        var fields = new LinkedHashMap<>(fields());
        fields.put("owner", field("created_by", Type.INTEGER, false, false, true));
        assertThatThrownBy(() -> contract(fields)).hasMessageContaining("Internal");
    }

    @Test void rejectsNestedUnknownInputsAndProjectsNestedSecretsAway() {
        var fields = new LinkedHashMap<>(fields());
        fields.put("address", new Field("crm_address", Type.OBJECT, true, true, false, true,
                Set.of(), false, null, Map.of("city", field("city_name", Type.STRING, true, true, false)), null));
        var definition = contract(fields);
        assertThat(definition.mapWrite(Map.of("address", Map.of("city", "Shanghai")), false))
                .containsEntry("crm_address", Map.of("city_name", "Shanghai"));
        assertThatThrownBy(() -> definition.mapWrite(Map.of("address", Map.of("city", "Shanghai", "secret", "x")), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(definition.project(Map.of("crm_address", Map.of("city_name", "Shanghai", "private", "secret"))))
                .containsEntry("address", Map.of("city", "Shanghai"));
    }

    @Test void acceptsOnlyAdditiveOptionalFieldRevisions() {
        var original = contract(fields());
        var added = new LinkedHashMap<>(fields());
        added.put("nickname", field("crm_nickname", Type.STRING, true, false, true));
        assertThatCode(() -> contract(added).requireCompatibleWith(original)).doesNotThrowAnyException();
        added.put("nickname", field("crm_nickname", Type.STRING, true, true, false));
        assertThatThrownBy(() -> contract(added).requireCompatibleWith(original)).hasMessageContaining("required");
        var removed = new LinkedHashMap<>(fields());
        removed.remove("phone");
        assertThatThrownBy(() -> contract(removed).requireCompatibleWith(original)).hasMessageContaining("cannot change");
    }

    @Test void callerCannotMutatePublishedFieldOrOperationWhitelists() {
        var mutable = new LinkedHashMap<>(fields());
        var definition = contract(mutable);
        mutable.put("secret", field("crm_secret", Type.STRING, true, false, true));
        assertThat(definition.fields()).doesNotContainKey("secret");
        assertThatThrownBy(() -> definition.operations().add(Operation.DELETE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void publicJsonUsesOneLowercaseContractAndRoundTripsEnumMapKeys() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var definition = contract(fields());
        String json = mapper.writeValueAsString(definition);
        assertThat(json).contains("\"dataPolicy\":\"installation_filter\"", "\"type\":\"pid\"", "\"get\":\"customers.read\"");
        assertThat(mapper.readValue(json, OpenApiResourceContract.class)).isEqualTo(definition);
        assertThatThrownBy(() -> mapper.readValue(json.replace("installation_filter", "INSTALLATION_FILTER"), OpenApiResourceContract.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
