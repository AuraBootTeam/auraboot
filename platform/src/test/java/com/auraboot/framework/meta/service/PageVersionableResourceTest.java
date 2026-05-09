package com.auraboot.framework.meta.service;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PageVersionableResource}.
 */
@ExtendWith(MockitoExtension.class)
class PageVersionableResourceTest {

    @Mock private PageSchemaMapper pageSchemaMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PageVersionableResource resource;

    @BeforeEach
    void setUp() {
        resource = new PageVersionableResource(pageSchemaMapper, objectMapper);
    }

    @Test
    void getResourceType_returns_page() {
        assertThat(resource.getResourceType()).isEqualTo("page");
    }

    @Test
    void createSnapshot_throws_when_page_not_found() {
        when(pageSchemaMapper.selectByPageKey("missing")).thenReturn(null);

        assertThatThrownBy(() -> resource.createSnapshot("missing"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void createSnapshot_serializes_basic_fields() {
        PageSchema page = new PageSchema();
        page.setPageKey("p1");
        page.setModelCode("crm_lead");
        page.setName("Lead Page");
        page.setTitle("Leads");
        page.setDescription("desc");
        page.setKind("list");
        page.setSchemaVersion(2);
        when(pageSchemaMapper.selectByPageKey("p1")).thenReturn(page);

        JsonNode snapshot = resource.createSnapshot("p1");

        assertThat(snapshot.get("pageKey").asText()).isEqualTo("p1");
        assertThat(snapshot.get("modelCode").asText()).isEqualTo("crm_lead");
        assertThat(snapshot.get("name").asText()).isEqualTo("Lead Page");
        assertThat(snapshot.get("title").asText()).isEqualTo("Leads");
        assertThat(snapshot.get("description").asText()).isEqualTo("desc");
        assertThat(snapshot.get("kind").asText()).isEqualTo("list");
        assertThat(snapshot.get("schemaVersion").asInt()).isEqualTo(2);
    }

    @Test
    void createSnapshot_parses_json_blocks_layout_metaInfo() {
        PageSchema page = new PageSchema();
        page.setPageKey("p2");
        page.setBlocks("[{\"id\":\"b1\"}]");
        page.setLayout("{\"cols\":12}");
        page.setMetaInfo("{\"author\":\"alice\"}");
        when(pageSchemaMapper.selectByPageKey("p2")).thenReturn(page);

        JsonNode snapshot = resource.createSnapshot("p2");

        assertThat(snapshot.get("blocks").isArray()).isTrue();
        assertThat(snapshot.get("blocks").get(0).get("id").asText()).isEqualTo("b1");
        assertThat(snapshot.get("layout").get("cols").asInt()).isEqualTo(12);
        assertThat(snapshot.get("metaInfo").get("author").asText()).isEqualTo("alice");
    }

    @Test
    void createSnapshot_falls_back_to_string_for_invalid_json() {
        PageSchema page = new PageSchema();
        page.setPageKey("p3");
        page.setBlocks("not-json");
        page.setLayout("@@invalid@@");
        page.setMetaInfo("???");
        when(pageSchemaMapper.selectByPageKey("p3")).thenReturn(page);

        JsonNode snapshot = resource.createSnapshot("p3");

        assertThat(snapshot.get("blocks").asText()).isEqualTo("not-json");
        assertThat(snapshot.get("layout").asText()).isEqualTo("@@invalid@@");
        assertThat(snapshot.get("metaInfo").asText()).isEqualTo("???");
    }

    @Test
    void applySnapshot_throws_when_page_not_found() {
        when(pageSchemaMapper.selectByPageKey("missing")).thenReturn(null);
        JsonNode snapshot = objectMapper.createObjectNode().put("title", "x");

        assertThatThrownBy(() -> resource.applySnapshot("missing", snapshot))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void applySnapshot_updates_only_present_fields() throws Exception {
        PageSchema page = new PageSchema();
        page.setPageKey("p4");
        page.setTitle("old-title");
        page.setKind("old-kind");
        when(pageSchemaMapper.selectByPageKey("p4")).thenReturn(page);

        JsonNode snapshot = objectMapper.readTree(
                "{\"title\":\"new-title\",\"description\":\"d\",\"kind\":\"detail\","
              + "\"blocks\":[{\"id\":\"x\"}],\"layout\":{\"cols\":24},\"metaInfo\":{\"k\":\"v\"}}");

        resource.applySnapshot("p4", snapshot);

        ArgumentCaptor<PageSchema> captor = ArgumentCaptor.forClass(PageSchema.class);
        verify(pageSchemaMapper).updateById(captor.capture());
        PageSchema saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("new-title");
        assertThat(saved.getDescription()).isEqualTo("d");
        assertThat(saved.getKind()).isEqualTo("detail");
        assertThat(saved.getBlocks()).contains("\"id\":\"x\"");
        assertThat(saved.getLayout()).contains("\"cols\":24");
        assertThat(saved.getMetaInfo()).contains("\"k\":\"v\"");
    }

    @Test
    void applySnapshot_leaves_unspecified_fields_untouched() throws Exception {
        PageSchema page = new PageSchema();
        page.setPageKey("p5");
        page.setTitle("keep-title");
        page.setKind("keep-kind");
        page.setBlocks("[]");
        when(pageSchemaMapper.selectByPageKey("p5")).thenReturn(page);

        JsonNode snapshot = objectMapper.readTree("{}");

        resource.applySnapshot("p5", snapshot);

        ArgumentCaptor<PageSchema> captor = ArgumentCaptor.forClass(PageSchema.class);
        verify(pageSchemaMapper).updateById(captor.capture());
        PageSchema saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("keep-title");
        assertThat(saved.getKind()).isEqualTo("keep-kind");
        assertThat(saved.getBlocks()).isEqualTo("[]");
    }
}
