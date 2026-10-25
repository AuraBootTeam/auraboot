package com.auraboot.framework.behavior;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.ingest.*;
import com.auraboot.framework.behavior.mapper.*;
import com.auraboot.framework.behavior.service.AnalyticsExecutionService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL persistence and intercepted mapper SQL. MQ transport is covered separately. */
class AnalyticsExecutionIT {
    private SqlSessionTemplate session;
    private BehaviorEventPersister persister;
    private BehaviorIngestPublisher quarantine;
    private AnalyticsExecutionService service;
    private long tenant;
    private Instant first;

    @BeforeEach
    void open() {
        String url = Objects.requireNonNull(System.getenv("TEST_DATABASE_URL"), "TEST_DATABASE_URL must select an isolated, migrated PostgreSQL database");
        var dataSource = new UnpooledDataSource("org.postgresql.Driver", url,
                Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")), System.getenv("TEST_DATABASE_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("execution-it", new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            public net.sf.jsqlparser.expression.Expression getTenantId() { return new LongValue(MetaContext.getCurrentTenantId()); }
            public boolean ignoreTable(String table) { return "ab_behavior_event".equalsIgnoreCase(table); }
        }));
        config.addInterceptor(interceptor);
        config.addMapper(BehaviorEventMapper.class);
        config.addMapper(AnalyticsExecutionMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        quarantine = mock(BehaviorIngestPublisher.class);
        persister = new BehaviorEventPersister(session.getMapper(BehaviorEventMapper.class), quarantine, new ObjectMapper(), BehaviorIngestMetrics.noop());
        service = new AnalyticsExecutionService(session.getMapper(AnalyticsExecutionMapper.class));
        tenant = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        MetaContext.setContext(tenant, 7L, "execution-fixture", "execution-fixture");
        first = LocalDate.now(ZoneOffset.UTC).minusDays(40).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @AfterEach
    void close() {
        MetaContext.clear();
        if (quarantine != null) verifyNoInteractions(quarantine);
    }

    private BehaviorEventInput event(String run, boolean start, Instant at, String cause,
                                     String status, String principal) {
        var input = new BehaviorEventInput();
        input.setEventId(UUID.randomUUID().toString());
        input.setEventName(start ? "agent_execution_started" : "agent_execution_completed");
        input.setSchemaVersion("1"); input.setSource("server");
        input.setProducerName("server-outcome-outbox"); input.setRunId(run);
        input.setOccurredAt(at); input.setCausedByEventId(cause);
        input.setSamplingProbability(BigDecimal.ONE);
        Map<String, Object> props = new HashMap<>();
        props.put("targetType", "agent_run"); props.put("targetKey", run);
        props.put("taskPid", "task-" + run); props.put("principalType", principal);
        if (status != null) props.put("status", status);
        input.setProps(props);
        return input;
    }

    private String persist(long scope, BehaviorEventInput input) {
        assertThat(persister.persistBatch(new BehaviorIngestEnvelope(scope, 7L, List.of(input)))).isEqualTo(1);
        return input.getEventId();
    }

    private String start(String run, Instant at, String principal) {
        return persist(tenant, event(run, true, at, null, null, principal));
    }

    private AnalyticsExecution query() {
        return service.query(tenant, new BehaviorQueryWindow(first, first.plusSeconds(60)), Instant.now());
    }

    @Test void mixedCohortKeepsUnresolvedAndExcludesSandbox() {
        for (String status : List.of("success", "failed", "cancelled")) {
            String id = start(status, first.plusSeconds(1), "human_delegated");
            persist(tenant, event(status, false, first.plusSeconds(120), id, status, "human_delegated"));
        }
        start("pending", first.plusSeconds(2), "system");
        start("unknown", first.plusSeconds(3), "unknown");
        String sandbox = start("sandbox", first.plusSeconds(4), "sandbox");
        persist(tenant, event("sandbox", false, first.plusSeconds(121), sandbox, "success", "sandbox"));
        var result = query();
        assertThat(result.counts().getStarted()).isEqualTo(5);
        assertThat(result.counts().getSucceeded()).isEqualTo(1);
        assertThat(result.counts().getFailed()).isEqualTo(1);
        assertThat(result.counts().getCancelled()).isEqualTo(1);
        assertThat(result.counts().getUnresolved()).isEqualTo(2);
        assertThat(result.counts().getExcludedSandbox()).isEqualTo(1);
        assertThat(result.counts().getUnknownPrincipal()).isEqualTo(1);
        assertThat(result.successRate()).isEqualByComparingTo("0.2");
        assertThat(result.completedSuccessRate()).isEqualByComparingTo("0.333333");
    }

    @Test void windowIsHalfOpenAndEarlierStartCannotReenterCohort() {
        start("at-from", first, "system");
        start("at-to", first.plusSeconds(60), "system");
        start("old", first.minusSeconds(1), "system");
        start("old", first.plusSeconds(1), "system");
        assertThat(query().counts().getStarted()).isEqualTo(1);
        assertThat(query().counts().getUnresolved()).isEqualTo(1);
    }

    @Test void otherTenantOrWrongReferenceCannotCompleteRun() {
        String id = start("same-run", first.plusSeconds(1), "system");
        persist(tenant + 1, event("same-run", false, first.plusSeconds(2), id, "success", "system"));
        persist(tenant, event("same-run", false, first.plusSeconds(3), "wrong-event", "success", "system"));
        var wrongTask = event("same-run", false, first.plusSeconds(4), id, "success", "system");
        wrongTask.getProps().put("taskPid", "foreign-task");
        persist(tenant, wrongTask);
        assertThat(query().counts().getStarted()).isEqualTo(1);
        assertThat(query().counts().getSucceeded()).isZero();
        assertThat(query().counts().getUnresolved()).isEqualTo(1);
        assertThat(service.query(tenant + 1, new BehaviorQueryWindow(first, first.plusSeconds(60)), Instant.now())
                .counts().getStarted()).isZero();
    }

    @Test void ingestionOrderDoesNotReplaceEventOrderAndFutureCompletionIsExcluded() {
        var start = event("late-start", true, first.plusSeconds(2), null, null, "system");
        persist(tenant, event("late-start", false, first.plusSeconds(3), start.getEventId(), "success", "system"));
        persist(tenant, start);
        String future = start("future", first.plusSeconds(4), "system");
        persist(tenant, event("future", false, Instant.now().plusSeconds(120), future, "success", "system"));
        String reversed = start("reversed", first.plusSeconds(5), "system");
        persist(tenant, event("reversed", false, first.plusSeconds(1), reversed, "success", "system"));
        assertThat(query().counts().getStarted()).isEqualTo(3);
        assertThat(query().counts().getSucceeded()).isEqualTo(1);
        assertThat(query().counts().getUnresolved()).isEqualTo(2);
    }

    @Test void untrustedOrSampledStartsCannotSupplyDenominator() {
        var sampled = event("sampled", true, first.plusSeconds(1), null, null, "system");
        sampled.setSamplingProbability(new BigDecimal("0.5")); persist(tenant, sampled);
        var client = event("client", true, first.plusSeconds(2), null, null, "system");
        client.setSource("declared"); persist(tenant, client);
        var producer = event("producer", true, first.plusSeconds(3), null, null, "system");
        producer.setProducerName("other-producer"); persist(tenant, producer);
        var target = event("target", true, first.plusSeconds(4), null, null, "system");
        target.getProps().put("targetKey", "other-run"); persist(tenant, target);
        assertThat(query().counts().getStarted()).isZero();
        assertThat(query().successRate()).isNull();
    }
    @Test void duplicateDeliveryIsIdempotentAndDataCutoffExcludesLaterArrival() {
        Instant beforeArrival = Instant.now().minusSeconds(1);
        var start = event("duplicate", true, first.plusSeconds(1), null, null, "system");
        persist(tenant, start);
        persist(tenant, start);
        assertThat(query().counts().getStarted()).isEqualTo(1);
        assertThat(service.query(tenant, new BehaviorQueryWindow(first, first.plusSeconds(60)), beforeArrival)
                .counts().getStarted()).isZero();
    }

}
