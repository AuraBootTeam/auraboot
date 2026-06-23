package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionDefinitionDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void capturesLocalizedNameAndDescriptionSuffixKeys() throws Exception {
        PermissionDefinitionDTO dto = objectMapper.readValue("""
                {
                  "code": "meta_management",
                  "name": "元数据管理",
                  "name:zh-CN": "元数据管理",
                  "name:en": "Metadata Management",
                  "description": "访问元数据管理模块",
                  "description:zh-CN": "访问元数据管理模块",
                  "description:en": "Access the metadata management module"
                }
                """, PermissionDefinitionDTO.class);

        // name:en is normalized to en-US, name:zh-CN preserved
        assertThat(dto.getAllLocalizedNames())
                .containsEntry("zh-CN", "元数据管理")
                .containsEntry("en-US", "Metadata Management");
        assertThat(dto.getAllLocalizedDescriptions())
                .containsEntry("zh-CN", "访问元数据管理模块")
                .containsEntry("en-US", "Access the metadata management module");
        assertThat(dto.isValid()).isTrue();
    }

    @Test
    void emptyLocalizedMapsWhenNoSuffixKeys() throws Exception {
        PermissionDefinitionDTO dto = objectMapper.readValue("""
                {
                  "code": "plain_perm",
                  "name": "纯名称",
                  "description": "纯描述"
                }
                """, PermissionDefinitionDTO.class);

        assertThat(dto.getAllLocalizedNames()).isEmpty();
        assertThat(dto.getAllLocalizedDescriptions()).isEmpty();
        // getEffectiveName falls back to raw name when no localized entries
        assertThat(dto.getEffectiveName()).isEqualTo("纯名称");
    }

    @Test
    void mergesLegacyNameZhCnEnFieldsIntoLocalizedNames() throws Exception {
        PermissionDefinitionDTO dto = objectMapper.readValue("""
                {
                  "code": "sla_config",
                  "name:zh-CN": "SLA 配置管理",
                  "name:en": "SLA Configuration Management"
                }
                """, PermissionDefinitionDTO.class);

        assertThat(dto.getAllLocalizedNames())
                .containsEntry("zh-CN", "SLA 配置管理")
                .containsEntry("en-US", "SLA Configuration Management");
    }
}
