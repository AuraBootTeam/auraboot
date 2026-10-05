package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.capability.mapper.CapabilityMapper;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityDescriptionLocalizationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CapabilityResolver resolver = new CapabilityResolver();

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"org-management", "platform-admin"})
    void shippedDescriptionsSurviveRegistryMappingAndApiSerialization(String plugin) throws Exception {
        List<CapabilityDefinitionDTO> declarations = json.readValue(
                Path.of("../plugins", plugin, "config/capabilities.json").toFile(), new TypeReference<>() {});
        assertThat(declarations).hasSize(plugin.equals("org-management") ? 9 : 11);
        MetaContext.setContext(20L, 1L, "tester", "tester");
        for (CapabilityDefinitionDTO declaration : declarations) {
            assertThat(declaration.getDescription()).isNotBlank();
            assertThat(declaration.getDescriptionEn()).isNotBlank();
            assertThat(declaration.getDescriptionEn()).doesNotContainPattern("[一-鿿]");
            CapabilityMapper mapper = mock(CapabilityMapper.class);
            CapabilityRegistryServiceImpl registry = new CapabilityRegistryServiceImpl(mapper);
            registry.saveDefinition(declaration);
            ArgumentCaptor<CapabilityRecord> written = ArgumentCaptor.forClass(CapabilityRecord.class);
            verify(mapper).insert(written.capture());
            CapabilityRecord record = written.getValue();
            assertThat(record.getTenantId()).isEqualTo(20L);
            assertThat(record.getDescription()).isEqualTo(declaration.getDescription());
            assertThat(record.getDescriptionEn()).isEqualTo(declaration.getDescriptionEn());
            when(mapper.findByTenant(20L)).thenReturn(List.of(record));
            CapabilityDefinitionDTO reloaded = registry.listDeclarations(20L).get(0);
            assertThat(reloaded).usingRecursiveComparison().ignoringFields("sensitive", "unmasksFields")
                    .isEqualTo(declaration);
            assertThat(reloaded.getSensitive()).isEqualTo(Boolean.TRUE.equals(declaration.getSensitive()));
            assertThat(reloaded.getUnmasksFields()).isEqualTo(
                    declaration.getUnmasksFields() == null ? List.of() : declaration.getUnmasksFields());
            Capability resolved = resolver.resolve(List.of(reloaded), declaration.getIncludes(), Set.of())
                    .get(0).getCapabilities().get(0);
            assertThat(resolved.getCode()).isEqualTo(declaration.getCode());
            assertThat(resolved.getIncludes()).isEqualTo(declaration.getIncludes());
            assertThat(resolved.isGranted()).isFalse();
            var payload = json.valueToTree(resolved);
            assertThat(payload.path("localizedDescriptions").path("en").asText())
                    .isEqualTo(declaration.getDescriptionEn());
            assertThat(payload.path("localizedDescriptions").path("zh-CN").asText())
                    .isEqualTo(declaration.getDescription());
            assertThat(payload.path("description").asText()).isEqualTo(declaration.getDescription());
        }
    }

    @Test
    void overwritePreservesRecordIdentityAndReplacesTheEnglishDescription() {
        CapabilityMapper mapper = mock(CapabilityMapper.class);
        CapabilityRegistryServiceImpl registry = new CapabilityRegistryServiceImpl(mapper);
        CapabilityRecord existing = new CapabilityRecord();
        existing.setId(42L);
        existing.setDescriptionEn("Previous description");
        when(mapper.findByTenantAndCode(20L, "org.cap.role")).thenReturn(existing);
        MetaContext.setContext(20L, 1L, "tester", "tester");
        registry.saveDefinition(CapabilityDefinitionDTO.builder().code("org.cap.role")
                .includes(List.of("org.role.read")).description("角色管理")
                .descriptionEn("Manage roles").build());
        ArgumentCaptor<CapabilityRecord> written = ArgumentCaptor.forClass(CapabilityRecord.class);
        verify(mapper).updateById(written.capture());
        assertThat(written.getValue().getId()).isEqualTo(42L);
        assertThat(written.getValue().getDescriptionEn()).isEqualTo("Manage roles");
    }

    @Test
    void legacyAndEmptyDescriptionsDoNotInventLocalizedText() {
        CapabilityDefinitionDTO legacy = CapabilityDefinitionDTO.builder().code("legacy.cap.read")
                .group("Legacy").includes(List.of("legacy.resource.read"))
                .description("Legacy source description").descriptionEn("  ").build();
        Capability result = resolver.resolve(List.of(legacy), List.of("legacy.resource.read", "x.resource.read"), Set.of())
                .get(0).getCapabilities().get(0);
        assertThat(result.getDescription()).isEqualTo("Legacy source description");
        assertThat(result.getLocalizedDescriptions()).isEmpty();
        Capability derived = resolver.resolve(List.of(), List.of("x.resource.read"), Set.of())
                .get(0).getCapabilities().get(0);
        assertThat(derived.getDescription()).isNull();
        assertThat(derived.getLocalizedDescriptions()).isEmpty();
    }

    @Test
    void englishOnlyDescriptionsRemainAvailableWithoutInventingChinese() {
        CapabilityDefinitionDTO declaration = CapabilityDefinitionDTO.builder().code("org.cap.role")
                .group("Organization").includes(List.of("org.role.read"))
                .descriptionEn("Manage roles").build();
        Capability result = resolver.resolve(List.of(declaration), declaration.getIncludes(), Set.of("org.role.read"))
                .get(0).getCapabilities().get(0);
        assertThat(result.getLocalizedDescriptions()).isEqualTo(Map.of("en", "Manage roles"));
        assertThat(result.isGranted()).isTrue();
    }
}
