package com.auraboot.framework.agent.nlmodeling;

import com.auraboot.framework.agent.nlmodeling.dto.NlModelingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the deterministic conformance post-processing in
 * {@link NlModelingService#buildPluginManifestJson} — closes Prompt-to-App
 * import gaps ④ (dicts channel), ⑤/⑦ (command.type / field.dataType case) and
 * ⑧ (dynamic-menu pageKey) without an LLM or DB.
 */
class NlModelingManifestPostProcessingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NlModelingService service = new NlModelingService(null, null, mapper);

    private static Map<String, Object> mutable(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void buildManifest_lowercasesTypes_derivesMenuPageKey_carriesDicts() throws Exception {
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .fields(List.of(mutable("code", "status", "dataType", "ENUM")))
                .commands(List.of(mutable("code", "x:create_y", "type", "CREATE")))
                .menus(List.of(mutable("code", "m1", "path", "/dynamic/inspection-record")))
                .dicts(List.of(mutable("code", "status_dict", "items", List.of())))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("x", res));

        assertEquals("create", m.get("commands").get(0).get("type").asText(),
                "command.type must be lower-cased for the import executor");
        assertEquals("enum", m.get("fields").get(0).get("dataType").asText(),
                "field.dataType must be lower-cased");
        assertEquals("inspection_record_list", m.get("menus").get(0).get("pageKey").asText(),
                "dynamic-path menu must derive pageKey = <snake_model>_list");
        assertTrue(m.has("dicts"), "dicts channel must be present in the manifest");
        assertEquals(1, m.get("dicts").size(), "dicts must be carried through");
        assertEquals("status_dict", m.get("dicts").get(0).get("code").asText());
    }

    @Test
    void buildManifest_alreadyConformant_isLeftUntouched() throws Exception {
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .fields(List.of(mutable("code", "name", "dataType", "string")))
                .commands(List.of(mutable("code", "x:update_y", "type", "update")))
                .menus(List.of(mutable("code", "m1", "path", "/dynamic/book", "pageKey", "book_list")))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("x", res));

        assertEquals("update", m.get("commands").get(0).get("type").asText());
        assertEquals("string", m.get("fields").get(0).get("dataType").asText());
        assertEquals("book_list", m.get("menus").get(0).get("pageKey").asText(),
                "an explicit pageKey must be preserved");
        assertTrue(m.get("dicts").isArray(), "dicts must default to an empty array, never null");
        assertEquals(0, m.get("dicts").size());
    }

    @Test
    void deriveDynamicMenuPageKeys_ignoresNonDynamicMenus() {
        Map<String, Object> nonDynamic = mutable("code", "m", "path", "/static/page");
        Map<String, Object> noPath = mutable("code", "m2");
        NlModelingService.deriveDynamicMenuPageKeys(List.of(nonDynamic, noPath));
        assertFalse(nonDynamic.containsKey("pageKey"), "non-dynamic path must not get a pageKey");
        assertFalse(noPath.containsKey("pageKey"), "menu without a path must not get a pageKey");
    }

    @Test
    void lowercaseStringKey_nullSafe() {
        assertTrue(NlModelingService.lowercaseStringKey(null, "type").isEmpty());
        assertTrue(NlModelingService.deriveDynamicMenuPageKeys(null).isEmpty());
    }
}
