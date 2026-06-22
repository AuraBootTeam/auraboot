package com.auraboot.framework.agent.nlmodeling;

import com.auraboot.framework.agent.nlmodeling.dto.NlModelingRequest;
import com.auraboot.framework.agent.nlmodeling.dto.NlModelingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void buildManifest_synthesizesDynamicCrudPermissions_forMenuPermissionRefs() throws Exception {
        // The system prompt tells the model to gate a child menu on dynamic.<model>.read but to
        // emit no explicit permissions (they are not platform-auto-created on model publish). The
        // manifest must synthesize the dynamic CRUD permissions so the menu->permission referential
        // check in PluginImportService.validateManifest passes at apply() time. (Live-surfaced gap:
        // "Menu 'NL_EQUIP_INSPECTION_LIST' references missing permission: dynamic.equip_inspection.read".)
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .models(List.of(mutable("code", "equip_inspection", "displayName:zh-CN", "设备点检")))
                .menus(List.of(mutable("code", "nl_ei_list", "path", "/p/equip_inspection",
                        "permissionCode", "dynamic.equip_inspection.read")))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("x", res));

        Set<String> permCodes = new HashSet<>();
        m.get("permissions").forEach(p -> permCodes.add(p.get("code").asText()));
        assertTrue(permCodes.contains("dynamic.equip_inspection.read"),
                "the menu-referenced dynamic read permission must be synthesized into the manifest");
        assertTrue(permCodes.containsAll(Set.of(
                        "dynamic.equip_inspection.read", "dynamic.equip_inspection.create",
                        "dynamic.equip_inspection.update", "dynamic.equip_inspection.delete")),
                "the full dynamic CRUD permission set must be synthesized for the model");
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizePageToV4_coercesUnknownBlockType_toKindDefault() {
        // Live-surfaced gap: the LLM confuses page kind with blockType, emitting a detail page with a
        // block typed "detail" — not a registered DslRegistry blockType — which the import gate rejects
        // ("[S-PAGE-BLOCK-TYPE] ... unknown blockType: 'detail'"). The normalizer must coerce it to a
        // valid kind-appropriate block ("description") so the page passes the gate.
        Map<String, Object> page = mutable(
                "kind", "detail",
                "blocks", List.of(mutable("id", "b1", "blockType", "detail",
                        "fields", List.of(mutable("field", "name")))));

        Map<String, Object> out = NlModelingService.normalizePageToV4(page);

        List<Map<String, Object>> blocks = (List<Map<String, Object>>) out.get("blocks");
        assertEquals("description", blocks.get(0).get("blockType"),
                "an unknown 'detail' blockType on a detail page must be coerced to 'description'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizePageToV4_coercesUnknownBlockType_byPageKind() {
        // list → table, form → form-section (kind-appropriate defaults for unknown block types).
        Map<String, Object> listPage = NlModelingService.normalizePageToV4(mutable(
                "kind", "list", "blocks", List.of(mutable("id", "x", "blockType", "grid"))));
        assertEquals("table", ((List<Map<String, Object>>) listPage.get("blocks")).get(0).get("blockType"));

        Map<String, Object> formPage = NlModelingService.normalizePageToV4(mutable(
                "kind", "form", "blocks", List.of(mutable("id", "y", "blockType", "fieldset"))));
        assertEquals("form-section", ((List<Map<String, Object>>) formPage.get("blocks")).get(0).get("blockType"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizePageToV4_leavesValidBlockType_untouched() {
        // A registered blockType (table, description, ...) must not be rewritten.
        Map<String, Object> page = NlModelingService.normalizePageToV4(mutable(
                "kind", "detail", "blocks", List.of(
                        mutable("id", "d", "blockType", "description"),
                        mutable("id", "t", "blockType", "activity-timeline"))));
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) page.get("blocks");
        assertEquals("description", blocks.get(0).get("blockType"));
        assertEquals("activity-timeline", blocks.get(1).get("blockType"),
                "a valid blockType must be left untouched");
    }

    @Test
    void buildManifest_preservesExplicitPermissions_andDoesNotDuplicate() throws Exception {
        // A custom permission the model emitted is preserved; a dynamic permission already present
        // is not duplicated by the synthesizer.
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .models(List.of(mutable("code", "book")))
                .permissions(List.of(
                        mutable("code", "book.export", "name:zh-CN", "导出图书"),
                        mutable("code", "dynamic.book.read")))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("x", res));

        List<String> codes = new ArrayList<>();
        m.get("permissions").forEach(p -> codes.add(p.get("code").asText()));
        assertTrue(codes.contains("book.export"), "an explicit custom permission must be preserved");
        assertEquals(1, codes.stream().filter("dynamic.book.read"::equals).count(),
                "an already-present dynamic permission must not be duplicated");
        assertTrue(codes.containsAll(Set.of(
                        "dynamic.book.create", "dynamic.book.update", "dynamic.book.delete")),
                "missing dynamic CRUD permissions are still synthesized alongside the existing one");
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
    void buildManifest_rewritesPageFieldLabelsToI18nKeys() throws Exception {
        // Live LLMs may emit V4 detail blocks with Chinese field labels. The strict import
        // gate rejects hardcoded non-ASCII text in page labels, so the manifest builder must
        // deterministically rewrite those labels to model field i18n keys before apply().
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .models(List.of(mutable("code", "device_inspection", "modelType", "entity")))
                .fields(List.of(
                        mutable("code", "device_no", "dataType", "string",
                                "displayName:zh-CN", "设备编号", "displayName:en", "Device No"),
                        mutable("code", "inspector", "dataType", "reference",
                                "displayName:zh-CN", "点检人", "displayName:en", "Inspector")))
                .pages(List.of(mutable(
                        "pageKey", "device_inspection_detail",
                        "kind", "detail",
                        "schemaVersion", 4,
                        "modelCode", "device_inspection",
                        "layout", mutable("type", "stack"),
                        "blocks", List.of(mutable(
                                "id", "basic",
                                "blockType", "description",
                                "fields", List.of(
                                        mutable("field", "device_no", "label", "设备编号"),
                                        mutable("field", "inspector", "label", "点检人")))))))
                .i18n(new ArrayList<>())
                .build();

        JsonNode manifest = mapper.readTree(service.buildPluginManifestJson("inspection", res));
        JsonNode fields = manifest.get("pages").get(0).get("blocks").get(0).get("fields");
        assertEquals("$i18n:model.device_inspection.device_no.label", fields.get(0).get("label").asText());
        assertEquals("$i18n:model.device_inspection.inspector.label", fields.get(1).get("label").asText());

        Set<String> i18nKeys = new HashSet<>();
        manifest.get("i18nResources").forEach(n -> i18nKeys.add(n.get("key").asText()));
        assertTrue(i18nKeys.contains("model.device_inspection.device_no.label"));
        assertTrue(i18nKeys.contains("model.device_inspection.inspector.label"));
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
        var existing = List.of(mutable("pageKey", "x_list_v4"));
        // existing V4-shaped pages are normalized in place but kept (not synthesized away).
        existing.get(0).put("kind", "list");
        existing.get(0).put("schemaVersion", 4);
        existing.get(0).put("layout", mutable("type", "stack"));
        existing.get(0).put("blocks", List.of(mutable("id", "b", "blockType", "table")));
        var out = NlModelingService.synthesizePages("x", List.of(mutable("code", "x")),
                List.of(), existing);
        assertEquals(1, out.size(), "existing pages must be kept, not replaced");
        assertEquals("x_list_v4", out.get(0).get("pageKey"));
        // multi-model still skips synthesis but normalizes any provided pages.
        assertTrue(NlModelingService.synthesizePages("x",
                List.of(mutable("code", "a"), mutable("code", "b")), List.of(), List.of()).isEmpty());
    }

    // =====================================================================
    // Scope 1 — V2→V4 page normalizer (the LLM-output safety net)
    // =====================================================================

    /**
     * Builds a representative V2-shaped page exactly as the legacy "Page Schema"
     * reference taught the LLM: capitalized {@code kind}, no {@code schemaVersion},
     * a single wrapper block whose {@code layout} is {@code areas}/{@code areasConfig}
     * (flex), and the real blocks nested under {@code areas.<region>.blocks[]}.
     */
    private static Map<String, Object> v2ListPage() {
        Map<String, Object> toolbarBlock = mutable("id", "toolbar", "blockType", "toolbar",
                "buttons", List.of(mutable("code", "create", "label", "$i18n:common.button.create",
                        "action", mutable("type", "navigate", "to", "book_form"))));
        Map<String, Object> tableBlock = mutable("blockType", "table",
                "columns", List.of(mutable("field", "title", "width", 200)));
        Map<String, Object> wrapper = mutable(
                "kind", "List",
                "version", "1.0.0",
                "id", "list.book",
                "modelCode", "book",
                "layout", mutable(
                        "areas", List.of("toolbar", "content"),
                        "areasConfig", mutable(
                                "toolbar", mutable("type", "flex", "direction", "row"),
                                "content", mutable("type", "flex", "direction", "column"))),
                "areas", mutable(
                        "toolbar", mutable("blocks", List.of(toolbarBlock)),
                        "content", mutable("blocks", List.of(tableBlock))));
        return mutable(
                "pageKey", "book_list",
                "name:zh-CN", "图书列表",
                "name:en", "Book List",
                "kind", "List",
                "modelCode", "book",
                "blocks", List.of(wrapper));
    }

    @Test
    void normalizePageToV4_hoistsAreasBlocks_setsVersionLayoutKind() {
        Map<String, Object> page = NlModelingService.normalizePageToV4(v2ListPage());

        // ① schemaVersion forced to 4
        assertEquals(4, page.get("schemaVersion"), "schemaVersion must be 4 (S-PAGE-VERSION)");
        // ② kind lower-cased to an importable value
        assertEquals("list", page.get("kind"), "kind must be lower-cased (S-PAGE-KIND)");
        // ③ layout.type mapped from flex (the V2 wrapper) to a valid v4 value
        assertTrue(page.get("layout") instanceof Map, "layout must be an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> layout = (Map<String, Object>) page.get("layout");
        assertTrue(Set.of("grid", "stack").contains(String.valueOf(layout.get("type"))),
                "layout.type must be grid|stack (S-PAGE-LAYOUT-TYPE), was " + layout.get("type"));
        // ④ nested areas blocks hoisted to a flat top-level blocks[]
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) page.get("blocks");
        assertEquals(2, blocks.size(), "nested areas.*.blocks must be hoisted to a flat blocks[]");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) blocks.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) blocks.get(1);
        assertEquals("toolbar", first.get("blockType"));
        assertEquals("table", second.get("blockType"));
        // ⑤ every hoisted block carries a stable id (S-PAGE-BLOCK-ID)
        assertEquals("toolbar", first.get("id"), "existing block id preserved");
        assertNotNull(second.get("id"), "block missing id must get a synthesized id");
        assertFalse(String.valueOf(second.get("id")).isBlank());
        // no nested wrapper leaks through
        assertFalse(first.containsKey("areas"), "wrapper areas must not survive");
    }

    @Test
    void normalizePageToV4_passesThroughAlreadyV4Page() {
        Map<String, Object> v4 = mutable(
                "pageKey", "book_list", "kind", "list", "schemaVersion", 4,
                "modelCode", "book", "layout", mutable("type", "stack"),
                "blocks", List.of(mutable("id", "t", "blockType", "table",
                        "columns", List.of(mutable("field", "title")))));
        Map<String, Object> out = NlModelingService.normalizePageToV4(v4);
        assertEquals(4, out.get("schemaVersion"));
        assertEquals("list", out.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> layout = (Map<String, Object>) out.get("layout");
        assertEquals("stack", layout.get("type"), "an already-valid stack layout is preserved");
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) out.get("blocks");
        assertEquals(1, blocks.size(), "a V4 flat page is left structurally intact");
    }

    @Test
    void normalizePageToV4_mapsUnknownLayoutTypeToStack() {
        Map<String, Object> page = mutable("pageKey", "x_list", "kind", "list",
                "layout", mutable("type", "flex"),
                "blocks", List.of(mutable("id", "b", "blockType", "table")));
        Map<String, Object> out = NlModelingService.normalizePageToV4(page);
        @SuppressWarnings("unchecked")
        Map<String, Object> layout = (Map<String, Object>) out.get("layout");
        assertEquals("stack", layout.get("type"), "flex layout.type must map to stack");
        assertEquals(4, out.get("schemaVersion"));
    }

    @Test
    void normalizePageToV4_defaultsMissingLayoutAndKindIsNullSafe() {
        // missing layout entirely → a stack layout is provided
        Map<String, Object> noLayout = mutable("pageKey", "x_list", "kind", "form",
                "blocks", List.of(mutable("id", "b", "blockType", "form-section")));
        Map<String, Object> out = NlModelingService.normalizePageToV4(noLayout);
        @SuppressWarnings("unchecked")
        Map<String, Object> layout = (Map<String, Object>) out.get("layout");
        assertEquals("stack", layout.get("type"), "a missing layout must default to a stack layout");
        // null input is tolerated
        assertNull(NlModelingService.normalizePageToV4(null));
    }

    @Test
    void buildManifest_normalizesLlmEmittedV2Pages() throws Exception {
        // The end-to-end safety net: even when the LLM emits a V2-shaped page,
        // the built manifest must carry a V4-conformant page.
        NlModelingResponse.Resources res = NlModelingResponse.Resources.builder()
                .models(List.of(mutable("code", "book", "modelType", "entity")))
                .fields(List.of(mutable("code", "title", "dataType", "string",
                        "displayName:en", "Title")))
                .pages(new java.util.ArrayList<>(List.of(v2ListPage())))
                .build();

        JsonNode m = mapper.readTree(service.buildPluginManifestJson("book_mgmt", res));

        JsonNode page = m.get("pages").get(0);
        assertEquals(4, page.get("schemaVersion").asInt(), "manifest page must be V4");
        assertEquals("list", page.get("kind").asText());
        assertEquals("stack", page.get("layout").get("type").asText());
        JsonNode blocks = page.get("blocks");
        assertTrue(blocks.size() >= 2, "areas blocks must be hoisted into a flat blocks[]");
        assertEquals("toolbar", blocks.get(0).get("blockType").asText());
        assertEquals("table", blocks.get(1).get("blockType").asText());
    }
}
