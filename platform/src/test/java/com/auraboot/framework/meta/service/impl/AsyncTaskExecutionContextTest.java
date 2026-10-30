package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AsyncTaskExecutionContextTest {
    @Test void claimedIdentityRestoresNestedScopesAndCannotLeakAfterFailure() {
        var first=new AsyncTaskExecutionContext.Snapshot(1L,"first",10L,20L,"claim-one");
        var second=new AsyncTaskExecutionContext.Snapshot(2L,"second",11L,21L,"claim-two");
        assertThatThrownBy(AsyncTaskExecutionContext::require).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->{
            try(var outer=AsyncTaskExecutionContext.enter(first)) {
                assertThat(AsyncTaskExecutionContext.require()).isEqualTo(first);
                try(var inner=AsyncTaskExecutionContext.enter(second)) {
                    assertThat(AsyncTaskExecutionContext.require()).isEqualTo(second);
                }
                assertThat(AsyncTaskExecutionContext.require()).isEqualTo(first);
                throw new IllegalArgumentException("executor failed");
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("executor failed");
        assertThatThrownBy(AsyncTaskExecutionContext::require).isInstanceOf(IllegalStateException.class);
    }
}
