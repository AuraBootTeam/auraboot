package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.DeclaredTestInfrastructure;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.mapper.NotificationSendLogMapper;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.*;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real migrated ledger and loopback protocol; synthetic authorization is explicit, never a real WeChat grant. */
@SpringBootTest(classes=TestApplication.class,properties={"spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.data.redis.database=${TEST_REDIS_DATABASE}","security.field-encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("integration-test")
@Import(WechatSubscriptionDeliveryPostgresIT.PollerIsolation.class)
class WechatSubscriptionDeliveryPostgresIT {
    @TestConfiguration(proxyBeanMethods=false)
    static class PollerIsolation {
        @Bean static BeanFactoryPostProcessor disableAutomaticPollers(){return factory->{
            var registry=(BeanDefinitionRegistry)factory;
            String name=TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
            if(registry.containsBeanDefinition(name))registry.removeBeanDefinition(name);
        };}
    }
    @MockitoBean SchedulerEngine scheduler;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired RedisProperties redisProperties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationSendLogMapper ledger;
    @Autowired NotificationMapper recipients;
    @Autowired AuthIdentityMapper identities;
    @Autowired OutboxEventMapper outbox;
    @Autowired ReliableIntegrationAccessor reliable;
    @Autowired ReliableEventDeliveryExecutor executor;
    @Autowired ObjectMapper json;
    private final Map<String,WechatSubscriptionGrantResolver.Grant> facts=new ConcurrentHashMap<>();
    private final List<Long> tenants=new ArrayList<>(),users=new ArrayList<>(),members=new ArrayList<>(),identityIds=new ArrayList<>();
    private long tenant,user,foreignTenant;
    private String userPid,identityPid,openid,appId;
    private HttpServer server;
    private ExecutorService serverThreads;
    private final AtomicInteger tokenCalls=new AtomicInteger(),sendCalls=new AtomicInteger();
    private volatile long responseDelay;
    private volatile String tokenResponse="{\"access_token\":\"synthetic-native-token\",\"expires_in\":7200}";
    private volatile String sendResponse="{\"errcode\":0}";
    private WechatSubscriptionProperties settings;
    private WechatMiniProperties mini;
    private WechatSubscriptionGrantResolver authority;

    @BeforeEach void fixture() throws Exception {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        DeclaredTestInfrastructure.assertSelected(source, redisProperties);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='ab_notification_send_log' AND column_name='delivery_key'",Integer.class)).isEqualTo(1);
        tenant=tenant();foreignTenant=tenant();userPid=UniqueIdGenerator.generate();user=IdWorker.getId();
        jdbc.update("INSERT INTO ab_user(id,pid,user_name,deleted_flag) VALUES(?,?,?,FALSE)",user,userPid,"s06-durable-"+userPid);users.add(user);
        long member=IdWorker.getId();jdbc.update("INSERT INTO ab_tenant_member(id,pid,tenant_id,user_id,status,deleted_flag) VALUES(?,?,?,?,'active',FALSE)",member,UniqueIdGenerator.generate(),tenant,user);members.add(member);
        identityPid=UniqueIdGenerator.generate();openid="synthetic-"+UniqueIdGenerator.generate();appId="synthetic-"+UniqueIdGenerator.generate();
        long identity=jdbc.queryForObject("INSERT INTO ab_auth_identity(pid,user_id,provider,app_id,openid) VALUES(?,?,'wechat_mini',?,?) RETURNING id",Long.class,identityPid,user,appId,openid);identityIds.add(identity);
        MetaContext.setContext(tenant,user,userPid,"S06 durable fixture");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);serverThreads=Executors.newCachedThreadPool();server.setExecutor(serverThreads);
        server.createContext("/cgi-bin/stable_token",exchange->{tokenCalls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] body=tokenResponse.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/cgi-bin/message/subscribe/send",exchange->{sendCalls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            try {if(responseDelay>0)Thread.sleep(responseDelay);byte[] body=sendResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
              catch(java.io.IOException expectedTimeout){ /* Actual client timeout after the provider observed the message. */ }
            finally{exchange.close();}
        });server.start();
        mini=new WechatMiniProperties();mini.setAppId(appId);mini.setAppSecret("synthetic-native-secret");mini.setApiBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        settings=new WechatSubscriptionProperties();settings.setEnabled(true);settings.setAllowLoopback(true);settings.setTemplateId("synthetic-template");
        settings.setMiniprogramState("developer");settings.setFieldMaxLengths(Map.of("thing1",20));settings.setReadTimeoutMs(1000);
        authority=new WechatSubscriptionGrantResolver(){
            public Optional<Grant> claim(long id,String ref){return Optional.ofNullable(facts.get(ref));}
            public Optional<Grant> verifyReserved(long id,String ref,String reservation){return Optional.ofNullable(facts.get(ref))
                    .filter(g->g.tenantId()==id && g.reservationReference().equals(reservation));}
        };
    }
    @AfterEach void cleanup() throws Exception {
        MetaContext.clear();if(server!=null)server.stop(0);
        if(serverThreads!=null){serverThreads.shutdownNow();assertThat(serverThreads.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
        for(long id:tenants){jdbc.update("DELETE FROM ab_notification_send_log WHERE tenant_id=?",id);jdbc.update("DELETE FROM ab_integration_receipt WHERE tenant_id=?",id);jdbc.update("DELETE FROM ab_outbox WHERE tenant_id=?",id);}
        for(long id:identityIds)jdbc.update("DELETE FROM ab_auth_identity WHERE id=?",id);
        for(long id:members)jdbc.update("DELETE FROM ab_tenant_member WHERE id=?",id);
        for(long id:users)jdbc.update("DELETE FROM ab_user WHERE id=?",id);
        for(long id:tenants)jdbc.update("DELETE FROM ab_tenant WHERE id=?",id);
    }
    private long tenant(){long id=IdWorker.getId();String pid=UniqueIdGenerator.generate();jdbc.update("INSERT INTO ab_tenant(id,pid,name,status,deleted_flag) VALUES(?,?,?,'active',FALSE)",id,pid,"S06 durable "+pid);tenants.add(id);return id;}
    private WechatSubscriptionDeliveryJournal fresh(NotificationSendLogMapper mapper){return new WechatSubscriptionDeliveryJournal(mapper,reliable,
            new WechatSubscriptionMessageSender(settings,mini,new WechatMiniStableTokenClient(mini),List.of(authority),recipients,identities),transactions);}
    private WechatSubscriptionDeliveryJournal fresh(){return fresh(ledger);}
    private WechatSubscriptionMessageSender.Request request(String kind){String ref=UniqueIdGenerator.generate()+"-"+kind;
        facts.put(ref,new WechatSubscriptionGrantResolver.Grant(tenant,ref,userPid,appId,identityPid,openid,settings.getTemplateId(),
                "synthetic-grant-"+ref,"synthetic-reservation-"+ref,Instant.now().plusSeconds(3600),Map.of("thing1","Synthetic reminder"),Instant.now()));
        return new WechatSubscriptionMessageSender.Request(tenant,ref);}
    private WechatSubscriptionDeliveryJournal.QueueResult queue(WechatSubscriptionDeliveryJournal journal,WechatSubscriptionMessageSender.Request request){return new TransactionTemplate(transactions).execute(status->journal.enqueue(request));}
    private IntegrationEventEnvelope event(String key){return jdbc.queryForObject("SELECT * FROM ab_outbox WHERE tenant_id=? AND event_id=?",(r,i)->{
        try{return new IntegrationEventEnvelope(r.getString("schema_version"),r.getString("event_id"),r.getString("event_type"),r.getString("event_source"),r.getString("subject"),r.getTimestamp("occurred_at").toInstant(),r.getLong("tenant_id"),r.getString("correlation_id"),r.getString("causation_id"),r.getString("ordering_key"),r.getLong("event_sequence"),json.readValue(r.getString("payload"),new TypeReference<Map<String,Object>>(){}),json.readValue(r.getString("headers"),new TypeReference<Map<String,String>>(){}));}
        catch(java.io.IOException invalid){throw new IllegalStateException("invalid native fixture envelope");}
    },tenant,key);}
    private String materialize(WechatSubscriptionDeliveryJournal journal,WechatSubscriptionMessageSender.Request request){String key=queue(journal,request).deliveryKey();executor.deliver(event(key),journal,UniqueIdGenerator.generate());return key;}
    private Map<String,Object> raw(String key){return jdbc.queryForMap("SELECT * FROM ab_notification_send_log WHERE tenant_id=? AND delivery_key=?",tenant,key);}
    private int count(String table){if(!Set.of("ab_notification_send_log","ab_integration_receipt","ab_outbox").contains(table))throw new IllegalArgumentException();return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);}
    private <T> List<T> concurrent(Callable<T> action) throws Exception {
        var workers=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
        try {Callable<T> run=()->{MetaContext.setContext(tenant,user,userPid,"S06 concurrent native");try{ready.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");return action.call();}finally{MetaContext.clear();}};
            var first=workers.submit(run);var second=workers.submit(run);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();release.countDown();return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        } finally {release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void concurrentQueueAndRestartedConsumersKeepOneIntentAndOneProviderSend() throws Exception {
        var request=request("concurrent");var journal=fresh();var results=concurrent(()->queue(fresh(),request));
        assertThat(results).extracting(WechatSubscriptionDeliveryJournal.QueueResult::status)
                .containsExactlyInAnyOrder(WechatSubscriptionDeliveryJournal.Status.QUEUED,WechatSubscriptionDeliveryJournal.Status.REPLAYED);
        String key=results.getFirst().deliveryKey();assertThat(count("ab_outbox")).isEqualTo(1);
        var event=event(key);concurrent(()->{executor.deliver(event,fresh(),UniqueIdGenerator.generate());return true;});
        assertThat(count("ab_notification_send_log")).isEqualTo(1);assertThat(count("ab_integration_receipt")).isEqualTo(1);
        concurrent(()->fresh().attempt(tenant,key).getStatus());
        assertThat(raw(key).get("status")).isEqualTo("accepted");assertThat(sendCalls).hasValue(1);assertThat(tokenCalls).hasValue(1);
        var before=raw(key);assertThat(fresh().attempt(tenant,key).getStatus()).isEqualTo("accepted");assertThat(raw(key)).isEqualTo(before);assertThat(sendCalls).hasValue(1);
        var original=facts.get(request.resolutionReference());facts.put(request.resolutionReference(),new WechatSubscriptionGrantResolver.Grant(tenant,original.resolutionReference(),userPid,appId,identityPid,openid,original.templateId(),original.grantReference(),original.reservationReference(),original.expiresAt(),Map.of("thing1","Changed reminder"),original.reservedAt()));
        assertThatThrownBy(()->queue(journal,request)).hasMessage("RELIABLE_ENVELOPE_CONFLICT");assertThat(raw(key)).isEqualTo(before);assertThat(sendCalls).hasValue(1);
    }
    @Test void intentAndReceiptRollbackTogetherWithoutAnyExternalHttp() {
        var request=request("atomic");var journal=fresh();String key=queue(journal,request).deliveryKey();var event=event(key);
        var crash=new ReliableEventConsumerExtension(){public String consumerCode(){return journal.consumerCode();}public Set<String> subscribedEventTypes(){return journal.subscribedEventTypes();}
            public void consume(IntegrationEventEnvelope input){journal.consume(input);assertThat(count("ab_notification_send_log")).isEqualTo(1);throw new IllegalStateException("synthetic crash after intent");}};
        assertThatThrownBy(()->executor.deliver(event,crash,"native-crash-fence")).hasMessage("synthetic crash after intent");
        assertThat(count("ab_notification_send_log")).isZero();assertThat(count("ab_integration_receipt")).isZero();assertThat(tokenCalls).hasValue(0);assertThat(sendCalls).hasValue(0);
        executor.deliver(event,journal,"native-recovery-fence");assertThat(count("ab_notification_send_log")).isEqualTo(1);assertThat(count("ab_integration_receipt")).isEqualTo(1);
    }
    @Test void timeoutAfterProviderObservedRequestIsDurableUnknownAndCannotReopen() {
        settings.setReadTimeoutMs(100);responseDelay=500;var journal=fresh();String key=materialize(journal,request("timeout"));
        assertThat(journal.attempt(tenant,key).getStatus()).isEqualTo("unknown");assertThat(sendCalls).hasValue(1);
        var before=raw(key);assertThat(fresh().attempt(tenant,key).getStatus()).isEqualTo("unknown");assertThat(raw(key)).isEqualTo(before);assertThat(sendCalls).hasValue(1);
        assertThatThrownBy(()->jdbc.update("UPDATE ab_notification_send_log SET status='pending' WHERE tenant_id=? AND delivery_key=?",tenant,key)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(raw(key)).isEqualTo(before);assertThat(tokenCalls).hasValue(1);
    }
    @Test void actualFinishCheckFailureAfterProviderAcceptanceCannotResendAcrossRestart() {
        NotificationSendLogMapper fault=(NotificationSendLogMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{NotificationSendLogMapper.class},(proxy,method,args)->{
            Object[] parameters=args;if(method.getName().equals("finishDurable")){parameters=args.clone();parameters[4]="invalid_state";}
            try{return method.invoke(ledger,parameters);}catch(InvocationTargetException error){throw error.getCause();}
        });
        var journal=fresh(fault);String key=materialize(journal,request("finish-failure"));
        assertThatThrownBy(()->journal.attempt(tenant,key)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(raw(key).get("status")).isEqualTo("attempting");assertThat(sendCalls).hasValue(1);
        assertThat(fresh().attempt(tenant,key).getStatus()).isEqualTo("attempting");assertThat(sendCalls).hasValue(1);
        jdbc.update("UPDATE ab_notification_send_log SET attempt_lease_until=clock_timestamp()-INTERVAL '1 second' WHERE tenant_id=? AND delivery_key=?",tenant,key);
        assertThat(fresh().attempt(tenant,key).getStatus()).isEqualTo("unknown");assertThat(sendCalls).hasValue(1);
    }
    @Test void tokenFailureWrongFenceAndForeignTenantRemainDistinctAndLegacyRowsStillWork() {
        var journal=fresh();var inactive=request("inactive");
        jdbc.update("UPDATE ab_tenant_member SET status='inactive' WHERE tenant_id=? AND user_id=?",tenant,user);
        assertThat(queue(journal,inactive).reason()).isEqualTo(WechatSubscriptionMessageSender.Reason.RECIPIENT_UNAVAILABLE);
        assertThat(count("ab_outbox")).isZero();assertThat(sendCalls).hasValue(0);assertThat(tokenCalls).hasValue(0);
        jdbc.update("UPDATE ab_tenant_member SET status='active' WHERE tenant_id=? AND user_id=?",tenant,user);
        String foreignRef=UniqueIdGenerator.generate();facts.put(foreignRef,new WechatSubscriptionGrantResolver.Grant(
                foreignTenant,foreignRef,userPid,appId,identityPid,openid,settings.getTemplateId(),"synthetic-foreign-grant",
                "synthetic-foreign-reservation",Instant.now().plusSeconds(3600),Map.of("thing1","Synthetic reminder"),Instant.now()));
        MetaContext.setContext(foreignTenant,user,userPid,"S06 foreign member counterexample");
        try {
            assertThat(queue(journal,new WechatSubscriptionMessageSender.Request(foreignTenant,foreignRef)).reason())
                    .isEqualTo(WechatSubscriptionMessageSender.Reason.RECIPIENT_UNAVAILABLE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_outbox WHERE tenant_id=?",Integer.class,foreignTenant)).isZero();
        } finally {MetaContext.setContext(tenant,user,userPid,"S06 durable fixture");}
        assertThat(tokenCalls).hasValue(0);assertThat(sendCalls).hasValue(0);
        String key=materialize(journal,request("token-failure"));tokenResponse="{\"errcode\":1}";
        assertThat(journal.attempt(tenant,key).getStatus()).isEqualTo("not_attempted");assertThat(sendCalls).hasValue(0);assertThat(tokenCalls).hasValue(1);
        assertThat(ledger.finishDurable(tenant,key,(String)raw(key).get("request_hash"),UUID.randomUUID().toString(),"accepted",0L,null)).isZero();
        var before=raw(key);assertThatThrownBy(()->fresh().attempt(foreignTenant,key)).hasMessage("NOTIFICATION_TENANT_MISMATCH");assertThat(raw(key)).isEqualTo(before);
        assertThat(fresh().attempt(tenant,key).getStatus()).isEqualTo("not_attempted");assertThat(tokenCalls).hasValue(1);
        assertThat(jdbc.update("INSERT INTO ab_notification_send_log(tenant_id,channel,recipient,status,content) VALUES(?,'email',?,'sent','synthetic legacy log')",tenant,userPid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_notification_send_log WHERE tenant_id=? AND delivery_key IS NULL AND channel='email' AND status='sent'",Integer.class,tenant)).isEqualTo(1);
    }
}
