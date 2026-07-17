package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.MetaFieldUpdateRequest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MetaModelServiceImplCacheContractTest {

    @Test
    @DisplayName("model definition cache uses Spring Optional-unwrapped null guard")
    void modelDefinitionCacheUsesSpringOptionalUnwrappedNullGuard() throws Exception {
        Cacheable cacheable = MetaDefinitionCacheService.class
                .getMethod("getModelDefinition", String.class, java.util.function.Supplier.class)
                .getAnnotation(Cacheable.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.unless())
                .as("Spring unwraps Optional for #result; Optional.empty is exposed as null")
                .isEqualTo("#result == null");
    }

    @Test
    @DisplayName("clearAllCache evicts model, field, and binding projection caches together")
    void clearAllCacheEvictsModelFieldAndBindingProjectionCaches() throws Exception {
        CacheEvict evict = MetaModelServiceImpl.class
                .getMethod("clearAllCache")
                .getAnnotation(CacheEvict.class);

        assertProjectionEviction(evict);
    }

    @Test
    @DisplayName("field mutations evict model, field, and binding projection caches")
    void fieldMutationsEvictModelFieldAndBindingProjectionCaches() throws Exception {
        assertProjectionEviction(MetaFieldServiceImpl.class
                .getMethod("update", String.class, MetaFieldUpdateRequest.class));
        assertProjectionEviction(MetaFieldServiceImpl.class
                .getMethod("delete", String.class));
        assertProjectionEviction(MetaFieldServiceImpl.class
                .getMethod("publishVersion", String.class));
        assertProjectionEviction(MetaFieldServiceImpl.class
                .getMethod("rollbackToVersion", String.class, Integer.class));
    }

    @Test
    @DisplayName("field binding mutations evict model, field, and binding projection caches")
    void fieldBindingMutationsEvictModelFieldAndBindingProjectionCaches() throws Exception {
        assertProjectionEviction(MetaModelServiceImpl.class
                .getMethod(
                        "bindFieldToModel",
                        Long.class,
                        Long.class,
                        Integer.class,
                        Boolean.class,
                        Boolean.class,
                        Boolean.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class));
        assertProjectionEviction(MetaModelServiceImpl.class
                .getMethod("unbindFieldFromModel", Long.class, Long.class));
        assertProjectionEviction(MetaModelServiceImpl.class
                .getMethod("updateFieldBinding", ModelFieldBinding.class));
    }

    @Test
    @DisplayName("model current-version mutations evict model, field, and binding projection caches")
    void modelCurrentVersionMutationsEvictModelFieldAndBindingProjectionCaches() throws Exception {
        assertProjectionEviction(MetaModelServiceImpl.class
                .getMethod("saveDefinition", ModelDefinition.class));
        assertProjectionEviction(MetaModelServiceImpl.class
                .getMethod("delete", String.class));
        assertProjectionEviction(MetaModelServiceImpl.class
                .getMethod("rollbackToVersion", String.class, Integer.class));
    }

    private static void assertProjectionEviction(Method method) {
        assertProjectionEviction(method.getAnnotation(CacheEvict.class));
    }

    private static void assertProjectionEviction(CacheEvict evict) {
        assertThat(evict).isNotNull();
        assertThat(evict.allEntries()).isTrue();
        // fieldBindings / metaFieldByKey / fieldDefinitions / relationDefinitions / modelExists
        // used to be in this list. They were cache regions **nothing ever populated** — no
        // @Cacheable, no cache.put, anywhere. Evicting them was a no-op that read as diligence.
        // Removed 2026-07-14 along with the regions themselves; see scripts/check-cache-eviction.mjs.
        assertThat(Arrays.asList(evict.value()))
                .contains(
                        "modelDefinitions",
                        "modelFieldBindings",
                        "metaField",
                        "viewModelFields",
                        "viewModelSummary"
                );
    }
}
