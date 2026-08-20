package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringPageSnapshotFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthoringPageSnapshotFactory factory =
            new AuthoringPageSnapshotFactory(objectMapper);

    @Test
    void checksumIsStableAcrossJsonObjectKeyOrder() throws Exception {
        var first = objectMapper.readTree("""
                {"pageKey":"orders","blocks":[{"id":"table","props":{"b":2,"a":1}}]}
                """);
        var reordered = objectMapper.readTree("""
                {"blocks":[{"props":{"a":1,"b":2},"id":"table"}],"pageKey":"orders"}
                """);

        assertThat(factory.checksum(first)).isEqualTo(factory.checksum(reordered));
    }

    @Test
    void normalizesFlatV4PageIntoServerOwnedRecursiveKindRoot() {
        PageSchema page = page("orders", "list", "order", """
                [{"id":"orders-table","blockType":"table","props":{"density":"normal"}}]
                """);

        var snapshot = factory.create(page);

        assertThat(snapshot.at("/blocks/0/id").asText()).isEqualTo("list_orders");
        assertThat(snapshot.at("/blocks/0/blockType").asText()).isEqualTo("list");
        assertThat(snapshot.at("/blocks/0/dataSource/model").asText()).isEqualTo("order");
        assertThat(snapshot.at("/blocks/0/blocks/0/id").asText()).isEqualTo("orders-table");
    }

    @Test
    void preservesExistingRecursiveRootWithoutDoubleWrapping() {
        PageSchema page = page("orders", "list", "order", """
                [{"id":"list-root","blockType":"list","blocks":[]}]
                """);

        var snapshot = factory.create(page);

        assertThat(snapshot.path("blocks")).hasSize(1);
        assertThat(snapshot.at("/blocks/0/id").asText()).isEqualTo("list-root");
        assertThat(snapshot.at("/blocks/0/blocks")).isEmpty();
    }

    @Test
    void emptyConcretePageStillReceivesItsNonDeletableRoot() {
        PageSchema page = page("quality_issue", "form", "quality_issue", "[]");

        var snapshot = factory.create(page);

        assertThat(snapshot.at("/blocks/0/id").asText()).isEqualTo("form_quality_issue");
        assertThat(snapshot.at("/blocks/0/blockType").asText()).isEqualTo("form");
        assertThat(snapshot.at("/blocks/0/blocks")).isEmpty();
    }

    private PageSchema page(String pageKey, String kind, String modelCode, String blocks) {
        PageSchema page = new PageSchema();
        page.setPid("01TESTPAGE00000000000000000");
        page.setPageKey(pageKey);
        page.setKind(kind);
        page.setModelCode(modelCode);
        page.setSchemaVersion(4);
        page.setTitle("{\"zh-CN\":\"Orders\"}");
        page.setLayout("{\"type\":\"stack\"}");
        page.setBlocks(blocks);
        return page;
    }
}
