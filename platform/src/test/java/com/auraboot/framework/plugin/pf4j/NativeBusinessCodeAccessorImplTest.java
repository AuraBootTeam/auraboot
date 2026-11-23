package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.CommandAutoSetExecutor;
import com.auraboot.framework.plugin.extension.NativeBusinessCodeAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic capability boundaries, not native persistence or sequence concurrency qualification. */
class NativeBusinessCodeAccessorImplTest {
    final CommandService commands=mock(CommandService.class);
    final MetaModelService models=mock(MetaModelService.class);
    final CommandAutoSetExecutor generator=mock(CommandAutoSetExecutor.class);
    final ObjectMapper json=new ObjectMapper();
    final String root="registration:approve",source="supplier:create",field="supplier_code";
    final Map<String,Object> settings=new HashMap<>();
    final CommandDefinitionDTO command=new CommandDefinitionDTO();
    NativeBusinessCodeAccessorImplTest() {
        MetaContext.setContext(1L,10L,"buyer","Buyer");
        settings.put(NativeBusinessCodeAccessor.SCOPE_HANDLER_PARAM,List.of(Map.of("commandCode",source,"fieldCode",field)));
        command.setCode(source);command.setTenantId(1L);command.setStatus("published");command.setIsCurrent(true);command.setType("create");command.setModelCode("supplier");
        command.setExecutionConfig("{\"autoSetFields\":{\"supplier_code\":{\"strategy\":\"auto_generate\",\"pattern\":\"S-{yyyyMMdd}-{seq}\"}}}");
        when(commands.findByCode(source)).thenReturn(command);
        when(models.getFieldDefinition("supplier",field)).thenReturn(FieldDefinition.builder().code(field).dataType("string").build());
        when(generator.generateRequiredAutoCode(eq("supplier"),eq(field),anyMap())).thenReturn("S-20261119-001");
    }
    @AfterEach void clear(){MetaContext.clear();}
    <T> T authorized(String code,java.util.function.Supplier<T> body) {
        return MetaContext.runWithCommandPermitPlan("ALL",null,"membership","application",()->MetaContext.runWithAuthorizedCommandCode(code,body));
    }

    NativeBusinessCodeAccessor accessor() {
        NativeBusinessCodeAccessorImpl.install(settings,1L,"buyer",root,commands,models,generator,json);
        return (NativeBusinessCodeAccessor)settings.get(NativeBusinessCodeAccessor.SETTINGS_KEY);
    }
    String call(NativeBusinessCodeAccessor accessor){return authorized(root,()->accessor.generate(source,field));}
    @Test void usesCurrentPublishedNativeRuleWithoutDispatchingOrGrantingModelAccess() {
        assertEquals("S-20261119-001",call(accessor()));
        verify(generator).generateRequiredAutoCode("supplier",field,Map.of("strategy","auto_generate","pattern","S-{yyyyMMdd}-{seq}"));
        verify(commands,never()).create(any());verify(commands,never()).update(anyString(),any());
    }
    @Test void absentOptInRemovesSpoofedAccessorAndReadsNothing() {
        settings.clear();settings.put(NativeBusinessCodeAccessor.SETTINGS_KEY,"spoofed");
        NativeBusinessCodeAccessorImpl.install(settings,1L,"buyer",root,null,null,null,null);
        assertFalse(settings.containsKey(NativeBusinessCodeAccessor.SETTINGS_KEY));verifyNoInteractions(commands,models,generator);
    }
    @Test void malformedDuplicateOrWildcardScopesRejectBeforeReadingMetadata() {
        for(Object value:List.of(List.of(),List.of(Map.of("commandCode","*","fieldCode",field)),
                List.of(Map.of("commandCode",source,"fieldCode",field,"modelCode","supplier")),
                List.of(Map.of("commandCode",source,"fieldCode",field),Map.of("commandCode",source,"fieldCode",field)))) {
            settings.put(NativeBusinessCodeAccessor.SCOPE_HANDLER_PARAM,value);
            assertThrows(IllegalArgumentException.class,this::accessor);
        }
        verifyNoInteractions(commands,models,generator);
    }
    @Test void retainedAccessorCannotCrossTenantActorCommandOrNestedInvocation() {
        var accessor=accessor();assertThrows(SecurityException.class,()->accessor.generate(source,field));
        assertThrows(SecurityException.class,()->authorized("other:command",()->accessor.generate(source,field)));
        assertThrows(SecurityException.class,()->authorized(root,()->authorized(root,()->accessor.generate(source,field))));
        MetaContext.setContext(2L,10L,"buyer","Buyer");assertThrows(SecurityException.class,()->call(accessor));
        MetaContext.setContext(1L,11L,"other","Other");assertThrows(SecurityException.class,()->call(accessor));
        verifyNoInteractions(commands,models,generator);
    }
    @Test void callerCannotSelectAnUnlistedCommandOrField() {
        var accessor=accessor();
        for(String other:List.of("private:create",source)) assertThrows(SecurityException.class,
                ()->authorized(root,()->accessor.generate(other,"secret_field")));
        verifyNoInteractions(commands,models,generator);
    }
    @Test void missingForeignDraftOrNonCreateMetadataRejects() {
        var accessor=accessor();
        command.setTenantId(2L);assertThrows(IllegalStateException.class,()->call(accessor));command.setTenantId(1L);
        command.setStatus("draft");assertThrows(IllegalStateException.class,()->call(accessor));command.setStatus("published");
        command.setIsCurrent(false);assertThrows(IllegalStateException.class,()->call(accessor));command.setIsCurrent(true);
        command.setType("update");assertThrows(IllegalStateException.class,()->call(accessor));
        verifyNoInteractions(generator);
    }
    @Test void nonAutoRuleMissingFieldAndBlankGenerationNeverFallBack() {
        var accessor=accessor();
        command.setExecutionConfig("{\"autoSetFields\":{\"supplier_code\":{\"strategy\":\"uuid\"}}}");
        assertThrows(IllegalStateException.class,()->call(accessor));
        command.setExecutionConfig("{\"autoSetFields\":{\"supplier_code\":{\"strategy\":\"auto_generate\"}}}");
        when(models.getFieldDefinition("supplier",field)).thenReturn(null);assertThrows(IllegalStateException.class,()->call(accessor));
        when(models.getFieldDefinition("supplier",field)).thenReturn(FieldDefinition.builder().code(field).dataType("string").build());
        when(generator.generateRequiredAutoCode(anyString(),anyString(),anyMap())).thenReturn(" ");assertThrows(IllegalStateException.class,()->call(accessor));
        when(generator.generateRequiredAutoCode(anyString(),anyString(),anyMap())).thenThrow(new IllegalStateException("sequence unavailable"));
        assertEquals("sequence unavailable",assertThrows(IllegalStateException.class,()->call(accessor)).getMessage());
    }
}
