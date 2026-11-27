package com.auraboot.framework.meta.synthesized;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract pins for the per-model synthesized command seed (the 2026-11-16
 * root fix for the dynamic-write 500: the seed previously built a definition
 * with a NULL model_code and an absent extension, and the INSERT died on the
 * NOT NULL constraints before authorization ever ran):
 * <ul>
 *   <li>the command code is derived per model ({@code dynamic.create.<model>}) so
 *       every model owns an accurate, non-borrowed identity;</li>
 *   <li>modelCode is required and stamped onto the definition (a route with a
 *       borrowed model would misattribute panel, audit and model-scoped checks);</li>
 *   <li>extension is explicit — INSERT passes the column, so a null would
 *       bypass the NOT NULL default exactly like the model_code did;</li>
 *   <li>re-seeding an existing current definition re-validates it against the
 *       expected pipeline config instead of writing a second row;</li>
 *   <li>unsupported operations, missing models and an absent tenant context all
 *       fail closed before any INSERT.</li>
 * </ul>
 */
class DynamicWriteCommandSeedServiceTest {

    private final CommandDefinitionMapper mapper = mock(CommandDefinitionMapper.class);
    private final DynamicWriteCommandSeedService service =
            new DynamicWriteCommandSeedService(mapper, new com.fasterxml.jackson.databind.ObjectMapper());

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
    void perModelCodeIsDerivedStampedAndExtensionIsExplicit() {
        when(mapper.findCurrentByCode("dynamic.create.orb_worker")).thenReturn(null);
        when(mapper.insertIdempotent(any(CommandDefinition.class))).thenReturn(1);

        String code = service.ensureSeeded("dynamic.create", "orb_worker");

        assertThat(code).isEqualTo("dynamic.create.orb_worker");
        ArgumentCaptor<CommandDefinition> captured = ArgumentCaptor.forClass(CommandDefinition.class);
        verify(mapper).insertIdempotent(captured.capture());
        CommandDefinition seeded = captured.getValue();
        assertThat(seeded.getModelCode()).isEqualTo("orb_worker");
        assertThat(seeded.getExtension()).as("INSERT passes the column explicitly — a null would violate the NOT NULL default").isNotNull();
        assertThat(seeded.getTargetModels()).contains("orb_worker");
        assertThat(seeded.getExecutionConfig()).contains("\"invocationAuthorization\":\"handler\"");
        assertThat(seeded.getStatus()).isEqualTo("published");
    }

    @Test
    void existingCurrentDefinitionIsReValidatedNotRewritten() {
        CommandDefinition existing = new CommandDefinition();
        existing.setCode("dynamic.delete.orb_worker");
        existing.setModelCode("orb_worker");
        existing.setStatus("published");
        existing.setExecutionConfig("{\"handler\":\"dynamic.delete\",\"type\":\"delete\","
                + "\"handlerParams\":{\"invocationAuthorization\":\"handler\",\"transactionIsolation\":\"serializable\"}}");
        when(mapper.findCurrentByCode("dynamic.delete.orb_worker")).thenReturn(existing);

        String code = service.ensureSeeded("dynamic.delete", "orb_worker");

        assertThat(code).isEqualTo("dynamic.delete.orb_worker");
        verify(mapper, never()).insertIdempotent(any(CommandDefinition.class));
    }

    @Test
    void unsupportedOperationOrMissingModelFailsBeforeAnyInsert() {
        assertThatThrownBy(() -> service.ensureSeeded("dynamic.list", "orb_worker"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ensureSeeded("dynamic.create", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertIdempotent(any(CommandDefinition.class));
    }

    @Test
    void missingTenantContextFailsClosed() {
        MetaContext.clear();
        assertThatThrownBy(() -> service.ensureSeeded("dynamic.create", "orb_worker"))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).insertIdempotent(any(CommandDefinition.class));
    }

    private static CommandDefinitionMapper mapper() {
        return mock(CommandDefinitionMapper.class);
    }
}
