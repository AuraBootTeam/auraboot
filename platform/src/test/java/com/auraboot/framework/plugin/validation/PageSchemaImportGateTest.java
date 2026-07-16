package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageSchemaImportGateTest {

    private final PageSchemaImportGate gate =
            new PageSchemaImportGate(new PageSchemaValidator(), new ObjectMapper());

    @Test
    void cleanV4ManifestPasses() {
        assertDoesNotThrow(() -> gate.enforce(manifestWith(validPage())));
    }

    @Test
    void nullPagesPasses() {
        assertDoesNotThrow(() -> gate.enforce(new PluginManifestExtended()));
    }

    @Test
    void pageWithoutSchemaVersionFailsImport() {
        PageSchemaDTO p = validPage();
        p.setSchemaVersion(null);
        PageSchemaImportException ex = assertThrows(PageSchemaImportException.class,
                () -> gate.enforce(manifestWith(p)));
        assertTrue(ex.getMessage().contains("S-PAGE-VERSION"),
                () -> "Expected message to mention S-PAGE-VERSION but was: " + ex.getMessage());
    }

    @Test
    void legacyVersionPageFailsImport() {
        PageSchemaDTO p = validPage();
        p.setSchemaVersion(2);
        assertThrows(PageSchemaImportException.class, () -> gate.enforce(manifestWith(p)));
    }

    @Test
    void dashboardPageFailsImport() {
        PageSchemaDTO p = validPage();
        p.setKind("dashboard");
        assertThrows(PageSchemaImportException.class, () -> gate.enforce(manifestWith(p)));
    }

    @Test
    void badLayoutTypeFailsImport() {
        PageSchemaDTO p = validPage();
        p.setLayout(Map.of("type", "flex"));
        assertThrows(PageSchemaImportException.class, () -> gate.enforce(manifestWith(p)));
    }

    @Test
    void unknownBlockTypeFailsImport() {
        PageSchemaDTO p = validPage();
        p.setBlocks(List.of(Map.of("id", "x", "blockType", "totally-made-up")));
        assertThrows(PageSchemaImportException.class, () -> gate.enforce(manifestWith(p)));
    }

    @Test
    void advisoryOnlyFindingDoesNotBlockImport() {
        // raw-code column label trips S-PAGE-LABEL + S-PAGE-FIELD-REF (advisory),
        // but the page is structurally v4-valid → import must NOT be blocked.
        PageSchemaDTO p = validPage();
        p.setBlocks(List.of(Map.of(
                "id", "tbl",
                "blockType", "table",
                "columns", List.of(Map.of("field", "made_up_field", "label", "made_up_field")))));
        assertDoesNotThrow(() -> gate.enforce(manifestWith(p)));
    }

    @Test
    void duplicateBlockIdFailsImport() {
        // DR-20260715-A-004: two blocks share an id. The ①+② validator has no
        // duplicate-id check, so before the block-structure mount this imported
        // cleanly and rendered a broken designer. It must now be blocked.
        PageSchemaDTO p = validPage();
        Map<String, Object> col = Map.of("field", "pe_order_no", "label", Map.of("zh-CN", "单号", "en", "No"));
        p.setBlocks(List.of(
                Map.of("id", "dup", "blockType", "table", "columns", List.of(col)),
                Map.of("id", "dup", "blockType", "table", "columns", List.of(col))));
        PageSchemaImportException ex = assertThrows(PageSchemaImportException.class,
                () -> gate.enforce(manifestWith(p)));
        assertTrue(ex.getMessage().contains("S-PAGE-BLOCK-STRUCTURE") && ex.getMessage().contains("duplicate"),
                () -> "Expected duplicate-block-id structure failure but was: " + ex.getMessage());
    }

    @Test
    void missingBlockTypeFailsImport() {
        // DR-20260715-A-004: a block with an id but no blockType. The ①+② validator
        // only flags a *present* unknown blockType (it skips a null one), so before
        // the mount this imported cleanly. It must now be blocked.
        PageSchemaDTO p = validPage();
        p.setBlocks(List.of(Map.of("id", "noType")));
        PageSchemaImportException ex = assertThrows(PageSchemaImportException.class,
                () -> gate.enforce(manifestWith(p)));
        assertTrue(ex.getMessage().contains("S-PAGE-BLOCK-STRUCTURE") && ex.getMessage().contains("missing blockType"),
                () -> "Expected missing-blockType structure failure but was: " + ex.getMessage());
    }

    // ===== helpers =====

    private PluginManifestExtended manifestWith(PageSchemaDTO... pages) {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setPages(List.of(pages));
        return m;
    }

    private PageSchemaDTO validPage() {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("pe_order_list");
        page.setKind("list");
        page.setSchemaVersion(4);
        page.setLayout(Map.of("type", "stack"));
        page.setBlocks(List.of(Map.of(
                "id", "tbl",
                "blockType", "table",
                "columns", List.of(Map.of("field", "pe_order_no", "label", Map.of("zh-CN", "单号", "en", "No"))))));
        return page;
    }
}
