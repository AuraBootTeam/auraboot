package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardDefinitionDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesLocalizedTitleObjectToImportTitleString() throws Exception {
        DashboardDefinitionDTO dto = objectMapper.readValue("""
                {
                  "code": "localized_dashboard",
                  "title": {
                    "zh-CN": "质量仪表盘",
                    "en": "Quality Dashboard"
                  },
                  "widgets": [
                    {
                      "id": "w1",
                      "type": "smart-number-card",
                      "config": {
                        "title": {
                          "zh-CN": "关键指标",
                          "en": "Key Metrics"
                        }
                      }
                    }
                  ]
                }
                """, DashboardDefinitionDTO.class);

        assertThat(dto.getTitle()).isEqualTo("质量仪表盘");
        assertThat(dto.isValid()).isTrue();
    }
}
