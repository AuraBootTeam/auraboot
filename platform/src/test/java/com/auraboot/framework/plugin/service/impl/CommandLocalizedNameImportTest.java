package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandLocalizedNameImportTest {
    private final CommandService commands = mock(CommandService.class);
    private final CommandDefinitionMapper mapper = mock(CommandDefinitionMapper.class);
    private final CommandMetadataCacheService cache = mock(CommandMetadataCacheService.class);
    private final PluginResourceImporterImpl importer = mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);
    CommandLocalizedNameImportTest() {
        ReflectionTestUtils.setField(importer, "commandService", commands);
        ReflectionTestUtils.setField(importer, "commandDefinitionMapper", mapper);
        ReflectionTestUtils.setField(importer, "commandMetadataCache", cache);
        ReflectionTestUtils.setField(importer, "objectMapper", new ObjectMapper());
    }
    private CommandDefinitionDTO resource() {
        return JsonUtil.parse("{\"code\":\"member:create\",\"modelCode\":\"tenant_member\",\"displayName:zh-CN\":\"Source name\",\"displayName:en\":\"Create member\",\"extension\":{\"backendOnly\":true,\"localizedDescriptions\":{\"en-US\":\"Creates a member\"}}}", CommandDefinitionDTO.class);
    }
    private void run(CommandDefinitionDTO resource) {
        importer.importCommand(resource, "plugin-1", "import-1", 42L, ImportRequest.ConflictStrategy.OVERWRITE, false);
    }
    @Test void newImportRetainsDeclaredNamesAndOtherExtensionMetadata() {
        var created = new com.auraboot.framework.meta.dto.CommandDefinitionDTO(); created.setPid("command-1");
        when(commands.findByCode("member:create")).thenThrow(new com.auraboot.framework.meta.exception.CommandNotFoundException("member:create"));
        when(commands.create(any())).thenReturn(created);
        run(resource());
        var request = ArgumentCaptor.forClass(CommandDefinitionCreateRequest.class);
        verify(commands).create(request.capture());
        var json = JsonUtil.parse(request.getValue().getExtension(), Map.class);
        assertThat(json.get("localizedDisplayNames")).isEqualTo(Map.of("en-US", "Create member", "zh-CN", "Source name"));
        assertThat(json.get("localizedDescriptions")).isEqualTo(Map.of("en-US", "Creates a member"));
        assertThat(json.get("backendOnly")).isEqualTo(true);
        assertThat(request.getValue().getDisplayName()).isEqualTo("Source name");
        verifyNoInteractions(mapper);
    }
    @Test void overwriteImportUsesTheSameNameProjection() {
        var existing = new com.auraboot.framework.meta.dto.CommandDefinitionDTO(); existing.setPid("command-1");
        when(commands.findByCode("member:create")).thenReturn(existing);
        run(resource());
        var extension = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateForPluginImport(eq("Source name"), isNull(), eq("tenant_member"), eq("{}"), eq("[]"), anyString(), extension.capture(), anyString(), eq("plugin-1"), eq("command-1"), eq(42L));
        assertThat(JsonUtil.parse(extension.getValue(), Map.class).get("localizedDisplayNames")).isEqualTo(Map.of("en-US", "Create member", "zh-CN", "Source name"));
        verify(commands, never()).create(any()); verify(cache).evictAll();
    }
    @Test void declaredSuffixesOverrideMatchingLocalesWithoutMutatingSourceExtension() {
        var dto = resource(); var source = new HashMap<String, Object>();
        source.put("localizedDisplayNames", Map.of("en-US", "Older", "fr", "French")); dto.setExtension(source);
        var names = dto.getEffectiveExtension().get("localizedDisplayNames");
        assertThat(names).isEqualTo(Map.of("en-US", "Create member", "zh-CN", "Source name", "fr", "French"));
        assertThat(source.get("localizedDisplayNames")).isEqualTo(Map.of("en-US", "Older", "fr", "French"));
    }
    @Test void legacyScalarDoesNotInventTheLanguageOfTheName() {
        var dto = new CommandDefinitionDTO(); dto.setDisplayName("Legacy");
        assertThat(dto.getEffectiveExtension()).isEmpty(); assertThat(dto.getEffectiveDisplayName()).isEqualTo("Legacy");
    }
    @ParameterizedTest @ValueSource(strings={"{\"localizedDisplayNames\":[]}", "{\"localizedDescriptions\":{\"en-US\":9}}"})
    void invalidDisplayMetadataIsRejectedBeforeNewImportWrites(String json) {
        var dto = resource(); dto.setExtension(JsonUtil.parse(json, Map.class));
        assertThatThrownBy(() -> run(dto)).isInstanceOf(ValidationException.class);
        verify(commands, never()).create(any()); verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"{\"localizedDisplayNames\":[]}", "{\"localizedDescriptions\":{\"en-US\":9}}"})
    void invalidDisplayMetadataIsRejectedBeforeOverwriteWrites(String json) {
        var existing = new com.auraboot.framework.meta.dto.CommandDefinitionDTO(); existing.setPid("command-1");
        when(commands.findByCode("member:create")).thenReturn(existing);
        var dto = resource(); dto.setExtension(JsonUtil.parse(json, Map.class));
        assertThatThrownBy(() -> run(dto)).isInstanceOf(ValidationException.class);
        verify(commands, never()).create(any()); verifyNoInteractions(mapper, cache);
    }

    @ParameterizedTest @ValueSource(strings={"database", "unrelated-bad-param", "invalid-metadata"})
    void readFailurePropagatesUnchangedWithoutImportWrites(String failure) {
        RuntimeException error = switch (failure) {
            case "database" -> new IllegalStateException("Database unavailable");
            case "unrelated-bad-param" -> new com.auraboot.framework.exception.BusinessException(
                    com.auraboot.framework.common.constant.ResponseCode.BadParam, "Command not found: misleading text");
            default -> new ValidationException(com.auraboot.framework.common.constant.ResponseCode.CommonValidationFailed, "Invalid command metadata");
        };
        var created = new com.auraboot.framework.meta.dto.CommandDefinitionDTO(); created.setPid("unexpected-write");
        when(commands.create(any())).thenReturn(created);
        when(commands.findByCode("member:create")).thenThrow(error);
        assertThatThrownBy(() -> run(resource())).isSameAs(error);
        verify(commands, never()).create(any()); verifyNoInteractions(mapper, cache);
    }
    @Test void explicitAbsenceAndNullResultAreTheOnlyNegativeExistenceOutcomes() {
        when(commands.findByCode("absent")).thenThrow(new com.auraboot.framework.meta.exception.CommandNotFoundException("absent"));
        assertThat(importer.checkCommandExists(42L, "absent")).isFalse();
        assertThat(importer.checkCommandExists(42L, "null-result")).isFalse();
        var found = new com.auraboot.framework.meta.dto.CommandDefinitionDTO();
        when(commands.findByCode("found")).thenReturn(found);
        assertThat(importer.checkCommandExists(42L, "found")).isTrue();
        verify(commands, never()).create(any()); verifyNoInteractions(mapper, cache);
    }

}
