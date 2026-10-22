package com.auraboot.framework.meta.cache;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interceptor's logic being correct is worth nothing if MyBatis never runs it.
 *
 * <p>That distinction is exactly what caused the bug this class exists for: {@code dictData}
 * had perfectly good {@code @CacheEvict} methods that **nothing ever called** (#1226). A
 * component that exists but is not wired is indistinguishable, from the outside, from one
 * that was never written. So: assert it is actually plugged into the SqlSessionFactory.
 */
@DisplayName("AggregateQueryCacheInterceptor is registered with MyBatis")
class AggregateQueryCacheInterceptorRegistrationIT extends BaseIntegrationTest {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    @DisplayName("MyBatis has the interceptor in its chain, so writes really do evict")
    void interceptorIsInTheChain() {
        assertThat(sqlSessionFactory.getConfiguration().getInterceptors())
                .as(
                        "AggregateQueryCacheInterceptor must be in MyBatis's interceptor chain — "
                            + "otherwise dynamic-data writes never evict the aggregate cache and "
                            + "dashboards go stale again")
                .anyMatch(i -> i instanceof AggregateQueryCacheInterceptor);
    }
}
