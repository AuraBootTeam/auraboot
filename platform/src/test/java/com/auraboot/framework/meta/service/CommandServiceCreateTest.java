package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.meta.service.impl.CommandServiceImpl;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic service checks; generated version allocation is covered by the real mapper IT. */
class CommandServiceCreateTest {
    private final CommandDefinitionMapper mapper=mock(CommandDefinitionMapper.class);
    private final CommandMetadataCacheService cache=mock(CommandMetadataCacheService.class);
    private final CommandServiceImpl service=new CommandServiceImpl(mapper,mock(BindingRuleMapper.class),cache,mock(ApplicationRuntimeDefinitionCatalog.class));
    @BeforeEach void context(){MetaContext.setContext(1L,7L,null,"system");}
    @AfterEach void clear(){MetaContext.clear();}
    private CommandDefinitionCreateRequest request(){var r=new CommandDefinitionCreateRequest();r.setCode("app:create");r.setDisplayName("Create");r.setModelCode("m");r.setExecutionConfig("{\"type\":\"create\"}");return r;}
    @Test void returnedIdentityUsesTheDatabaseAllocatedVersion(){
        when(mapper.insertIdempotent(any())).thenAnswer(call->{CommandDefinition row=call.getArgument(0);row.setId(10L);row.setVersion(2);return 1;});
        var result=service.create(request());assertThat(result.getId()).isEqualTo(10L);assertThat(result.getVersion()).isEqualTo(2);assertThat(result.getPid()).isNotBlank();verify(cache).evictCommandDefinitions();
    }
    @Test void zeroRowInsertNeverReturnsAGhostCommandOrEvictsCache(){
        when(mapper.insertIdempotent(any())).thenReturn(0);
        assertThatThrownBy(()->service.create(request())).hasMessageContaining("concurrently created");verifyNoInteractions(cache);
    }
    @Test void anExistingLiveCommandIsStillRejected(){
        when(mapper.findCurrentByCode("app:create")).thenReturn(new CommandDefinition());
        assertThatThrownBy(()->service.create(request())).hasMessageContaining("already exists");verify(mapper,never()).insertIdempotent(any());verifyNoInteractions(cache);
    }
}
