package com.auraboot.framework.environment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.service.impl.EnvironmentServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL, production mapper and transaction proxy; all fixture rows are retained. */
@SpringJUnitConfig(DefaultEnvironmentConcurrencyIT.Stack.class)
class DefaultEnvironmentConcurrencyIT {
    @Autowired EnvironmentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired FirstReadBarrier barrier;
    @Autowired DataSourceTransactionManager transactions;

    @Test
    void concurrentFirstRequestsReturnOneDefaultWithoutAbortingCallerTransactions() throws Exception {
        long tenant = UUID.randomUUID().getMostSignificantBits() & 0x001fffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)", tenant,
            UniqueIdGenerator.generate(), "default-environment-concurrency-" + tenant);
        barrier.arm(8);
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<java.util.concurrent.Future<Long>>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    Long id = service.findOrCreateDefaultId(tenant);
                    // A recovered unique-constraint exception must not leave this caller in 25P02.
                    assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                    return id;
                })));
            }
            var ids = new ArrayList<Long>();
            var failures = new ArrayList<Throwable>();
            for (var future : futures) {
                try { ids.add(future.get(30, TimeUnit.SECONDS)); }
                catch (java.util.concurrent.ExecutionException failure) { failures.add(failure.getCause()); }
            }
            assertThat(failures).isEmpty();
            assertThat(ids).hasSize(8).doesNotContainNull();
            assertThat(ids.stream().distinct().count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_environment WHERE tenant_id=? AND code='default' AND deleted_flag=false",
                Long.class, tenant)).isEqualTo(1);
            assertThat(service.findOrCreateDefaultId(tenant)).isEqualTo(ids.getFirst());
        } finally {
            barrier.release();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // Schedule only the initial real reads; no query result or write is replaced.
    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
        MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class
    }))
    static class FirstReadBarrier implements Interceptor {
        private final AtomicInteger remaining = new AtomicInteger();
        private volatile CountDownLatch reads = new CountDownLatch(0);
        void arm(int count) { reads = new CountDownLatch(count); remaining.set(count); }
        void release() { while (reads.getCount() > 0) reads.countDown(); }
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (((MappedStatement) invocation.getArgs()[0]).getId().endsWith(".findByTenantAndCode")
                    && remaining.getAndDecrement() > 0) {
                reads.countDown();
                if (!reads.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("initial read barrier timed out");
            }
            return result;
        }
    }

    @EnableTransactionManagement
    static class Stack {
        @Bean DataSource dataSource() {
            String url = System.getenv("TEST_DATABASE_URL");
            if (url == null || !url.matches("jdbc:postgresql://[^/]+/auraboot_[0-9]+")) {
                throw new IllegalStateException("This IT requires an owned auraboot slot database");
            }
            return new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"),
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        }
        @Bean FirstReadBarrier firstReadBarrier() { return new FirstReadBarrier(); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, FirstReadBarrier barrier) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addInterceptor(barrier);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean MapperFactoryBean<EnvironmentMapper> environmentMapper(SqlSessionFactory sql) {
            var bean = new MapperFactoryBean<>(EnvironmentMapper.class);
            bean.setSqlSessionFactory(sql);
            return bean;
        }
        @Bean EnvironmentService service(EnvironmentMapper mapper) {
            var service = new EnvironmentServiceImpl();
            ReflectionTestUtils.setField(service, "environmentMapper", mapper);
            return service;
        }
        @Bean MapperFactoryBean<com.auraboot.framework.audit.mapper.AdminEventLogMapper> adminEventLogMapper(SqlSessionFactory sql) {
            var bean = new MapperFactoryBean<>(com.auraboot.framework.audit.mapper.AdminEventLogMapper.class);
            bean.setSqlSessionFactory(sql);
            return bean;
        }
        @Bean com.auraboot.framework.audit.service.AdminEventLogService adminEventLogService(
                com.auraboot.framework.audit.mapper.AdminEventLogMapper mapper) {
            return new com.auraboot.framework.audit.service.impl.AdminEventLogServiceImpl();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
    }
}
