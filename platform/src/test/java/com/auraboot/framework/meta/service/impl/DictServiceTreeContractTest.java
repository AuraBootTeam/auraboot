package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.converter.DictConverter;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictCascadeService;
import com.auraboot.framework.meta.service.DictVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DictServiceTreeContractTest {
    private final DictMapper dictMapper = mock(DictMapper.class);
    private final DictItemMapper itemMapper = mock(DictItemMapper.class);
    private final DictServiceImpl service = new DictServiceImpl(dictMapper, itemMapper,
            mock(DictCascadeService.class), mock(DictVersionService.class), mock(DictConverter.class),
            mock(ApplicationEventPublisher.class), new ConcurrentMapCacheManager("dictData"));

    @BeforeEach
    void setUp() {
        MetaContext.setContext(42L, 1L, "test-user", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private void dictionary(String type) {
        var dict = new Dict();
        dict.setId(7L);
        dict.setDictType(type);
        when(dictMapper.selectByPidWithContext("owned-pid", 42L)).thenReturn(dict);
    }

    @Test
    void emptyTreeHasAnExplicitEmptyRoot() {
        dictionary("tree");
        when(itemMapper.selectByDictIdAndStatus(7L, "enabled")).thenReturn(List.of());
        var root = service.buildCascadeTree("owned-pid");
        assertThat(root.getValue()).isNull();
        assertThat(root.getLabel()).isNull();
        assertThat(root.getChildren()).isEmpty();
    }

    @Test
    void missingPidIsNotSuccessfulEmptyData() {
        assertThatThrownBy(() -> service.buildCascadeTree("missing-pid")).isInstanceOf(ValidationException.class);
    }

    @Test
    void unsupportedDictionaryTypeIsNotSuccessfulEmptyData() {
        dictionary("dynamic");
        assertThatThrownBy(() -> service.buildCascadeTree("owned-pid"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("tree or cascade");
    }

    @Test
    void storageFailureIsNotSuccessfulEmptyData() {
        dictionary("tree");
        var failure = new IllegalStateException("storage unavailable");
        when(itemMapper.selectByDictIdAndStatus(7L, "enabled")).thenThrow(failure);
        assertThatThrownBy(() -> service.buildCascadeTree("owned-pid")).isSameAs(failure);
    }

    @Test
    void nonemptyItemsWithoutARootAreNotSuccessfulEmptyData() {
        dictionary("tree");
        var orphan = new DictItem();
        orphan.setValue("child");
        orphan.setLabel("Child");
        orphan.setParentValue("missing-parent");
        when(itemMapper.selectByDictIdAndStatus(7L, "enabled")).thenReturn(List.of(orphan));
        assertThatThrownBy(() -> service.buildCascadeTree("owned-pid"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("root node");
    }
}
