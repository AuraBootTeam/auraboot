package com.auraboot.framework.meta.synthesized;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract pins for the synthesized route-level command seed (V20261116090000 made
 * {@code model_code} NULL-able for exactly this row shape):
 * <ul>
 *   <li>the seeded definition carries {@code modelCode == NULL} — route-level commands have
 *       no single-model owner; authorization validates the per-call target model instead.
 *       A regression that "helpfully" fills a model here would misattribute every dynamic
 *       CRUD call in the command panel, the audit trail and model-scoped authorization;</li>
 *   <li>the seed is idempotent (existing current definition → no insert) and fails closed
 *       outside a tenant context.</li>
 * </ul>
 */
class DynamicWriteCommandSeedServiceTest {

    private final CommandDefinitionMapper mapper = mock(CommandDefinitionMapper.class);
    private final DynamicWriteCommandSeedService service = new DynamicWriteCommandSeedService(mapper);

    @BeforeEach
    void setUpTenant() {
        MetaContext.setCurrentTenantId(42L);
        MetaContext.setCurrentUserId(7L);
        MetaContext.setMemberId(9L);
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void seededRouteCommandHasNullModelCodeAndPipelineRoutingConfig() {
        when(mapper.findCurrentByCode("dynamic.create")).thenReturn(null);

        String code = service.ensureSeeded("dynamic.create");

        assertThat(code).isEqualTo("dynamic.create");
        ArgumentCaptor<CommandDefinition> captured = ArgumentCaptor.forClass(CommandDefinition.class);
        verify(mapper).insertIdempotent(captured.capture());
        CommandDefinition seeded = captured.getValue();
        assertThat(seeded.getModelCode()).as("route-level commands carry no single-model owner").isNull();
        assertThat(seeded.getTargetModels()).isEqualTo("[]");
        assertThat(seeded.getExtension()).as("INSERT passes the column explicitly — a null extension would violate the NOT NULL default").isNotNull();
        assertThat(seeded.getExecutionConfig())
                .contains("\"handler\":\"" + DynamicWriteCommandHandler.HANDLER_CODE + "\"")
                .contains("\"invocationAuthorization\":\"handler\"");
        assertThat(seeded.getStatus()).isEqualTo("published");
        assertThat(seeded.getIsCurrent()).isTrue();
    }

    @Test
    void existingCurrentDefinitionIsNotReSeeded() {
        CommandDefinition existing = new CommandDefinition();
        existing.setCode("dynamic.delete");
        when(mapper.findCurrentByCode("dynamic.delete")).thenReturn(existing);

        String code = service.ensureSeeded("dynamic.delete");

        assertThat(code).isEqualTo("dynamic.delete");
        verify(mapper, never()).insertIdempotent(any(CommandDefinition.class));
    }

    @Test
    void missingTenantContextFailsClosed() {
        MetaContext.clear();
        // Either fail-closed wording: an uninitialized MetaContext thread or an absent tenant id.
        assertThatThrownBy(() -> service.ensureSeeded("dynamic.update"))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).insertIdempotent(any(CommandDefinition.class));
    }
}
