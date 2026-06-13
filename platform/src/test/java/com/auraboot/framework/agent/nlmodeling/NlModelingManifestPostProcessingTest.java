package com.auraboot.framework.agent.nlmodeling;

import com.auraboot.framework.agent.nlmodeling.dto.NlModelingRequest;
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
    void generatePageDsl_blankOrNullMessage_returnsNullWithoutCallingLlm() {
        // The in-designer page-gen endpoint guards empty input before any LLM call.
        // (The real LLM path is covered by the host-first designer golden, not here.)
        NlModelingService svc = new NlModelingService(null, null, mapper);
        assertNull(svc.generatePageDsl("a page-gen system prompt", null));
        assertNull(svc.generatePageDsl("a page-gen system prompt", "   "));
    }

    @Test
    void emptyOptions_deserializeToNull_notFalse_soGenerationStaysOn() throws Exception {
        // Regression: with primitive boolean + @Builder.Default, Jackson deserialized
        // `options:{}` to all-false, silently disabling page/command/menu generation
        // for callers that send empty or partial options. Boxed Boolean leaves omitted
        // fields null (treated as "generate" downstream); only explicit false disables.
        NlModelingRequest empty = mapper.readValue(
                "{\"description\":\"x\",\"options\":{}}", NlModelingRequest.class);
        assertNull(empty.getOptions().getGeneratePages(), "omitted option must be null, not false");
        assertNull(empty.getOptions().getGenerateCommands());
        assertNull(empty.getOptions().getGenerateMenus());

        NlModelingRequest explicit = mapper.readValue(
                "{\"description\":\"x\",\"options\":{\"generatePages\":false}}", NlModelingRequest.class);
        assertEquals(Boolean.FALSE, explicit.getOptions().getGeneratePages(),
                "an explicit false must still be honored");

        // The builder path (used when options is null) still defaults everything on.
        assertEquals(Boolean.TRUE, NlModelingRequest.Options.builder().build().getGeneratePages());
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

    @Test
    void buildManifest_synthesizesBindings_andDowngradesOrphanEnum() throws Exception {
        // Real-world LLM output (verified live): a single model + fields, but NO
        // modelFieldBindings and an enum field referencing an undefined dict — both
        // make the strict import reject the plugin. Conformance must fix both.
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .models(List.of(mutable("code", "equipment")))
                .fields(List.of(
                        mutable("code", "name", "dataType", "string", "constraints", mutable("required", true)),
                        mutable("code", "status", "dataType", "enum", "dictCode", "equipment_status")))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("x", res));

        JsonNode bindings = m.get("modelFieldBindings");
        assertEquals(2, bindings.size(), "a binding must be synthesized for every field");
        assertEquals("equipment", bindings.get(0).get("modelCode").asText());
        assertEquals("name", bindings.get(0).get("fieldCode").asText());
        assertEquals(1, bindings.get(0).get("sequence").asInt());
        assertTrue(bindings.get(0).get("required").asBoolean(), "required must come from field constraints");
        assertFalse(bindings.get(1).get("required").asBoolean());

        JsonNode status = m.get("fields").get(1);
        assertEquals("string", status.get("dataType").asText(), "orphan enum must downgrade to string");
        assertFalse(status.has("dictCode"), "the dangling dict reference must be dropped");
    }

    @Test
    void synthesizeBindings_keepsExistingBindings() {
        var existing = List.of(mutable("modelCode", "book", "fieldCode", "title"));
        var out = NlModelingService.synthesizeBindings(
                List.of(mutable("code", "book")),
                List.of(mutable("code", "title", "dataType", "string")),
                existing);
        assertSame(existing, out, "existing LLM bindings must be left untouched");
    }

    @Test
    void synthesizeBindings_multiModel_doesNotGuess() {
        var out = NlModelingService.synthesizeBindings(
                List.of(mutable("code", "a"), mutable("code", "b")),
                List.of(mutable("code", "f1", "dataType", "string")),
                List.of());
        assertTrue(out.isEmpty(), "ambiguous multi-model assignment must not be guessed");
    }

    @Test
    void downgradeOrphanEnumFields_keepsEnumWithMatchingDict() {
        var fields = new java.util.ArrayList<Map<String, Object>>();
        fields.add(mutable("code", "status", "dataType", "enum", "dictCode", "status_dict"));
        NlModelingService.downgradeOrphanEnumFields(fields, List.of(mutable("code", "status_dict")));
        assertEquals("enum", fields.get(0).get("dataType"), "enum with a defined dict must be kept");
        assertEquals("status_dict", fields.get(0).get("dictCode"));
    }

    @Test
    void buildManifest_synthesizesCrudCommands_whenNoneGenerated() throws Exception {
        // Permissions are derived from commands; a model with no commands gets no
        // model.<code>.create permission, so dynamic CRUD 403s. Conformance must
        // synthesize default CRUD commands for the single-model case.
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .models(List.of(mutable("code", "equipment")))
                .fields(List.of(mutable("code", "name", "dataType", "string"),
                        mutable("code", "location", "dataType", "string")))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("equipment_mgmt", res));

        JsonNode cmds = m.get("commands");
        assertEquals(3, cmds.size(), "create/update/delete must be synthesized");
        assertEquals("equipment_mgmt:create_equipment", cmds.get(0).get("code").asText());
        assertEquals("create", cmds.get(0).get("type").asText());
        assertEquals("equipment", cmds.get(0).get("modelCode").asText());
        assertEquals(2, cmds.get(0).get("inputFields").size(), "create binds all fields");
        assertEquals("delete", cmds.get(2).get("type").asText());
        assertEquals(0, cmds.get(2).get("inputFields").size(), "delete needs no input fields");
    }

    @Test
    void synthesizeCrudCommands_keepsExistingAndSkipsMultiModel() {
        var existing = List.of(mutable("code", "x:create_y", "type", "create"));
        assertSame(existing, NlModelingService.synthesizeCrudCommands("x", List.of(mutable("code", "y")),
                List.of(), existing), "existing LLM commands must be left untouched");
        assertTrue(NlModelingService.synthesizeCrudCommands("x",
                List.of(mutable("code", "a"), mutable("code", "b")), List.of(), List.of()).isEmpty(),
                "ambiguous multi-model must not be guessed");
    }

    @Test
    void buildManifest_synthesizesListFormPages_andMenu() throws Exception {
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .models(List.of(mutable("code", "equipment")))
                .fields(List.of(mutable("code", "name", "dataType", "string"),
                        mutable("code", "location", "dataType", "string")))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("equipment_mgmt", res));

        JsonNode pages = m.get("pages");
        assertEquals(2, pages.size(), "a list + form page must be synthesized");
        JsonNode listPage = pages.get(0);
        assertEquals("equipment_list", listPage.get("pageKey").asText());
        assertEquals("list", listPage.get("kind").asText());
        // V2 flat format: top-level kind/layout/blocks (the strict import requires these)
        assertEquals("stack", listPage.get("layout").get("type").asText(),
                "V2 flat page must carry a top-level layout");
        assertEquals(4, listPage.get("schemaVersion").asInt());
        JsonNode blocks = listPage.get("blocks");
        assertEquals("toolbar", blocks.get(0).get("blockType").asText());
        assertEquals("create", blocks.get(0).get("buttons").get(0).get("action").asText());
        JsonNode table = blocks.get(1);
        assertEquals("table", table.get("blockType").asText());
        // one column per field + a trailing action column
        assertEquals(3, table.get("columns").size());
        JsonNode actionCol = table.get("columns").get(2);
        assertTrue(actionCol.get("isActionColumn").asBoolean());
        assertEquals("equipment_mgmt:delete_equipment",
                actionCol.get("buttons").get(1).get("commandCode").asText());

        JsonNode formPage = pages.get(1);
        assertEquals("equipment_form", formPage.get("pageKey").asText());
        assertEquals("form", formPage.get("kind").asText());
        assertEquals("stack", formPage.get("layout").get("type").asText());
        JsonNode formButtons = formPage.get("blocks").get(1);
        assertEquals("form-buttons", formButtons.get("blockType").asText());
        assertEquals("equipment_mgmt:create_equipment",
                formButtons.get("buttons").get(0).get("commandCode").asText());

        // menu uses the canonical /p/<model> dynamic-page route (snake_case model code);
        // the frontend resolves it to <model>_list. Kebab-casing would 404.
        JsonNode menus = m.get("menus");
        assertEquals(1, menus.size());
        assertEquals("/p/equipment", menus.get(0).get("path").asText(),
                "synthesized menu must use the canonical /p/<snake_model> route");
    }

    @Test
    void conformFieldLabels_synthesizesBusinessLabelFromCode() {
        var noLabel = mutable("code", "unit_price", "dataType", "decimal");
        var rawLabel = mutable("code", "name", "dataType", "string", "displayName:en", "name");
        var goodLabel = mutable("code", "status", "dataType", "string", "displayName:zh-CN", "状态");
        NlModelingService.conformFieldLabels(List.of(noLabel, rawLabel, goodLabel));
        // snake_case humanized, both locales filled
        assertEquals("Unit Price", noLabel.get("displayName:en"));
        assertEquals("Unit Price", noLabel.get("displayName:zh-CN"));
        // a raw label equal to the code is overwritten with a business label
        assertEquals("Name", rawLabel.get("displayName:en"));
        // an existing business label (any locale) is left untouched
        assertEquals("状态", goodLabel.get("displayName:zh-CN"));
        assertFalse(goodLabel.containsKey("displayName:en"), "fields with a business label must not be rewritten");
    }

    @Test
    void humanize_splitsSnakeAndKebab() {
        assertEquals("Unit Price", NlModelingService.humanize("unit_price"));
        assertEquals("Order Line Item", NlModelingService.humanize("order-line-item"));
        assertEquals("Name", NlModelingService.humanize("name"));
        assertNull(NlModelingService.humanize(null));
    }

    @Test
    void conformModels_defaultsMissingModelType() {
        var withType = mutable("code", "a", "modelType", "lookup");
        var noType = mutable("code", "b");
        var blankType = mutable("code", "c", "modelType", "");
        NlModelingService.conformModels(List.of(withType, noType, blankType));
        assertEquals("lookup", withType.get("modelType"), "an explicit modelType must be preserved");
        assertEquals("entity", noType.get("modelType"), "missing modelType must default to entity");
        assertEquals("entity", blankType.get("modelType"), "blank modelType must default to entity");
    }

    @Test
    void synthesizePages_keepsExistingAndSkipsMultiModel() {
        var existing = List.of(mutable("pageKey", "x_list"));
        assertSame(existing, NlModelingService.synthesizePages("x", List.of(mutable("code", "x")),
                List.of(), existing));
        assertTrue(NlModelingService.synthesizePages("x",
                List.of(mutable("code", "a"), mutable("code", "b")), List.of(), List.of()).isEmpty());
    }
}
