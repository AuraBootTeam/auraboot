package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.entity.AuditTrail;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.mybatis.spring.mapper.MapperFactoryBean;
import com.auraboot.framework.meta.mapper.AuditTrailMapper;
import com.auraboot.framework.user.mapper.UserMapper;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link AuditTrailService} — recordAudit (hash-chained), the
 * entity/pid/actor/command query surface, getLatestRecord, verifyChainIntegrity and
 * generateComplianceReport and concurrent chain appends. Real PostgreSQL, production
 * mapper and Spring transaction proxy; unique tenant per run, evidence retained.
 */
@Slf4j
@SpringJUnitConfig(AuditTrailServiceCoverageIT.Stack.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AuditTrailService Coverage IT — record + query + chain verify")
class AuditTrailServiceCoverageIT {

    private static final long TENANT_ID = UUID.randomUUID().getMostSignificantBits() & 0x001fffffffffffffL;
    private static final long ACTOR_ID = TENANT_ID + 1;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private AuditTrailService auditTrailService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, ACTOR_ID, "audit-test-pid", "audit-test-user");
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private AuditTrailEvent event(String entityPid, long entityId, String command) {
        return AuditTrailEvent.builder()
                .tenantId(TENANT_ID)
                .eventType("command")
                .entityType("audit_order")
                .entityId(entityId)
                .entityPid(entityPid)
                .commandCode(command)
                .operationType("create")
                .actorId(ACTOR_ID)
                .actorName("audit-test-user")
                .build();
    }

    @Test
    @DisplayName("recordAudit chains records; entity/pid/actor/command queries + latest return them")
    void recordAndQuery() {
        long e1 = seq.incrementAndGet();
        AuditTrail a1 = auditTrailService.recordAudit(event("apid_" + e1, e1, "audit:create"));
        assertNotNull(a1);
        long e2 = seq.incrementAndGet();
        AuditTrail a2 = auditTrailService.recordAudit(event("apid_" + e2, e2, "audit:create"));
        assertNotNull(a2);

        assertNotNull(auditTrailService.getLatestRecord(TENANT_ID));
        assertTrue(auditTrailService.getAuditTrail(TENANT_ID, "audit_order", e1).stream()
                .anyMatch(r -> r.getEntityId() != null && r.getEntityId() == e1));
        assertTrue(auditTrailService.getAuditTrailByPid(TENANT_ID, "audit_order", "apid_" + e1).size() >= 1);
        assertTrue(auditTrailService.getAuditByCommand(TENANT_ID, "audit:create").size() >= 2);
        assertNotNull(auditTrailService.getAuditByActor(
                TENANT_ID, ACTOR_ID, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("verifyChainIntegrity + generateComplianceReport over the tenant's records")
    void chainAndCompliance() {
        long e = seq.incrementAndGet();
        auditTrailService.recordAudit(event("apid_" + e, e, "audit:verify"));

        assertNotNull(auditTrailService.verifyChainIntegrity(TENANT_ID, 0L, Long.MAX_VALUE));
        assertNotNull(auditTrailService.generateComplianceReport(
                TENANT_ID, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60)));
    }
    @Test
    void concurrentAppendsKeepEveryRecordAndOneContinuousHashChain() throws Exception {
        String command = "audit:concurrent:" + UUID.randomUUID();
        long from = auditTrailService.getLatestRecord(TENANT_ID) == null ? 1L
                : auditTrailService.getLatestRecord(TENANT_ID).getSequenceNo() + 1;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                futures.add(pool.submit(() -> {
                    MetaContext.setContext(TENANT_ID, ACTOR_ID, "audit-test-pid", "audit-test-user");
                    ready.countDown();
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
                        for (int n = 0; n < 8; n++) {
                            long entity = seq.incrementAndGet();
                            auditTrailService.recordAudit(event("concurrent_" + entity, entity, command));
                        }
                    } finally {
                        MetaContext.clear();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        var records = auditTrailService.getAuditByCommand(TENANT_ID, command);
        assertEquals(64, records.size());
        assertEquals(64, records.stream().map(AuditTrail::getEntityPid).distinct().count());
        for (int i = 0; i < records.size(); i++) assertEquals(from + i, records.get(i).getSequenceNo());
        var integrity = auditTrailService.verifyChainIntegrity(TENANT_ID, from, from + 63);
        assertTrue(integrity.isValid(), integrity.getMessage());
        assertEquals(64, integrity.getRecordsVerified());
    }

    @Test
    void independentAuditTransactionSurvivesCallerRollback() {
        String command = "audit:independent:" + UUID.randomUUID();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        tx.executeWithoutResult(status -> {
            long entity = seq.incrementAndGet();
            auditTrailService.recordAudit(event("independent_" + entity, entity, command));
            status.setRollbackOnly();
        });
        assertEquals(1, auditTrailService.getAuditByCommand(TENANT_ID, command).size());
    }

    @Configuration
    @EnableTransactionManagement
    static class Stack {
        @Bean DataSource dataSource() {
            String url = System.getenv("TEST_DATABASE_URL");
            if (url == null || !url.matches("jdbc:postgresql://[^/]+/aura_bpm_[0-9]+(?:\\?.*)?")) {
                throw new IllegalStateException("TEST_DATABASE_URL must identify an owned aura_bpm slot database");
            }
            return new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"),
                    System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        }
        @Bean org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean MapperFactoryBean<AuditTrailMapper> auditTrailMapper(org.apache.ibatis.session.SqlSessionFactory sql) {
            var bean = new MapperFactoryBean<>(AuditTrailMapper.class);
            bean.setSqlSessionFactory(sql);
            return bean;
        }
        @Bean MapperFactoryBean<UserMapper> userMapper(org.apache.ibatis.session.SqlSessionFactory sql) {
            var bean = new MapperFactoryBean<>(UserMapper.class);
            bean.setSqlSessionFactory(sql);
            return bean;
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean AuditTrailService auditTrailService(AuditTrailMapper audit, UserMapper users) { return new AuditTrailService(audit, users); }
    }

}
