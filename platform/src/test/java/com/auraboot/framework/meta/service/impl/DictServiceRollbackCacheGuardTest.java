package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.converter.DictConverter;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictCascadeService;
import com.auraboot.framework.meta.service.DictVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictServiceRollbackCacheGuardTest {

    @Mock private DictMapper dictMapper;
    @Mock private DictItemMapper dictItemMapper;
    @Mock private DictCascadeService dictCascadeService;
    @Mock private DictVersionService dictVersionService;
    @Mock private DictConverter dictConverter;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ConcurrentMapCacheManager cacheManager;
    private DictServiceImpl service;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager("dictData");
        service = new DictServiceImpl(
                dictMapper,
                dictItemMapper,
                dictCascadeService,
                dictVersionService,
                dictConverter,
                eventPublisher,
                cacheManager);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rollbackClearsEntryCachedAfterTransactionalDictRead() {
        Dict dict = new Dict();
        DictDTO dto = new DictDTO();
        when(dictMapper.findCurrentByCode("crm_status")).thenReturn(dict);
        when(dictConverter.toDTO(dict)).thenReturn(dto);

        assertThat(service.findByCode("crm_status")).isSameAs(dto);
        cache().put("tenant:crm_status", dto);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(cache().get("tenant:crm_status")).isNull();
    }

    @Test
    void committedTransactionPreservesCachedEntry() {
        Dict dict = new Dict();
        DictDTO dto = new DictDTO();
        when(dictMapper.findCurrentByCode("crm_status")).thenReturn(dict);
        when(dictConverter.toDTO(dict)).thenReturn(dto);

        service.findByCode("crm_status");
        cache().put("tenant:crm_status", dto);

        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(cache().get("tenant:crm_status", DictDTO.class)).isSameAs(dto);
    }

    @Test
    void repeatedReadsRegisterOnlyOneGuardPerTransaction() {
        Dict dict = new Dict();
        when(dictMapper.findCurrentByCode("crm_status")).thenReturn(dict);
        when(dictConverter.toDTO(dict)).thenReturn(new DictDTO());
        when(dictItemMapper.selectByDictIdAndStatus(42L, "enabled"))
                .thenReturn(List.of(new DictItem()));

        service.findByCode("crm_status");
        service.findEnabledItems(42L);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void emptyReadsDoNotRegisterGuard() {
        when(dictMapper.findCurrentByCode("missing")).thenReturn(null);
        when(dictItemMapper.selectByDictIdAndStatus(42L, "enabled")).thenReturn(List.of());

        assertThat(service.findByCode("missing")).isNull();
        assertThat(service.findEnabledItems(42L)).isEmpty();

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private Cache cache() {
        return cacheManager.getCache("dictData");
    }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(status));
    }
}
