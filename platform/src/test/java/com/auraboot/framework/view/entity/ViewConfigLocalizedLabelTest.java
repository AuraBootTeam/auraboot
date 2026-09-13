package com.auraboot.framework.view.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * WMS-UX-02 regression: a saved view carrying localized ({zh-CN, en}) card-field label maps
 * must survive the jsonb round trip. A strict String label made Jackson reject the whole
 * ViewConfig, which silently dropped such views from the view picker.
 */
class ViewConfigLocalizedLabelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapLabelsSurviveConversionIntoViewConfig() throws Exception {
        String json = """
                {
                  "groupByField": "inv_in_status",
                  "groupByDictCode": "inv_warehouse_status",
                  "titleField": "inv_in_code",
                  "idField": "pid",
                  "cardFields": [
                    { "field": "inv_in_type",
                      "label": { "zh-CN": "入库类型", "en": "Inbound Type" },
                      "type": "tag" },
                    { "field": "inv_in_total_amount",
                      "label": "总金额",
                      "type": "number" }
                  ]
                }
                """;

        ViewConfig config = objectMapper.convertValue(objectMapper.readTree(json), ViewConfig.class);

        assertEquals(2, config.getCardFields().size());
        Map<?, ?> localized = assertInstanceOf(Map.class, config.getCardFields().get(0).getLabel());
        assertEquals("入库类型", localized.get("zh-CN"));
        assertEquals("总金额", config.getCardFields().get(1).getLabel());

        // and the reverse: the stored config serializes the map back verbatim
        ViewConfig roundTrip = objectMapper.convertValue(
                objectMapper.convertValue(config, Map.class), ViewConfig.class);
        localized = assertInstanceOf(Map.class, roundTrip.getCardFields().get(0).getLabel());
        assertEquals("Inbound Type", localized.get("en"));
    }
}
