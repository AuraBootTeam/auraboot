package com.auraboot.framework.promotion.diff;

import com.auraboot.framework.meta.entity.PageSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the JSON tree diff. No Spring, no DB.
 */
class PageSchemaDiffServiceTest {

    private final PageSchemaDiffService service = new PageSchemaDiffService();

    @Test
    void identicalContent_producesNoEntries() {
        PageSchema a = page("[{\"id\":\"b1\",\"title\":\"x\"}]", "{\"type\":\"stack\"}", "{\"zh-CN\":\"页面\"}");
        PageSchema b = page("[{\"id\":\"b1\",\"title\":\"x\"}]", "{\"type\":\"stack\"}", "{\"zh-CN\":\"页面\"}");

        List<SemanticDiffEntry> diff = service.diff(a, b);
        assertThat(diff).isEmpty();
    }

    @Test
    void scalarChange_producesModify() {
        PageSchema a = page(null, "{\"type\":\"stack\"}", null);
        PageSchema b = page(null, "{\"type\":\"grid\",\"cols\":12}", null);

        List<SemanticDiffEntry> diff = service.diff(a, b);
        assertThat(diff).extracting(SemanticDiffEntry::getPath, SemanticDiffEntry::getOp)
                .containsExactlyInAnyOrder(
                        tuple("layout.type", SemanticDiffEntry.Op.MODIFY),
                        tuple("layout.cols", SemanticDiffEntry.Op.ADD)
                );
    }

    @Test
    void addedArrayElement_producesAdd() {
        PageSchema a = page("[{\"id\":\"b1\"}]", null, null);
        PageSchema b = page("[{\"id\":\"b1\"},{\"id\":\"b2\",\"new\":true}]", null, null);

        List<SemanticDiffEntry> diff = service.diff(a, b);
        assertThat(diff).hasSize(1);
        SemanticDiffEntry entry = diff.get(0);
        assertThat(entry.getPath()).isEqualTo("blocks[1]");
        assertThat(entry.getOp()).isEqualTo(SemanticDiffEntry.Op.ADD);
        assertThat(entry.getOldValue()).isNull();
        assertThat(entry.getNewValue()).isNotNull();
    }

    @Test
    void removedArrayElement_producesDelete() {
        PageSchema a = page("[{\"id\":\"b1\"},{\"id\":\"b2\"}]", null, null);
        PageSchema b = page("[{\"id\":\"b1\"}]", null, null);

        List<SemanticDiffEntry> diff = service.diff(a, b);
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).getPath()).isEqualTo("blocks[1]");
        assertThat(diff.get(0).getOp()).isEqualTo(SemanticDiffEntry.Op.DELETE);
    }

    @Test
    void deeplyNestedScalarChange_capturesPath() {
        PageSchema a = page("[{\"id\":\"b1\",\"buttons\":[{\"label\":\"old\"}]}]", null, null);
        PageSchema b = page("[{\"id\":\"b1\",\"buttons\":[{\"label\":\"new\"}]}]", null, null);

        List<SemanticDiffEntry> diff = service.diff(a, b);
        assertThat(diff).hasSize(1);
        SemanticDiffEntry entry = diff.get(0);
        assertThat(entry.getPath()).isEqualTo("blocks[0].buttons[0].label");
        assertThat(entry.getOp()).isEqualTo(SemanticDiffEntry.Op.MODIFY);
        assertThat(entry.getOldValue()).isEqualTo("old");
        assertThat(entry.getNewValue()).isEqualTo("new");
    }

    @Test
    void typeChange_producesModifyAtParent() {
        // String → Object at same path → recorded as MODIFY at parent (don't drill into mismatched types)
        PageSchema a = page("[{\"id\":\"b1\",\"label\":\"text\"}]", null, null);
        PageSchema b = page("[{\"id\":\"b1\",\"label\":{\"zh\":\"文字\",\"en\":\"text\"}}]", null, null);

        List<SemanticDiffEntry> diff = service.diff(a, b);
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).getPath()).isEqualTo("blocks[0].label");
        assertThat(diff.get(0).getOp()).isEqualTo(SemanticDiffEntry.Op.MODIFY);
    }

    @Test
    void nullSourceAgainstPopulatedTarget_producesAddAtRoot() {
        PageSchema a = page(null, null, null);
        PageSchema b = page("[{\"id\":\"b1\"}]", null, null);

        List<SemanticDiffEntry> diff = service.diff(a, b);
        // The whole blocks field becomes one ADD at root
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).getPath()).isEqualTo("blocks");
        assertThat(diff.get(0).getOp()).isEqualTo(SemanticDiffEntry.Op.ADD);
    }

    @Test
    void localizedTitleChange_recordsPerLocale() {
        PageSchema a = page(null, null, "{\"zh-CN\":\"列表\",\"en\":\"List\"}");
        PageSchema b = page(null, null, "{\"zh-CN\":\"列表\",\"en\":\"All Records\"}");

        List<SemanticDiffEntry> diff = service.diff(a, b);
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).getPath()).isEqualTo("title.en");
        assertThat(diff.get(0).getOp()).isEqualTo(SemanticDiffEntry.Op.MODIFY);
    }

    @Test
    void nullSourceAndNullTarget_isClean() {
        List<SemanticDiffEntry> diff = service.diff(null, null);
        assertThat(diff).isEmpty();
    }

    private PageSchema page(String blocks, String layout, String title) {
        PageSchema p = new PageSchema();
        p.setBlocks(blocks);
        p.setLayout(layout);
        p.setTitle(title);
        return p;
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
