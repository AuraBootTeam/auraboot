package com.auraboot.framework.meta.dto;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MetaFieldBusinessLabelTest {
    @Test
    void usesAuthoredUiLabelBeforeLegacyExtensionAndCode() {
        MetaFieldDTO dto = MetaFieldDTO.builder().code("customer_name")
                .uiSchema(Map.of("label", " Customer name "))
                .extension(Map.of("displayName", "Legacy label")).build();
        assertEquals("Customer name", dto.getDisplayName());
    }

    @Test
    void retainsLegacyExtensionLabelWhenUiLabelIsBlank() {
        MetaFieldDTO dto = MetaFieldDTO.builder().code("customer_name")
                .uiSchema(Map.of("label", " "))
                .extension(Map.of("displayName", "Customer")).build();
        assertEquals("Customer", dto.getDisplayName());
        dto.setExtension(null);
        assertEquals("customer_name", dto.getDisplayName());
    }
}
