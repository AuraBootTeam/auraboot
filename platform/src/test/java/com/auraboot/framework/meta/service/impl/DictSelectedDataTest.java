package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import com.auraboot.framework.meta.controller.config.DictController;
import com.auraboot.framework.meta.converter.DictConverter;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictCascadeService;
import com.auraboot.framework.meta.service.DictVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DictSelectedDataTest {
    private final DictMapper mapper = mock(DictMapper.class);
    private final DictVersionService versions = mock(DictVersionService.class);
    private final DictConverter converter = mock(DictConverter.class);
    private final DictServiceImpl service = new DictServiceImpl(mapper, mock(DictItemMapper.class),
            mock(DictCascadeService.class), versions, converter, mock(ApplicationEventPublisher.class),
            new ConcurrentMapCacheManager("dictData"));
    private final DictController controller = new DictController(service, versions,
            mock(DictCascadeService.class), null, mock(I18nLocaleResolver.class));

    @BeforeEach
    void context() {
        MetaContext.setContext(42L, 1L, "test-user", "tester");
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private Dict dictionary() {
        var dict = new Dict();
        dict.setId(7L);
        dict.setPid("selected-pid");
        dict.setCode("STATUS");
        dict.setStatus("deprecated");
        dict.setIsCurrent(false);
        when(mapper.findByPid("selected-pid")).thenReturn(dict);
        when(mapper.selectByPidWithContext("selected-pid", 42L)).thenReturn(dict);
        var dto = new DictDTO();
        dto.setCode("STATUS");
        when(converter.toDTO(dict)).thenReturn(dto);
        return dict;
    }

    @Test
    void defaultPidReadPreservesUnpublishedSelectedItems() {
        var dict = dictionary();
        var item = new DictDataResult.DictItemData();
        item.setValue("active");
        item.setLabel("Active");
        var result = new DictDataResult();
        result.setSuccess(true);
        result.setItems(List.of(item));
        when(versions.loadUnifiedDictData(dict)).thenReturn(result);

        var data = controller.loadDictData("selected-pid", "latest", null, null).getData();

        assertThat(data.getSuccess()).isTrue();
        assertThat(data.getItems()).extracting(DictDataResult.DictItemData::getValue)
                .containsExactly("active");
        assertThat(data.getItems()).extracting(DictDataResult.DictItemData::getLabel)
                .containsExactly("Active");
        verify(versions, never()).loadDictByStrategy(anyString(), anyString(), any());
    }

    @Test
    void selectedReadRejectsPidOutsideCurrentTenantBeforeLoadingItems() {
        assertThatThrownBy(() -> service.loadSelectedDictData("foreign-pid"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("当前租户");
        verify(mapper).selectByPidWithContext("foreign-pid", 42L);
        verifyNoInteractions(versions);
    }

    @Test
    void explicitPinnedStrategyRetainsVersionPolicy() {
        dictionary();
        var denied = new DictDataResult();
        denied.setSuccess(false);
        denied.setErrorMessage("Dictionary is unpublished");
        when(versions.loadDictByStrategy("STATUS", "pinned", "2")).thenReturn(denied);

        assertThat(controller.loadDictData("selected-pid", "pinned", "2", null)
                .getData().getSuccess()).isFalse();
        verify(versions, never()).loadUnifiedDictData(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lifecycleChangeEvictsAlreadyCachedRuntimeState(boolean publish) {
        var caches = new ConcurrentMapCacheManager("dictData");
        var target = mock(DictServiceImpl.class);
        var interceptor = new CacheInterceptor();
        interceptor.setCacheManager(caches);
        interceptor.setCacheOperationSources(new AnnotationCacheOperationSource());
        interceptor.afterPropertiesSet();
        interceptor.afterSingletonsInstantiated();
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        var proxy = (DictServiceImpl) factory.getProxy();
        var cache = caches.getCache("dictData");
        cache.put("tenant:STATUS:latest:null", !publish);

        if (publish) proxy.publish("selected-pid", null);
        else proxy.unpublish("selected-pid");

        assertThat(cache.get("tenant:STATUS:latest:null")).isNull();
    }
}
