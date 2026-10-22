package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual metadata policy predicate, hermetic inputs; not a native or production approval. */
class ProductQuoteNativeSystemFieldsTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode manifest() throws Exception {
        return json.readTree("{\"pluginId\":\"com.auraboot.org-management\",\"namespace\":\"org\",\"version\":\"1.0.0\"}");
    }
    private Map<String, JsonNode> fields() throws Exception {
        return Map.of("created_at", json.readTree("{\"code\":\"created_at\",\"dataType\":\"datetime\"}"),
                "updated_at", json.readTree("{\"code\":\"updated_at\",\"dataType\":\"datetime\"}"));
    }
    private Map<String, Object> row() {
        var row = new LinkedHashMap<String, Object>();
        row.put("pid", "actual-test-created-field"); row.put("code", "created_at"); row.put("data_type", "datetime");
        row.put("binding_tenant_id", 17L); row.put("field_tenant_id", 17L); row.put("is_system_binding", true);
        row.put("editable", false); row.put("alias_code", null); row.put("field_plugin_pid", "actual-test-org");
        row.put("plugin_pid", "actual-test-org"); row.put("plugin_tenant_id", 17L);
        row.put("plugin_id", "com.auraboot.org-management"); row.put("namespace", "org"); row.put("version", "1.0.0");
        return row;
    }
    @Test void realOrgOwnedCanonicalTimestampNeedsNoFabricatedBindingTracking() throws Exception {
        assertEquals(Set.of("actual-test-created-field"), ProductQuoteReminderNativeFacet.nativeSystemFieldPids(
                List.of(row()), 17L, manifest(), fields()));
    }
    @Test void ordinaryForeignOrWrongCanonicalFieldCannotBeApprovedAsNative() throws Exception {
        for (String facet : List.of("ordinary", "tenant", "owner", "pluginTenant", "code", "type", "editable", "alias", "version")) {
            var row = row();
            switch (facet) {
                case "ordinary" -> row.put("is_system_binding", false);
                case "tenant" -> row.put("field_tenant_id", 18L);
                case "owner" -> row.put("plugin_id", "com.test.fake-org");
                case "pluginTenant" -> row.put("plugin_tenant_id", 18L);
                case "code" -> row.put("code", "arbitrary_field");
                case "type" -> row.put("data_type", "string");
                case "editable" -> row.put("editable", true);
                case "alias" -> row.put("alias_code", "private_alias");
                case "version" -> row.put("version", "other-version");
                default -> throw new AssertionError(facet);
            }
            assertThrows(AssertionError.class, () -> ProductQuoteReminderNativeFacet.nativeSystemFieldPids(
                    List.of(row), 17L, manifest(), fields()), facet);
        }
    }
    @Test void nullOwnedCanonicalSystemFieldCompatibilityDoesNotApproveOrdinaryFields() throws Exception {
        var row = row(); row.put("field_plugin_pid", null);
        assertEquals(Set.of("actual-test-created-field"), ProductQuoteReminderNativeFacet.nativeSystemFieldPids(
                List.of(row), 17L, manifest(), fields()));
        row.put("is_system_binding", false);
        assertThrows(AssertionError.class, () -> ProductQuoteReminderNativeFacet.nativeSystemFieldPids(
                List.of(row), 17L, manifest(), fields()));
    }
}
