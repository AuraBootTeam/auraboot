package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.SecureQueryRequest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlInjectionProtector;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.service.UserPermissionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecureQueryRequiredReadCacheTest {
    @Test void enablingMemberReadGuardNeverServesOrStoresPreviouslyCachedRows() {
        var models=mock(MetaModelService.class);var caches=new ConcurrentMapCacheManager("secureQuery");
        var service=new SecureQueryExecutorImpl(mock(QueryBuilderService.class),models,mock(UserPermissionService.class),
                mock(SqlInjectionProtector.class),mock(QueryAuditService.class),mock(DynamicDataMapper.class),mock(DataPermissionEngine.class),caches);
        var request=new SecureQueryRequest();request.setModelCode("fixture_bundle");request.setTenantId(7L);request.setUserId(42L);
        when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(ModelDefinition.builder().code("fixture_bundle").build()));
        service.setQueryCache(request,"previous rows");
        assertThat(service.<String>getQueryCache(request)).isEqualTo("previous rows");
        when(models.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(RequiredReadReferencesTest.model(10)));
        assertThat(service.<String>getQueryCache(request)).isNull();
        service.setQueryCache(request,"rows after revocation");
        assertThat(caches.getCache("secureQuery").get(service.generateCacheKey(request)).get()).isEqualTo("previous rows");
        assertThat(service.<String>getQueryCache(request)).isNull();
    }
}
