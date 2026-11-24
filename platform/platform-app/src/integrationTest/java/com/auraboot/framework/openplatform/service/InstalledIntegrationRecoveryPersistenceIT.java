package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.openplatform.entity.ApplicationInstallation;
import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.Registration;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;


import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.concurrent.*;
import static com.auraboot.framework.openplatform.service.InstalledIntegrationRecoveryContracts.*;

/** Real PostgreSQL transactions and locks; metadata/provider dependencies are controlled fixtures.
 * Actual JWT/role admission and Kingdee business effects are qualified separately on the deployed target.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=InstalledIntegrationRecoveryPersistenceIT.Fixture.class)
class InstalledIntegrationRecoveryPersistenceIT {
    @Autowired InstalledIntegrationService integration;
    @Autowired InstalledIntegrationRecoveryService recovery;
    @Autowired NativeProviderFixture fixture;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionTemplate sessions;
    Map<String,String> initial;
    IntegrationEventEnvelope seed() throws Exception {
        if(initial==null){initial=new HashMap<>();fixture.metadata.forEach((k,v)->initial.put(k,v.getExecutionConfig()));}
        fixture.metadata.forEach((k,v)->v.setExecutionConfig(initial.get(k)));
        String old="sha256:"+"a".repeat(64);
        when(fixture.registry.registration(fixture.source)).thenReturn(Optional.of(Map.entry(fixture.registration,old)));
        when(fixture.plugins.observeLoadedArtifact(any())).thenReturn(Optional.of(new AuraPluginManager.LoadedPluginArtifact(
                fixture.registration.providerPluginId(),java.nio.file.Path.of("old-fixture.jar"),old)));
        String id="recovery-it-"+UUID.randomUUID();String request="request-"+UUID.randomUUID();
        var original=fixture.event();var headers=new HashMap<>(original.headers());headers.put("externalEventId",id);
        var event=new IntegrationEventEnvelope("1.0","ext:inst:"+fixture.source+":"+id,original.eventType(),original.source(),
                original.subject(),original.occurredAt(),42,original.correlationId(),null,original.orderingKey(),0,original.payload(),headers);
        when(fixture.executor.execute(eq(fixture.accept),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid",request,"state","accepted")).build());
        integration.accept(fixture.principal,fixture.source,event);
        Long outbox=jdbc.queryForObject("""
            INSERT INTO ab_outbox(tenant_id,event_id,event_type,payload,status,retry_count,max_retries,
              schema_version,event_source,subject,occurred_at,correlation_id,ordering_key,event_sequence,headers,replay_count)
            VALUES(42,?,?,?::jsonb,'failed',1,1,'1.0',?,?,?::timestamptz,?,?,0,?::jsonb,0) RETURNING id
            """,Long.class,event.eventId(),event.eventType(),fixture.json.writeValueAsString(event.payload()),event.source(),
                event.subject(),event.occurredAt().toString(),event.correlationId(),event.orderingKey(),fixture.json.writeValueAsString(event.headers()));
        jdbc.update("INSERT INTO ab_integration_dead_letter(outbox_id,tenant_id,event_id,event_type,payload,error_detail,failed_at,status) VALUES(?,42,?,?,?::jsonb,'old-provider-failure',NOW(),'open')",
                outbox,event.eventId(),event.eventType(),fixture.json.writeValueAsString(event.payload()));
        fixture.upgradeProvider();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.json.readTree(fixture.metadata.get(fixture.process).getExecutionConfig());
        var policy=((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("handlerParams")).putObject("installedMachineData").putObject("prod_product");
        policy.putArray("read").add("pid").add("prod_code");policy.putArray("create").add("prod_code");
        fixture.metadata.get(fixture.process).setExecutionConfig(tree.toString());
        return event;
    }
    Decision decision(IntegrationEventEnvelope e,String reason) {
        String id=e.headers().get("externalEventId");var p=recovery.plan(42,"inst",fixture.source,id);
        return new Decision(p.requestPid(),p.envelopeSha256(),p.bindingSha256(),p.previousCommandSha256(),p.previousProviderSha256(),
                p.commandSha256(),p.providerSha256(),p.expectedReplayCount(),reason);
    }
    String storedCommand(IntegrationEventEnvelope e){return jdbc.queryForObject("SELECT command_sha256 FROM ab_installed_integration_receipt WHERE tenant_id=42 AND durable_event_id=?",String.class,e.eventId());}
    int audits(IntegrationEventEnvelope e){return jdbc.queryForObject("SELECT count(*) FROM ab_installed_integration_recovery WHERE tenant_id=42 AND durable_event_id=?",Integer.class,e.eventId());}

    @Test void compareAndSwapRollbackConcurrencyAndExactRetryUseRealStorage() throws Exception {
        var e=seed();String id=e.headers().get("externalEventId");var d=decision(e,"Reviewed exact local fixture upgrade");
        assertThrows(SecurityException.class,()->integration.consume(e));
        assertEquals(d.previousCommandSha256(),storedCommand(e));
        var result=recovery.authorize(42,"inst",fixture.source,id,"native-operator",d);
        assertEquals(d.commandSha256(),storedCommand(e));assertEquals(1,audits(e));
        assertEquals(result,recovery.authorize(42,"inst",fixture.source,id,"native-operator",d));assertEquals(1,audits(e));
        assertEquals("failed",jdbc.queryForObject("SELECT status FROM ab_outbox WHERE tenant_id=42 AND event_id=?",String.class,e.eventId()),"Authorization must not silently enqueue");
        var previousPayload=jdbc.queryForObject("SELECT payload::text FROM ab_outbox WHERE tenant_id=42 AND event_id=?",String.class,e.eventId());
        assertThrows(NoSuchElementException.class,()->recovery.plan(43,"inst",fixture.source,id));
        assertThrows(NoSuchElementException.class,()->recovery.authorize(43,"inst",fixture.source,id,"native-operator",d));
        when(fixture.executor.execute(eq(fixture.process),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","completed")).build());
        clearInvocations(fixture.executor);integration.consume(e);integration.consume(e);
        verify(fixture.executor,times(1)).execute(eq(fixture.process),any());
        assertEquals("completed",jdbc.queryForObject("SELECT state FROM ab_installed_integration_receipt WHERE tenant_id=42 AND durable_event_id=?",String.class,e.eventId()));
        assertEquals(result,recovery.authorize(42,"inst",fixture.source,id,"native-operator",d));
        assertEquals(previousPayload,jdbc.queryForObject("SELECT payload::text FROM ab_outbox WHERE tenant_id=42 AND event_id=?",String.class,e.eventId()));

        var failed=seed();var rejected=decision(failed,"native-audit-insert-failure");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='it_receipt_recovery_audit_failure'",Integer.class));
        jdbc.execute("ALTER TABLE ab_installed_integration_recovery ADD CONSTRAINT it_receipt_recovery_audit_failure CHECK(reason<>'native-audit-insert-failure')");
        try {
            assertThrows(RuntimeException.class,()->recovery.authorize(42,"inst",fixture.source,failed.headers().get("externalEventId"),"native-operator",rejected));
            assertEquals(rejected.previousCommandSha256(),storedCommand(failed),"Audit failure must roll back the prior UPDATE");assertEquals(0,audits(failed));
        } finally {jdbc.execute("ALTER TABLE ab_installed_integration_recovery DROP CONSTRAINT it_receipt_recovery_audit_failure");}

        var concurrent=seed();var chosen=decision(concurrent,"Concurrent reviewed upgrade");String key=concurrent.headers().get("externalEventId");
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var calls=new ArrayList<Future<String>>();
            for(String actor:List.of("operator-a","operator-b"))calls.add(pool.submit(()->{
                start.await();try {recovery.authorize(42,"inst",fixture.source,key,actor,chosen);return actor;}
                catch(IllegalStateException conflict){return "conflict";}
            }));
            start.countDown();var outcomes=List.of(calls.get(0).get(20,TimeUnit.SECONDS),calls.get(1).get(20,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("conflict"::equals).count());assertEquals(1,audits(concurrent));
            String actor=outcomes.stream().filter(x->!x.equals("conflict")).findFirst().orElseThrow();
            recovery.authorize(42,"inst",fixture.source,key,actor,chosen);assertEquals(1,audits(concurrent));
            var altered=new Decision(chosen.requestPid(),chosen.envelopeSha256(),chosen.bindingSha256(),chosen.previousCommandSha256(),chosen.previousProviderSha256(),
                    chosen.commandSha256(),chosen.providerSha256(),chosen.expectedReplayCount(),"another reason");
            assertThrows(IllegalStateException.class,()->recovery.authorize(42,"inst",fixture.source,key,actor,altered));
        } finally {pool.shutdownNow();}
    }

    @Configuration @EnableTransactionManagement
    static class Fixture {
        @Bean DataSource dataSource(){
            String url=System.getenv("TEST_DATABASE_URL");
            if(url==null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/(auraquote_[0-9]+|kd_receipt_recovery_[a-z0-9_]+)(\\?.*)?"))
                throw new IllegalStateException("Explicit owned loopback migrated test database required; never skip");
            return new DriverManagerDataSource(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean SqlSessionFactory factory(DataSource ds) throws Exception {
            var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(InstalledIntegrationReceiptMapper.class);config.addMapper(InstalledIntegrationRecoveryMapper.class);
            var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);return factory.getObject();
        }
        @Bean SqlSessionTemplate sessions(SqlSessionFactory f){return new SqlSessionTemplate(f);}
        @Bean InstalledIntegrationReceiptMapper receipts(SqlSessionTemplate s){return s.getMapper(InstalledIntegrationReceiptMapper.class);}
        @Bean InstalledIntegrationRecoveryMapper recoveryMapper(SqlSessionTemplate s){return s.getMapper(InstalledIntegrationRecoveryMapper.class);}
        @Bean NativeProviderFixture fixture() throws Exception {var f=new NativeProviderFixture();f.setup();return f;}
        @Bean InstalledIntegrationBindingLookup bindingLookup(){return mock(InstalledIntegrationBindingLookup.class);}
        @Bean InstalledIntegrationService integration(NativeProviderFixture f,InstalledIntegrationReceiptMapper r){
            return new InstalledIntegrationService(f.registry,f.handlers,f.plugins,f.installations,f.scopes,f.commands,f.owners,r,
                    f.executor,f.users,f.members,f.json,new OpenPlatformSecretCodec());
        }
        @Bean InstalledIntegrationRecoveryService recovery(InstalledIntegrationService s,InstalledIntegrationReceiptMapper r,
                                                          InstalledIntegrationRecoveryMapper m,NativeProviderFixture f){
            return new InstalledIntegrationRecoveryService(s,r,m,f.json);
        }
    }
    static class NativeProviderFixture {
    final InstalledIntegrationRegistry registry = mock(InstalledIntegrationRegistry.class);
    final com.auraboot.framework.plugin.pf4j.ExtensionRegistry handlers=mock(com.auraboot.framework.plugin.pf4j.ExtensionRegistry.class);
    final AuraPluginManager plugins=mock(AuraPluginManager.class);
    final ApplicationInstallationMapper installations = mock(ApplicationInstallationMapper.class);
    final ApplicationScopeGrantMapper scopes = mock(ApplicationScopeGrantMapper.class);
    final CommandDefinitionMapper commands = mock(CommandDefinitionMapper.class);
    final PluginRecordMapper owners = mock(PluginRecordMapper.class);
    final InstalledIntegrationReceiptMapper receipts = mock(InstalledIntegrationReceiptMapper.class);
    final CommandExecutor executor = mock(CommandExecutor.class);
    final com.auraboot.framework.user.service.UserService users = mock(com.auraboot.framework.user.service.UserService.class);
    final com.auraboot.framework.tenant.service.TenantMemberService members = mock(com.auraboot.framework.tenant.service.TenantMemberService.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final String source="kingdee-procurement", model="qo_procurement_inquiry_request_common";
    final String accept=model+":accept", process=model+":process", result=model+":result";
    final Registration registration = new Registration(source,"procurement.inquiry.ingress",
            Set.of("external."+source+".inquiry.requested.v1"),"procurement.inquiries.write","qo.procurement.ingress",
            "com.auraboot.quote-engine","com.auraboot.quote-procurement",accept,process,result,"ownerUserPid");
    final MachinePrincipal principal = new MachinePrincipal(42L,"token","app",Set.of(registration.requiredScope()),
            "app","inst","test","token");
    InstalledIntegrationService service;
    final Map<String,CommandDefinition> metadata = new HashMap<>();

    void setup() throws Exception {
        service = new InstalledIntegrationService(registry,handlers,plugins,installations,scopes,commands,owners,receipts,executor,users,members,json,new OpenPlatformSecretCodec());
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(registration,"sha256:"+"a".repeat(64))));
        when(registry.completedReceiptProcessReadFieldAdditions(eq(registration),anyString())).thenReturn(Map.of());
        var install=new ApplicationInstallation(); install.setId(9L); install.setPid("inst");install.setTenantId(42L);install.setStatus("active");
        when(installations.findByTenantAndPid(42L,"inst")).thenReturn(install);
        when(installations.findActiveIdentity(42L,"inst")).thenReturn(new ApplicationInstallationMapper.ActiveIdentity(9L,"app"));
        when(scopes.findScopes(42L,9L)).thenReturn(Set.of(registration.requiredScope()));
        var owner=new PluginRecord();owner.setTenantId(42L);owner.setPid("owner");owner.setDeletedFlag(false);owner.setPluginId(registration.commandOwnerPluginId());
        var manifest=new PluginManifest();manifest.setDependencies(new ArrayList<>(List.of(registration.providerPluginId())));owner.setManifest(manifest);
        when(owners.findByPid("owner")).thenReturn(owner);
        var user=new com.auraboot.framework.user.dao.entity.User();user.setId(7L);user.setPid("buyer");
        when(users.findByPid("buyer")).thenReturn(user);
        var member=new com.auraboot.framework.tenant.dao.entity.TenantMember();member.setUserId(7L);member.setTenantId(42L);member.setStatus("active");
        when(members.findByTenantIdAndUserId(42L,7L)).thenReturn(member);
        var binding=Map.of("sourceCode",source,"capability",registration.capability(),"bindings",List.of(
                Map.of("installationPid","inst","revision",1,"parameters",Map.of("connectionPid","connection","ownerUserPid","buyer"))));
        for(String code:List.of(accept,process,result)) {
            var command=new CommandDefinition();command.setCode(code);command.setModelCode(model);command.setPluginPid("owner");
            command.setTenantId(42L);command.setIsCurrent(true);command.setDeletedFlag(false);command.setStatus("published");
            command.setExecutionConfig(json.writeValueAsString(Map.of("handler",code,"permissions",List.of(registration.commandPermission()),
                    "handlerParams",Map.of("installedIntegration",binding))));metadata.put(code,command);
            when(commands.findCurrentByCode(code)).thenReturn(command);
            var handler=mock(com.auraboot.framework.plugin.extension.CommandHandlerExtension.class);
            when(handlers.getCommandHandler(code)).thenReturn(Optional.of(handler));
            when(handlers.getSecondaryCommandHandlers(code)).thenReturn(List.of());
            when(plugins.observeLoadedArtifact(handler.getClass())).thenReturn(Optional.of(new AuraPluginManager.LoadedPluginArtifact(
                    "com.auraboot.quote-engine",java.nio.file.Path.of("provider.jar"),"sha256:"+"a".repeat(64))));
        }
        when(receipts.claim(any())).thenReturn(1);
        when(receipts.accepted(eq(42L),eq("inst"),eq(source),eq("evt-1"),eq("request-pid"))).thenReturn(1);
        when(receipts.completed(eq(42L),eq("inst"),eq(source),eq("evt-1"),eq("request-pid"))).thenReturn(1);
        when(executor.execute(eq(accept),any())).thenAnswer(call -> {
            assertEquals(0L,MetaContext.getCurrentUserId()); assertEquals("installation:inst",MetaContext.getCurrentUserPid());
            assertEquals(registration.commandPermission(),MetaContext.getExternalCommandPermission());
            var boundary = new com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase(
                mock(com.auraboot.framework.permission.service.UserPermissionService.class),
                mock(com.auraboot.framework.permission.service.RecordShareService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
            var pipeline = com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.builder()
                .commandCode(accept).tenantId(42L).userId(0L).command(metadata.get(accept))
                .execConfig(json.readValue(metadata.get(accept).getExecutionConfig(),Map.class)).build();
            boundary.execute(pipeline);assertTrue(pipeline.getAuthorizationVerdict().isAuthorized());
            Map<String,Object> settings=new HashMap<>();InstalledIntegrationExecution.install(accept,42L,settings);
            var installed=(InstalledIntegrationBinding)settings.get(InstalledIntegrationBinding.SETTINGS_KEY);
            assertEquals("connection",installed.parameters().get("connectionPid"));assertEquals("evt-1",installed.externalEventId());
            var eventFacts=(InstalledIntegrationEvent)settings.get(InstalledIntegrationEvent.SETTINGS_KEY);
            assertNotNull(eventFacts);assertEquals(event().eventType(),eventFacts.eventType());
            assertEquals(event().sequence(),eventFacts.sequence());assertEquals(event().occurredAt(),eventFacts.occurredAt());
            assertEquals("purchase-requisition",eventFacts.subjectType());assertEquals("10",eventFacts.subjectPid());
            return CommandExecuteResult.builder().phaseReached("completed").data(Map.of("recordPid","request-pid","state","accepted")).build();
        });
    }
    IntegrationEventEnvelope event() { return event(Map.of("title","Inquiry","selected",List.of(Map.of("requisitionId","10","entryId","11")))); }
    IntegrationEventEnvelope event(Map<String,Object> payload) {
        return new IntegrationEventEnvelope("1.0","ext:inst:"+source+":evt-1","external."+source+".inquiry.requested.v1",
                "open-platform/inst/"+source,"purchase-requisition/10",Instant.parse("2026-10-16T00:00:00Z"),42L,"trace",null,"purchase-requisition/10",0,payload,
                Map.of("applicationPid","app","installationPid","inst","sourceCode",source,"externalEventId","evt-1","externalSubjectType","purchase-requisition","externalSubjectPid","10"));
    }
    void upgradeProvider() {
        String digest="sha256:"+"b".repeat(64);
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(registration,digest)));
        when(plugins.observeLoadedArtifact(any())).thenReturn(Optional.of(new AuraPluginManager.LoadedPluginArtifact(
                registration.providerPluginId(),java.nio.file.Path.of("provider-new.jar"),digest)));
    }

    }

}
