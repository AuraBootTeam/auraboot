package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDescriptionLocalization;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.meta.service.impl.CommandServiceImpl;
import com.auraboot.framework.common.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandDescriptionLocalizationTest {
    private final CommandDefinitionMapper mapper = mock(CommandDefinitionMapper.class);
    private final CommandServiceImpl service = new CommandServiceImpl(mapper, mock(BindingRuleMapper.class),
            mock(CommandMetadataCacheService.class), mock(com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.class));
    private static final String JSON = "{\"localizedDescriptions\":{\"en-US\":\"Create a member\",\"zh-CN\":\"Create member source\"},\"localizedDisplayNames\":{\"en-US\":\"Create member\",\"zh-CN\":\"Source name\"},\"backendOnly\":true}";
    @BeforeEach void context() { MetaContext.setCurrentTenantId(42L); }
    @AfterEach void cleanup() { MetaContext.clear(); }
    private CommandDefinitionCreateRequest request() {
        var request = new CommandDefinitionCreateRequest(); request.setCode("member:create");
        request.setModelCode("tenant_member"); request.setDescription("Legacy description"); return request;
    }
    private CommandDefinition existing() {
        var entity = new CommandDefinition(); entity.setPid("command-pid"); entity.setStatus("draft");
        entity.setDescription("Legacy description"); entity.setExtension(JsonUtil.parse(JSON, ExtensionBean.class)); return entity;
    }
    @Test void createPersistsDeclaredTranslationsWithoutReturningOtherExtensionData() {
        var request = request(); request.setExtension(JSON); var dto = service.create(request);
        var capture = ArgumentCaptor.forClass(CommandDefinition.class); verify(mapper).insertIdempotent(capture.capture());
        assertThat(capture.getValue().getExtension().get("backendOnly")).isEqualTo(true);
        assertThat(dto.getLocalizedDescriptions()).containsEntry("en-US", "Create a member").hasSize(2);
        assertThat(dto.getDescription()).isEqualTo("Legacy description");
        assertThat(dto.getLocalizedDisplayNames()).containsEntry("en-US", "Create member");
        assertThat(JsonUtil.toJson(dto)).doesNotContain("backendOnly", "\"extension\"");
    }
    @Test void updateWritesProvidedTranslationExtension() {
        var entity = existing(); when(mapper.findByPid("command-pid")).thenReturn(entity);
        var request = request(); request.setExtension("{\"localizedDescriptions\":{\"en-US\":\"Updated\"}}");
        assertThat(service.update("command-pid", request).getLocalizedDescriptions()).containsOnly(entry("en-US", "Updated"));
        verify(mapper).updateById(entity);
    }
    @Test void omittedUpdateExtensionPreservesStoredTranslations() {
        var entity = existing(); when(mapper.findByPid("command-pid")).thenReturn(entity);
        assertThat(service.update("command-pid", request()).getLocalizedDescriptions()).containsEntry("en-US", "Create a member");
        assertThat(entity.getExtension().get("backendOnly")).isEqualTo(true);
        assertThat(service.findByPid("command-pid").getLocalizedDisplayNames()).containsEntry("en-US", "Create member");
    }
    @Test void legacyCommandKeepsItsDescriptionWithNoLocalizedEntries() {
        var dto = service.create(request()); assertThat(dto.getDescription()).isEqualTo("Legacy description");
        assertThat(dto.getLocalizedDescriptions()).isEmpty();
    }
    @Test void readProjectsNestedAndFlatExtensionShapes() {
        for (String json : new String[]{JSON, "{\"extension\":" + JSON + "}"}) {
            var entity = existing(); entity.setExtension(JsonUtil.parse(json, ExtensionBean.class));
            when(mapper.findByPid("command-pid")).thenReturn(entity);
            assertThat(service.findByPid("command-pid").getLocalizedDescriptions()).containsEntry("en-US", "Create a member");
        }
    }
    @ParameterizedTest @ValueSource(strings={"null", "[]", "{broken", "{\"localizedDescriptions\":[]}", "{\"localizedDescriptions\":{\"en-US\":4}}", "{\"localizedDescriptions\":{\"\":\"value\"}}", "{\"localizedDisplayNames\":[]}", "{\"localizedDisplayNames\":{\"en-US\":4}}", "{\"localizedDisplayNames\":{\"\":\"value\"}}"})
    void invalidExtensionIsRejectedBeforeWriting(String json) {
        var request = request(); request.setExtension(json);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(RuntimeException.class);
        verify(mapper, never()).insertIdempotent(any());
    }
    @Test void projectionIsDetachedFromTheSourceMap() {
        var source = new java.util.HashMap<String, String>(); source.put("en-US", "Original");
        var result = CommandDescriptionLocalization.from(source); source.put("en-US", "Changed");
        assertThat(result).containsEntry("en-US", "Original");
        assertThatThrownBy(() -> result.put("en-US", "Changed")).isInstanceOf(UnsupportedOperationException.class);
    }
}
