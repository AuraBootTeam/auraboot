package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageSchemaDTOMobileUxTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodesTopLevelMobileUxProfile() throws Exception {
        String json = """
                {
                  "pageKey": "showcase_all_fields_list",
                  "name": "Showcase all fields",
                  "kind": "list",
                  "layout": {"type": "list"},
                  "blocks": [{"type": "table"}],
                  "mobileUx": {
                    "list": {
                      "views": [
                        {
                          "id": "high_priority",
                          "label": {"en": "High priority"},
                          "filter": {"field": "sc_priority", "op": "EQ", "value": "high"}
                        }
                      ],
                      "defaultSort": [
                        {"field": "sc_created_at", "direction": "ASC"}
                      ]
                    }
                  }
                }
                """;

        PageSchemaDTO dto = objectMapper.readValue(json, PageSchemaDTO.class);

        assertThat(dto.getMobileUx()).isNotNull();
        Map<String, Object> list = objectMapper.convertValue(dto.getMobileUx().get("list"), Map.class);
        assertThat(list).containsKeys("views", "defaultSort");
        List<Map<String, Object>> views = objectMapper.convertValue(list.get("views"), List.class);
        assertThat(views).hasSize(1);
        assertThat(views.get(0)).containsEntry("id", "high_priority");
        assertThat(views.get(0).get("filter")).isInstanceOf(Map.class);
        assertThat(dto.getUnknownFields()).isNull();
    }
}
