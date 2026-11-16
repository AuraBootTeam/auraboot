package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.integration.ReliableIntegrationAccessorImpl;
import com.auraboot.framework.integration.ReliableIntegrationMetrics;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.openplatform.mapper.InstalledIntegrationReceiptMapper;
import com.auraboot.framework.plugin.extension.integration.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, production outbox/receipt mappers and consumer; only metadata and domain commands are fixtures. */
@EnabledIfEnvironmentVariable(named="INGRESS_DEFAULTS_POSTGRES", matches="true")
class InstalledIntegrationTimePrecisionPostgresIT {
    private final InstalledIntegrationServiceTest fixture = new InstalledIntegrationServiceTest();
    private DataSource datasource;
    private TransactionTemplate tx;
    private InstalledIntegrationReceiptMapper receipts;
    private ReliableIntegrationAccessorImpl outbox;
    private InstalledIntegrationService service;

    @BeforeEach void setup() throws Exception {
        fixture.setup();
        String url=System.getenv("TEST_DATABASE_URL");assertNotNull(url);
        var address=java.net.URI.create(url.substring(5));assertEquals("127.0.0.1",address.getHost());assertEquals("/enterprise_1",address.getPath());
        datasource=new DriverManagerDataSource(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"));
        Configuration config=new Configuration(new Environment("owned-time-precision",new SpringManagedTransactionFactory(),datasource));
        config.setMapUnderscoreToCamelCase(true);config.addMapper(InstalledIntegrationReceiptMapper.class);config.addMapper(OutboxEventMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        receipts=session.getMapper(InstalledIntegrationReceiptMapper.class);
        outbox=new ReliableIntegrationAccessorImpl(session.getMapper(OutboxEventMapper.class),fixture.json,mock(ReliableIntegrationMetrics.class),null,mock(org.springframework.context.ApplicationEventPublisher.class));
        tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));
        service=new InstalledIntegrationService(fixture.registry,fixture.handlers,fixture.plugins,fixture.installations,fixture.scopes,
                fixture.commands,fixture.owners,receipts,fixture.executor,fixture.users,fixture.members,fixture.json,new OpenPlatformSecretCodec());
    }
    @AfterEach void clear() { fixture.clear(); }

    @Test void preservesNanosecondsAcrossBothPostgresRoundingDirectionsAndSecondCarry() throws Exception {
        for(String time:List.of("2026-11-16T09:00:00.123456233Z","2026-11-16T09:00:00.123456789Z","2026-11-16T09:00:59.999999789Z")) {
            var original=accept(Instant.parse(time),true);
            var persisted=read(original);assertNotEquals(original.occurredAt(),persisted.occurredAt());
            doAnswer(call->{
                var settings=new HashMap<String,Object>();InstalledIntegrationExecution.install(fixture.process,42L,settings);
                var facts=(InstalledIntegrationEvent)settings.get(InstalledIntegrationEvent.SETTINGS_KEY);
                assertNotNull(facts);assertEquals(original.occurredAt(),facts.occurredAt());
                return CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","completed")).build();
            }).when(fixture.executor).execute(eq(fixture.process),any());
            tx.execute(status->{service.consume(persisted);return null;});
            assertEquals("completed",state(original));
            tx.execute(status->{service.consume(persisted);return null;});
            assertEquals("completed",state(original));
        }
    }
    @Test void rejectsTamperedOrMalformedInternalTimeAndDoesNotCompleteTheReceipt() throws Exception {
        var original=accept(Instant.parse("2026-11-16T09:00:00.123456789Z"),true);var persisted=read(original);
        for(String time:List.of("not-an-instant","2026-11-16T09:00:01.123456789Z")) {
            var headers=new LinkedHashMap<>(persisted.headers());headers.put(InstalledIntegrationService.ORIGINAL_OCCURRED_AT_HEADER,time);
            var altered=copy(persisted,persisted.occurredAt(),headers);
            assertThrows(SecurityException.class,()->tx.execute(status->{service.consume(altered);return null;}));
            assertEquals("accepted",state(original));
        }
        var altered=copy(persisted,persisted.occurredAt().plusSeconds(1),persisted.headers());
        assertThrows(SecurityException.class,()->tx.execute(status->{service.consume(altered);return null;}));
        verify(fixture.executor,never()).execute(eq(fixture.process),any());assertEquals("accepted",state(original));
    }
    @Test void legacyWholeSecondEventsDoNotRequireTheInternalTimeHeader() throws Exception {
        var original=accept(Instant.parse("2026-11-16T09:00:00Z"),false);var persisted=read(original);
        doReturn(CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","completed")).build())
                .when(fixture.executor).execute(eq(fixture.process),any());
        tx.execute(status->{service.consume(persisted);return null;});assertEquals("completed",state(original));
    }
    private IntegrationEventEnvelope accept(Instant time,boolean stamp) {
        var base=fixture.event();String id="time-precision-"+UUID.randomUUID();
        var headers=new LinkedHashMap<>(base.headers());headers.put("externalEventId",id);
        if(stamp)headers.put(InstalledIntegrationService.ORIGINAL_OCCURRED_AT_HEADER,time.toString());
        var event=new IntegrationEventEnvelope(base.schemaVersion(),"ext:inst:"+fixture.source+":"+id,base.eventType(),base.source(),base.subject(),time,
                base.tenantId(),base.correlationId(),base.causationId(),base.orderingKey(),base.sequence(),base.payload(),headers);
        doReturn(CommandExecuteResult.builder().phaseReached("completed").data(Map.of("recordPid",id,"state","accepted")).build())
                .when(fixture.executor).execute(eq(fixture.accept),any());
        tx.execute(status->{assertFalse(service.accept(fixture.principal,fixture.source,event).duplicate());outbox.enqueue(event);return null;});
        return event;
    }
    private IntegrationEventEnvelope read(IntegrationEventEnvelope original) throws Exception {
        try(var connection=datasource.getConnection();var query=connection.prepareStatement("SELECT occurred_at,headers::text FROM ab_outbox WHERE tenant_id=? AND event_id=?")) {
            query.setLong(1,original.tenantId());query.setString(2,original.eventId());
            try(var row=query.executeQuery()) {
                assertTrue(row.next());Instant time=row.getTimestamp(1).toInstant();
                Map<String,String> headers=fixture.json.readValue(row.getString(2),new com.fasterxml.jackson.core.type.TypeReference<>(){});
                assertFalse(row.next());return copy(original,time,headers);
            }
        }
    }
    private String state(IntegrationEventEnvelope event) {
        return tx.execute(status->receipts.find(event.tenantId(),"inst",fixture.source,event.headers().get("externalEventId")).state());
    }
    private IntegrationEventEnvelope copy(IntegrationEventEnvelope event,Instant time,Map<String,String> headers) {
        return new IntegrationEventEnvelope(event.schemaVersion(),event.eventId(),event.eventType(),event.source(),event.subject(),time,event.tenantId(),
                event.correlationId(),event.causationId(),event.orderingKey(),event.sequence(),event.payload(),headers);
    }
}
