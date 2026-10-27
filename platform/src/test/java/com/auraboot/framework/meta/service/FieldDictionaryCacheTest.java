package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.service.impl.MetaFieldServiceImpl;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FieldDictionaryCacheTest {
    @org.junit.jupiter.api.BeforeEach void context() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(7L, 99L, "actor", "tester");
    }
    @org.junit.jupiter.api.AfterEach void clear() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }
    @Test void bindingAndUnbindingInvalidateAlreadyReadFieldMetadata() {
        var dictionary = mock(DictService.class);
        var mapper = mock(MetaFieldDictBindingMapper.class);
        var target = spy(new MetaFieldServiceImpl(null, dictionary, null, null, null, null, mapper, null, null, null));
        var field = MetaFieldDTO.builder().pid("field-pid").code("delivery_state").dataType("enum").id(1L).build();
        doReturn(field).when(target).findByPid("field-pid");
        var dict = new DictDTO(); dict.setId(2L);
        when(dictionary.findByCode("delivery_status")).thenReturn(dict);
        var manager = new ConcurrentMapCacheManager("modelDefinitions", "modelFieldBindings", "metaField", "viewModelFields", "viewModelSummary");
        var interceptor = new CacheInterceptor(); interceptor.setCacheManager(manager);
        interceptor.setCacheOperationSources(new AnnotationCacheOperationSource()); interceptor.afterPropertiesSet(); interceptor.afterSingletonsInstantiated();
        var factory = new ProxyFactory(target); factory.addAdvice(interceptor);
        var service = (MetaFieldService) factory.getProxy();
        for (String name : manager.getCacheNames()) manager.getCache(name).put("already-read", "stale dictionary metadata");
        assertThat(service.bindDictionary("field-pid", "delivery_status")).isTrue();
        for (String name : manager.getCacheNames()) assertThat(manager.getCache(name).get("already-read")).isNull();
        var binding = FieldDictBinding.builder().pid("binding-pid").dictCode("delivery_status").build();
        when(mapper.findByFieldPid(eq("field-pid"), any())).thenReturn(binding);
        when(mapper.deleteByFieldPid(eq("field-pid"), any())).thenReturn(1);
        for (String name : manager.getCacheNames()) manager.getCache(name).put("already-read", "bound dictionary");
        assertThat(service.unbindDictionary("field-pid")).isTrue();
        for (String name : manager.getCacheNames()) assertThat(manager.getCache(name).get("already-read")).isNull();
    }
}
