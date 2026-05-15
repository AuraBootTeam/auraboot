package com.auraboot.framework.meta.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageSchemaConverterMobileUxTest {

    @Test
    void exposesExtensionMobileUxAsTopLevelDtoField() {
        PageSchemaConverter converter = Mappers.getMapper(PageSchemaConverter.class);
        ReflectionTestUtils.setField(converter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(converter, "extensionConverter", new ExtensionConverter());
        ReflectionTestUtils.setField(converter, "utcDateTimeMapper", new UtcDateTimeMapper());

        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of(
                "mobileUx", Map.of(
                        "list", Map.of(
                                "views", java.util.List.of(Map.of("id", "all")),
                                "defaultSort", java.util.List.of(Map.of("field", "sc_created_at", "direction", "ASC"))
                        )
                )
        ));

        PageSchema entity = new PageSchema();
        entity.setPageKey("showcase_all_fields_list");
        entity.setKind("list");
        entity.setLayout("{}");
        entity.setBlocks("[]");
        entity.setExtension(extension);

        com.auraboot.framework.meta.dto.PageSchemaDTO dto = converter.toDTO(entity);

        assertThat(dto.getExtension()).containsKey("mobileUx");
        assertThat(dto.getMobileUx()).isNotNull();
        assertThat(dto.getMobileUx()).containsKey("list");
    }
}
