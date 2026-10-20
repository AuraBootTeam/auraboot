package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetaModelServiceDatabaseReadTest {
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void explicitDatabaseReadBypassesAValidButOlderRuntimeReleaseSnapshot() {
        MetaContext.setContext(1L,7L,null,"system");
        var mapper=mock(MetaModelMapper.class);
        var cache=mock(MetaDefinitionCacheService.class);
        var release=mock(ApplicationRuntimeDefinitionCatalog.class);
        var service=new MetaModelServiceImpl(mapper,mock(MetaFieldMapper.class),mock(QueryBuilderService.class),
            mock(MetaModelFieldBindingMapper.class),mock(AutoPermissionAssignmentService.class),cache,
            mock(ApplicationEventPublisher.class),release);
        ReflectionTestUtils.setField(service,"defaultApplicationCode","app");
        ReflectionTestUtils.setField(service,"applicationRuntimePrimaryEnabled",true);
        var old=ModelDefinition.builder().code("shared_bid").build();
        when(release.findModel(1L,"app","shared_bid")).thenReturn(Optional.of(old));
        assertThat(service.getModelDefinition("shared_bid")).contains(old);
        assertThat(service.getModelDefinitionFromDb("shared_bid")).isEmpty();
        verify(mapper).findCurrentByCode("shared_bid");
        verify(cache).evict("shared_bid");
        verify(cache,never()).getModelDefinition(anyString(),any());
        assertThat(service.getModelDefinition("shared_bid")).contains(old);
        verify(release,times(2)).findModel(1L,"app","shared_bid");
    }
}
