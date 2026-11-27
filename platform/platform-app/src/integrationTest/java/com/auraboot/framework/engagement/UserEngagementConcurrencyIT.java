package com.auraboot.framework.engagement;

import static org.junit.jupiter.api.Assertions.*;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.engagement.dto.UserEngagementDTO;
import com.auraboot.framework.engagement.mapper.UserEngagementMapper;
import com.auraboot.framework.engagement.service.impl.UserEngagementServiceImpl;
import com.auraboot.framework.test.util.OwnedTestDatabase;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Production service/mapper, PostgreSQL, tenant interception and real caller transactions. */
class UserEngagementConcurrencyIT {
    private UserEngagementServiceImpl service;
    private UserEngagementMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private final FirstReadBarrier barrier = new FirstReadBarrier();
    private final long user = IdWorker.getId();
    private final long tenant = IdWorker.getId();

    @BeforeEach void prepare() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url, OwnedTestDatabase.AURABOOT_SLOT_PATTERN,
                "Engagement concurrency requires an explicitly owned slot database");
        var source = new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"),
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        jdbc = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(barrier);
        var tenantPlugin = new MybatisPlusInterceptor();
        tenantPlugin.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new LongValue(MetaContext.getCurrentTenantId()); }
        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(tenantPlugin);
        var sessions = new SqlSessionTemplate(factory.getObject());
        sessions.getConfiguration().addMapper(UserEngagementMapper.class);
        mapper = sessions.getMapper(UserEngagementMapper.class);
        service = new UserEngagementServiceImpl(mapper);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        System.out.println("Retained engagement fixture user=" + user + " tenant=" + tenant);
    }

    private UserEngagementDTO input(String path, String label) {
        var dto = new UserEngagementDTO();
        dto.setTargetType("page"); dto.setTargetId(path); dto.setTargetLabel(label);
        dto.setEngagementType("recent_view");
        dto.setTargetContext(Map.of("path", path, "title", label, "nested", Map.of("allowed", true)));
        return dto;
    }

    @Test void concurrentFirstVisitsReturnOneIdentityAndLeaveEveryCallerTransactionUsable() throws Exception {
        barrier.arm(8);
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<java.util.concurrent.Future<UserEngagementDTO>>();
        try {
            for (int i = 0; i < 8; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    MetaContext.setContext(tenant, user, null, "system");
                    try {
                        return transactions.execute(status -> {
                            var saved = service.upsert(user, tenant, input("/concurrent", "visit-" + index));
                            assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
                            return saved;
                        });
                    } finally { MetaContext.clear(); }
                }));
            }
            var results = new ArrayList<UserEngagementDTO>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            assertEquals(8, results.size());
            assertEquals(1, results.stream().map(UserEngagementDTO::getId).distinct().count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_user_engagement WHERE user_id=? AND tenant_id=?", Integer.class, user, tenant));
            MetaContext.setContext(tenant, user, null, "system");
            var stored = mapper.selectById(results.getFirst().getId());
            assertEquals("/concurrent", stored.getTargetContext().get("path"));
            assertEquals(Map.of("allowed", true), stored.getTargetContext().get("nested"));
            assertTrue(stored.getTargetLabel().startsWith("visit-"));
            var refreshed = transactions.execute(s -> service.upsert(user, tenant, input("/concurrent", "revisited")));
            assertEquals(stored.getId(), refreshed.getId());
            assertEquals(stored.getCreatedAt(), refreshed.getCreatedAt());
            assertFalse(refreshed.getUpdatedAt().isBefore(stored.getUpdatedAt()));
            assertEquals("revisited", mapper.selectById(stored.getId()).getTargetContext().get("title"));
            MetaContext.setContext(tenant + 1, user, null, "system");
            assertNull(mapper.selectById(stored.getId()));
        } finally {
            MetaContext.clear(); barrier.release(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void writeAndContextRollBackWithTheCallerAndManualOrderSurvivesAConflict() {
        MetaContext.setContext(tenant, user, null, "system");
        try {
            assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
                service.upsert(user, tenant, input("/rollback", "discarded"));
                throw new IllegalStateException("controlled caller failure");
            }));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_user_engagement WHERE user_id=? AND tenant_id=?", Integer.class, user, tenant));
            var first = input("/ordered", "first"); first.setEngagementType("favorite"); first.setSortOrder(7);
            var saved = transactions.execute(s -> service.upsert(user, tenant, first));
            var candidate = new com.auraboot.framework.engagement.entity.UserEngagement();
            candidate.setId(IdWorker.getId()); candidate.setUserId(user); candidate.setTenantId(tenant);
            candidate.setTargetType("page"); candidate.setTargetId("/ordered"); candidate.setTargetLabel("conflict");
            candidate.setTargetContext(Map.of("path", "/ordered")); candidate.setEngagementType("favorite");
            candidate.setSortOrder(99); candidate.setCreatedAt(java.time.OffsetDateTime.now()); candidate.setUpdatedAt(candidate.getCreatedAt());
            var result = transactions.execute(s -> mapper.insertOrRefresh(candidate));
            assertEquals(saved.getId(), result.getId()); assertEquals(7, result.getSortOrder());
            assertEquals(saved.getCreatedAt(), result.getCreatedAt());
        } finally { MetaContext.clear(); }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
        MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class
    }))
    static class FirstReadBarrier implements Interceptor {
        private final AtomicInteger remaining = new AtomicInteger();
        private volatile CountDownLatch reads = new CountDownLatch(0);
        void arm(int count) { reads = new CountDownLatch(count); remaining.set(count); }
        void release() { while (reads.getCount() > 0) reads.countDown(); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (((MappedStatement) invocation.getArgs()[0]).getId().endsWith("UserEngagementMapper.selectList")
                    && remaining.getAndDecrement() > 0) {
                reads.countDown();
                if (!reads.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("first-read concurrency barrier timed out");
            }
            return result;
        }
    }
}
