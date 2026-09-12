package com.auraboot.framework.behavior;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.ingest.*;
import com.auraboot.framework.behavior.mapper.*;
import com.auraboot.framework.behavior.service.AnalyticsRetentionService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL persistence and intercepted mapper SQL. MQ transport is covered separately. */
class AnalyticsRetentionIT {
    private SqlSession session;
    private BehaviorEventPersister persister;
    private BehaviorIngestPublisher quarantine;
    private AnalyticsRetentionService service;
    private long tenant;
    private Instant first;

    @BeforeEach
    void open() {
        String url = Objects.requireNonNull(System.getenv("TEST_DATABASE_URL"), "TEST_DATABASE_URL must select an isolated, migrated PostgreSQL database");
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", url,
                Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")), System.getenv("TEST_DATABASE_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("retention-it", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            public net.sf.jsqlparser.expression.Expression getTenantId() { return new LongValue(MetaContext.getCurrentTenantId()); }
            public boolean ignoreTable(String table) { return "ab_behavior_event".equalsIgnoreCase(table); }
        }));
        config.addInterceptor(interceptor);
        config.addMapper(BehaviorEventMapper.class);
        config.addMapper(AnalyticsRetentionMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        quarantine = mock(BehaviorIngestPublisher.class);
        persister = new BehaviorEventPersister(session.getMapper(BehaviorEventMapper.class), quarantine, new ObjectMapper(), BehaviorIngestMetrics.noop());
        service = new AnalyticsRetentionService(session.getMapper(AnalyticsRetentionMapper.class));
        tenant = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        MetaContext.setContext(tenant, 7L, "retention-fixture", "retention-fixture");
        first = LocalDate.now(ZoneOffset.UTC).minusDays(40).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
        MetaContext.clear();
        if (quarantine != null) verifyNoInteractions(quarantine);
    }

    private void use(long tenantId, long user, String artifact, Instant time) {
        var event = new BehaviorEventInput();
        event.setEventId(UUID.randomUUID().toString()); event.setEventName("analytics_dashboard_used");
        event.setSchemaVersion("1"); event.setSource("server"); event.setProducerName("aurabot-analytics");
        event.setInteractionId(UUID.randomUUID().toString()); event.setOccurredAt(time);
        event.setSamplingProbability(BigDecimal.ONE);
        event.setProps(Map.of("targetType", "dashboard", "targetKey", artifact, "originalQuery", false));
        assertThat(persister.persistBatch(new BehaviorIngestEnvelope(tenantId, user, List.of(event)))).isEqualTo(1);
    }

    private AnalyticsRetention query(String unit) {
        // Include committed fixture rows, while cohort dates remain historical.
        return service.query(tenant, unit, new BehaviorQueryWindow(first, first.plusSeconds(86400)), Instant.now());
    }

    @Test
    void matureUserAndArtifactCohortsHaveDifferentDenominators() {
        use(tenant, 7, "one", first.plusSeconds(10));
        use(tenant, 7, "two", first.plusSeconds(20));
        use(tenant, 8, "three", first.plusSeconds(30));
        use(tenant, 9, "one", first.plusSeconds(86400 + 10));
        use(tenant, 7, "four", first.plusSeconds(86400 + 20));
        use(tenant, 7, "four", first.plusSeconds(86400 + 30));
        var users = query("user").records();
        var artifacts = query("artifact").records();
        assertThat(users.getFirst().cohortSize()).isEqualTo(2);
        assertThat(users.getFirst().retained()).isEqualTo(1);
        assertThat(users.getFirst().retentionRate()).isEqualByComparingTo("0.5");
        assertThat(artifacts.getFirst().cohortSize()).isEqualTo(3);
        assertThat(artifacts.getFirst().retained()).isEqualTo(1);
        assertThat(artifacts.getFirst().retentionRate()).isEqualByComparingTo("0.333333");
        assertThat(users).extracting(AnalyticsRetention.Point::dayOffset).containsExactly(1, 7, 30);
        assertThat(users.get(1).retained()).isZero();
        assertThat(users.get(2).retained()).isZero();
    }

    @Test
    void historyOutsideCohortWindowPreventsFalseFirstUse() {
        use(tenant, 7, "existing", first.minusSeconds(1));
        use(tenant, 7, "existing", first.plusSeconds(10));
        assertThat(query("user").records()).isEmpty();
        assertThat(query("artifact").records()).isEmpty();
    }

    @Test
    void nextUtcDayIsHalfOpenAndOtherTenantsCannotSupplyReturns() {
        use(tenant, 7, "one", first.plusSeconds(1));
        use(tenant, 8, "two", first.plusSeconds(2));
        use(tenant, 7, "one", first.plusSeconds(86400));
        use(tenant, 8, "two", first.plusSeconds(2 * 86400));
        use(tenant + 1, 8, "two", first.plusSeconds(86400 + 1));
        var dayOne = query("user").records().getFirst();
        assertThat(dayOne.cohortSize()).isEqualTo(2);
        assertThat(dayOne.retained()).isEqualTo(1);
    }

    @Test
    void currentCohortRemainsImmatureAndUncalculable() {
        first = Instant.now().minusSeconds(2);
        use(tenant, 7, "new", first.plusSeconds(1));
        assertThat(query("user").records()).hasSize(3).allSatisfy(point -> {
            assertThat(point.status()).isEqualTo("immature");
            assertThat(point.retained()).isNull();
            assertThat(point.retentionRate()).isNull();
        });
    }
    @Test
    void observationDayBecomesMatureExactlyAtItsUtcEnd() {
        first = LocalDate.now(ZoneOffset.UTC).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        use(tenant, 7, "one", first.plusSeconds(1));
        use(tenant, 7, "one", first.plusSeconds(86400 + 1));
        Instant end = first.plusSeconds(2 * 86400);
        var window = new BehaviorQueryWindow(first, first.plusSeconds(86400));
        var before = service.query(tenant, "user", window, end.minusNanos(1000)).records().getFirst();
        var closed = service.query(tenant, "user", window, end).records().getFirst();
        assertThat(before.status()).isEqualTo("immature");
        assertThat(before.retentionRate()).isNull();
        assertThat(closed.status()).isEqualTo("observed");
        assertThat(closed.retentionRate()).isEqualByComparingTo("1");
    }

}
