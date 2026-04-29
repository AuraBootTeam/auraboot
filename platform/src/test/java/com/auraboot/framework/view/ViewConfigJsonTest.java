package com.auraboot.framework.view;

import com.auraboot.framework.view.entity.ViewConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ViewConfigJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolbarActionsRoundTripThroughJson() throws Exception {
        String json = """
                {
                  "toolbarActions": [
                    { "code": "create", "visible": true, "pinned": false, "order": 0 },
                    { "code": "_export_csv", "visible": false, "pinned": false, "order": 4 }
                  ]
                }
                """;

        ViewConfig config = objectMapper.readValue(json, ViewConfig.class);

        assertThat(config.getToolbarActions()).hasSize(2);
        assertThat(config.getToolbarActions().get(0).getCode()).isEqualTo("create");
        assertThat(config.getToolbarActions().get(0).getVisible()).isTrue();
        assertThat(config.getToolbarActions().get(0).getPinned()).isFalse();
        assertThat(config.getToolbarActions().get(0).getOrder()).isZero();
        assertThat(config.getToolbarActions().get(1).getCode()).isEqualTo("_export_csv");
        assertThat(config.getToolbarActions().get(1).getVisible()).isFalse();

        String serialized = objectMapper.writeValueAsString(config);
        ViewConfig readBack = objectMapper.readValue(serialized, ViewConfig.class);

        assertThat(readBack.getToolbarActions()).hasSize(2);
        assertThat(readBack.getToolbarActions().get(1).getPinned()).isFalse();
    }
}
