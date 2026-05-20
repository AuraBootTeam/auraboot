package com.auraboot.framework.meta.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageSchemaConverterMobileUxTest {

    @Test
    void preservesExplicitSchemaVersionFromCreateAndUpdateRequests() {
        PageSchemaConverter converter = buildConverter();

        PageSchemaCreateRequest createRequest = new PageSchemaCreateRequest();
        createRequest.setPageKey("designer_v3_list");
        createRequest.setName("Designer V3 List");
        createRequest.setTitle("Designer V3 List");
        createRequest.setKind("list");
        createRequest.setBlocks(List.of());
        createRequest.setSchemaVersion(3);

        PageSchema created = converter.toEntity(createRequest);

        assertThat(created.getSchemaVersion()).isEqualTo(3);

        PageSchema existing = new PageSchema();
        existing.setSchemaVersion(2);

        PageSchemaUpdateRequest updateRequest = new PageSchemaUpdateRequest();
        updateRequest.setSchemaVersion(3);
        converter.updateEntity(existing, updateRequest);

        assertThat(existing.getSchemaVersion()).isEqualTo(3);

        PageSchemaUpdateRequest partialUpdate = new PageSchemaUpdateRequest();
        partialUpdate.setDescription("touch without schema version");
        converter.updateEntity(existing, partialUpdate);

        assertThat(existing.getSchemaVersion()).isEqualTo(3);
    }

    @Test
    void exposesExtensionMobileUxAsTopLevelDtoField() {
        PageSchemaConverter converter = buildConverter();

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

    private PageSchemaConverter buildConverter() {
        PageSchemaConverter converter = Mappers.getMapper(PageSchemaConverter.class);
        ReflectionTestUtils.setField(converter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(converter, "extensionConverter", new ExtensionConverter());
        ReflectionTestUtils.setField(converter, "utcDateTimeMapper", new UtcDateTimeMapper());
        return converter;
    }
}
