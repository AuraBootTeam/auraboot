package com.auraboot.framework.aurabot.skill.provider;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.*;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillConfirmationAuthorizationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AuraBotSkillRegistry registry = mock(AuraBotSkillRegistry.class);
    private final PreviewTokenStore tokens = mock(PreviewTokenStore.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final AuraBotSkill skill = mock(AuraBotSkill.class);
    private SkillToolExecutor executor;
    private SkillRequest input;

    @SuppressWarnings("unchecked") @BeforeEach void setup() throws Exception {
        MetaContext.setContext(7L, 9L, "user-9", "Test");
        ObjectProvider<PreviewTokenStore> tokenProvider = mock(ObjectProvider.class);
        when(tokenProvider.getIfAvailable()).thenReturn(tokens);
        var validator = new SkillRequestValidator(registry, mock(SkillRunRepository.class),
                mock(ObjectProvider.class), tokenProvider, json);
        executor = new SkillToolExecutor(registry, validator, tokenProvider, permissions, json);
        when(registry.get("model:create")).thenReturn(Optional.of(skill));
        when(skill.requiredPermissions()).thenReturn(Set.of("system.model.create"));
        when(skill.name()).thenReturn("model:create");
        when(skill.riskLevel()).thenReturn(RiskLevel.HIGH);
        var schema = mock(JsonSchema.class);
        when(schema.validate(any())).thenReturn(Set.of());
        when(registry.getCompiledSchema("model:create")).thenReturn(Optional.of(schema));
        when(permissions.getUserPermissionCodes(9L)).thenReturn(Set.of("system.model.create"));
        input = SkillRequest.builder().skillName("model:create").params(json.readTree("{\"code\":\"sample\"}")).build();
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void permissionRevocationIsCheckedBeforeTokenConsumptionOrExecution() {
        when(permissions.getUserPermissionCodes(9L)).thenReturn(Set.of());
        assertThrows(SkillSpiException.class, () -> executor.confirm("model:create", input, "token"));
        verifyNoInteractions(tokens); verify(skill, never()).execute(any());
    }
    @Test void foreignTenantPreviewCannotExecute() {
        when(tokens.consume(eq("token"), eq("model:create"), any()))
                .thenReturn(Optional.of(new PreviewTokenStore.PreviewPayload(8L, "model:create", json.nullNode())));
        assertThrows(SkillSpiException.class, () -> executor.confirm("model:create", input, "token"));
        verify(skill, never()).execute(any());
    }
    @Test void expiredOrAlreadyConsumedTokenCannotExecute() {
        when(tokens.consume(eq("token"), eq("model:create"), any())).thenReturn(Optional.empty());
        assertThrows(SkillSpiException.class, () -> executor.confirm("model:create", input, "token"));
        verify(skill, never()).execute(any());
    }
    @Test void validPreviewExecutesOnlyOnceAndKeepsOriginalInput() {
        when(tokens.consume(eq("token"), eq("model:create"), any()))
                .thenReturn(Optional.of(new PreviewTokenStore.PreviewPayload(7L, "model:create", json.nullNode())))
                .thenReturn(Optional.empty());
        executor.confirm("model:create", input, "token");
        assertThrows(SkillSpiException.class, () -> executor.confirm("model:create", input, "token"));
        verify(skill, times(1)).execute(argThat(request -> request.getParams().equals(input.getParams())));
    }
}
