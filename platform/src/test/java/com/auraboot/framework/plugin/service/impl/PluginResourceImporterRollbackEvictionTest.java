package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A read-through cache entry loaded inside the import transaction survives its
 * rollback — the phantom that used to resurface as "Model exists but cannot be
 * loaded for update" on retries. The importer must shed those entries when the
 * transaction rolls back, and must not touch them on commit.
 */
class PluginResourceImporterRollbackEvictionTest {

    private final PluginResourceImporterImpl importer =
            mock(PluginResourceImporterImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final MetaModelService modelService = mock(MetaModelService.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(importer, "metaModelService", modelService);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackImportEvictsTheModelDefinitionCache() {
        ReflectionTestUtils.invokeMethod((Object) ReflectionTestUtils.invokeMethod(importer, "fieldsModelsBindings"), "registerModelCacheRollbackEviction", "data_permission");

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        org.assertj.core.api.Assertions.assertThat(synchronizations).hasSize(1);
        synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(modelService).clearAllCache();
    }

    @Test
    void committedImportKeepsTheCache() {
        ReflectionTestUtils.invokeMethod((Object) ReflectionTestUtils.invokeMethod(importer, "fieldsModelsBindings"), "registerModelCacheRollbackEviction", "data_permission");

        TransactionSynchronizationManager.getSynchronizations()
                .getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verifyNoInteractions(modelService);
    }

    @Test
    void noActiveTransactionRegistersNothing() {
        TransactionSynchronizationManager.clearSynchronization();

        ReflectionTestUtils.invokeMethod((Object) ReflectionTestUtils.invokeMethod(importer, "fieldsModelsBindings"), "registerModelCacheRollbackEviction", "data_permission");

        verifyNoInteractions(modelService);
    }
}
