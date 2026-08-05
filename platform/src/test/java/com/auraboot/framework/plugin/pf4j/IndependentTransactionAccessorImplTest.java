package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndependentTransactionAccessorImplTest {

    @Test
    void requiresNew_commitsTheBoundedCallbackAndSuppliesDynamicDataAccess() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        DynamicDataService dynamicData = mock(DynamicDataService.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        when(dynamicData.create("sample_model", Map.of("value", 7)))
                .thenReturn(Map.of("pid", "row-1"));

        IndependentTransactionAccessorImpl accessor =
                new IndependentTransactionAccessorImpl(transactions, dynamicData);
        String pid = accessor.requiresNew(db -> String.valueOf(
                db.create("sample_model", Map.of("value", 7)).get("pid")));

        assertThat(pid).isEqualTo("row-1");
        verify(transactions).commit(status);
    }
}
