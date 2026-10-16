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

class InstalledIntegrationServiceTest {
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

    @BeforeEach void setup() throws Exception {
        service = new InstalledIntegrationService(registry,handlers,plugins,installations,scopes,commands,owners,receipts,executor,users,members,json,new OpenPlatformSecretCodec());
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(registration,"sha256:"+"a".repeat(64))));
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
            return CommandExecuteResult.builder().phaseReached("completed").data(Map.of("recordPid","request-pid","state","accepted")).build();
        });
    }
    @AfterEach void clear() { MetaContext.clear(); }
    IntegrationEventEnvelope event() { return event(Map.of("title","Inquiry","selected",List.of(Map.of("requisitionId","10","entryId","11")))); }
    IntegrationEventEnvelope event(Map<String,Object> payload) {
        return new IntegrationEventEnvelope("1.0","ext:inst:"+source+":evt-1","external."+source+".inquiry.requested.v1",
                "open-platform/inst/"+source,"purchase-requisition/10",Instant.parse("2026-10-16T00:00:00Z"),42L,"trace",null,"purchase-requisition/10",0,payload,
                Map.of("applicationPid","app","installationPid","inst","sourceCode",source,"externalEventId","evt-1"));
    }
    InstalledIntegrationReceiptMapper.Receipt acceptedReceipt() {
        var captured=ArgumentCaptor.forClass(InstalledIntegrationReceiptMapper.Receipt.class);verify(receipts).claim(captured.capture());var r=captured.getValue();
        return new InstalledIntegrationReceiptMapper.Receipt(r.tenantId(),r.installationPid(),r.sourceCode(),r.externalEventId(),r.durableEventId(),
                r.envelopeSha256(),r.bindingSha256(),r.commandSha256(),r.providerSha256(),"request-pid","accepted");
    }
    @Test void nativeAcceptUsesActualCommandAndRestoresCallerIdentity() {
        MetaContext.setContext(77L,8L,"caller","caller");
        var accepted=service.accept(principal,source,event());assertEquals("request-pid",accepted.recordPid());assertFalse(accepted.duplicate());
        var request=ArgumentCaptor.forClass(CommandExecuteRequest.class);verify(executor).execute(eq(accept),request.capture());
        assertEquals("evt-1",request.getValue().getPayload().get("requestId"));assertNull(request.getValue().getTargetRecordPid());
        assertEquals(77L,MetaContext.getCurrentTenantId());assertEquals("caller",MetaContext.getCurrentUserPid());assertFalse(InstalledIntegrationExecution.active());
    }
    @Test void genericAutomationScopeDoesNotGrantBusinessCapability() {
        var weak=new MachinePrincipal(42L,"t","a",Set.of("automation.events.write"),"app","inst","test","t");
        assertThrows(SecurityException.class,()->service.accept(weak,source,event()));verifyNoInteractions(executor,receipts);
    }
    @Test void revokedInstallationGrantDeniesEvenWithPreviouslyIssuedToken() {
        when(scopes.findScopes(42L,9L)).thenReturn(Set.of());assertThrows(SecurityException.class,()->service.accept(principal,source,event()));verifyNoInteractions(executor,receipts);
    }
    @Test void foreignInstallationApplicationAndMetadataOwnerDeny() {
        when(installations.findActiveIdentity(42L,"inst")).thenReturn(new ApplicationInstallationMapper.ActiveIdentity(9L,"foreign"));
        assertThrows(SecurityException.class,()->service.accept(principal,source,event()));
        when(installations.findActiveIdentity(42L,"inst")).thenReturn(new ApplicationInstallationMapper.ActiveIdentity(9L,"app"));
        metadata.get(process).setTenantId(43L);assertThrows(SecurityException.class,()->service.accept(principal,source,event()));verifyNoInteractions(executor,receipts);
    }
    @Test void permanentIntentReplayDoesNotRunAcceptOrProcessAgain() {
        service.accept(principal,source,event());var r=acceptedReceipt();clearInvocations(executor);
        when(receipts.claim(any())).thenReturn(0);when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        assertTrue(service.accept(principal,source,event()).duplicate());verifyNoInteractions(executor);
        var original=event();var headers=new HashMap<>(original.headers());headers.put("requestId","new-http-trace");
        var retried=new IntegrationEventEnvelope(original.schemaVersion(),original.eventId(),original.eventType(),original.source(),
                original.subject(),original.occurredAt(),original.tenantId(),"new-http-trace",null,original.orderingKey(),original.sequence(),original.payload(),headers);
        assertTrue(service.accept(principal,source,retried).duplicate());verifyNoInteractions(executor);
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.accept(principal,source,event(Map.of("title","different"))));
    }
    @Test void reliableConsumerRequiresOriginalProvenanceThenExecutesFixedProcess() {
        service.accept(principal,source,event());var r=acceptedReceipt();when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        when(executor.execute(eq(process),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","completed")).build());
        service.consume(event());var request=ArgumentCaptor.forClass(CommandExecuteRequest.class);verify(executor).execute(eq(process),request.capture());
        assertEquals("request-pid",request.getValue().getTargetRecordPid());assertEquals(Map.of("requestPid","request-pid"),request.getValue().getPayload());
        assertThrows(SecurityException.class,()->service.consume(event(Map.of("title","forged"))));
    }
    @Test void ownerScopedResultAlwaysExecutesReadAndNeverCachesInitialAcceptedState() {
        service.accept(principal,source,event());var r=acceptedReceipt();when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        when(executor.execute(eq(result),any())).thenReturn(
            CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","accepted")).build(),
            CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","completed","taskPid","task")).build());
        when(receipts.deliveryStatus(42L,r.durableEventId())).thenReturn("failed");
        var failed=service.result(principal,source,"evt-1");
        assertEquals("accepted",failed.get("state"));assertEquals("failed",failed.get("deliveryStatus"));
        assertEquals("integration_delivery_failed",failed.get("deliveryFailureCode"));
        when(receipts.deliveryStatus(42L,r.durableEventId())).thenReturn("delivered");
        assertEquals("completed",service.result(principal,source,"evt-1").get("state"));
        var requests=ArgumentCaptor.forClass(CommandExecuteRequest.class);verify(executor,times(2)).execute(eq(result),requests.capture());
        requests.getAllValues().forEach(request -> {assertNull(request.getClientRequestId());assertNull(request.getTargetRecordPid());assertEquals(Map.of("requestId","evt-1"),request.getPayload());});
        var foreign=new MachinePrincipal(42L,"t","a",principal.scopes(),"app","foreign","test","t");
        assertThrows(SecurityException.class,()->service.result(foreign,source,"evt-1"));
    }

    @Test void bindingRevisionDriftAndMissingReceiptCannotBeConsumed() {
        assertThrows(SecurityException.class,()->service.consume(event()));service.accept(principal,source,event());var r=acceptedReceipt();
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        metadata.get(process).setExecutionConfig(metadata.get(process).getExecutionConfig().replace("\"revision\":1","\"revision\":2"));
        assertThrows(SecurityException.class,()->service.consume(event()));verify(executor,never()).execute(eq(process),any());
    }
    @Test void onlyCompletedSynchronousCommandResultsAreAccepted() {
        when(executor.execute(eq(accept),any())).thenReturn(CommandExecuteResult.builder().phaseReached("handler").data(Map.of()).build());
        assertThrows(IllegalStateException.class,()->service.accept(principal,source,event()));verify(receipts,never()).accepted(anyLong(),anyString(),anyString(),anyString(),anyString());
        assertFalse(InstalledIntegrationExecution.active());
    }
    @Test void onlyExplicitNativeBodyConflictMapsToIngressConflict() throws Exception {
        String key="domain.error.body_conflict";
        for(var command:metadata.values()) {
            var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(command.getExecutionConfig());
            ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("handlerParams").path("installedIntegration")).put("conflictMessageKey",key);
            command.setExecutionConfig(config.toString());
        }
        var exact=new com.auraboot.framework.plugin.extension.PluginCommandRejectionException(
            com.auraboot.framework.plugin.extension.PluginCommandRejectionException.Code.BUSINESS_RULE_BLOCKED,key);
        when(executor.execute(eq(accept),any())).thenThrow(new com.auraboot.framework.exception.BusinessException(
            com.auraboot.framework.common.constant.ResponseCode.BadParam,"rejected",exact));
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.accept(principal,source,event()));
        var other=new com.auraboot.framework.plugin.extension.PluginCommandRejectionException(
            com.auraboot.framework.plugin.extension.PluginCommandRejectionException.Code.BUSINESS_RULE_BLOCKED,key+"_other");
        when(executor.execute(eq(accept),any())).thenThrow(new com.auraboot.framework.exception.BusinessException(
            com.auraboot.framework.common.constant.ResponseCode.BadParam,"rejected",other));
        assertThrows(com.auraboot.framework.exception.BusinessException.class,()->service.accept(principal,source,event()));
    }

    @Test void contextCannotBeForgedOrInheritedByAnotherCommand() {
        var binding=new InstalledIntegrationBinding(42L,"app","inst",source,"evt-1","durable",1,Map.of());
        assertThrows(SecurityException.class,()->InstalledIntegrationExecution.verify(accept,42L,"config",Map.of("installedIntegration",Map.of())));
        InstalledIntegrationExecution.run(accept,"config",binding,false,new Object(),()-> {
            assertThrows(SecurityException.class,()->InstalledIntegrationExecution.verify(process,42L,"config",Map.of("installedIntegration",Map.of())));
            assertThrows(SecurityException.class,()->InstalledIntegrationExecution.run(accept,"config",binding,false,new Object(),()->null));return null;
        });assertFalse(InstalledIntegrationExecution.active());
    }
    @Test void disabledOrForeignServerOwnerDeniesWithoutImpersonation() {
        users.findByPid("buyer").setEnabled(false);
        assertThrows(SecurityException.class,()->service.accept(principal,source,event()));
        users.findByPid("buyer").setEnabled(true);members.findByTenantIdAndUserId(42L,7L).setTenantId(99L);
        assertThrows(SecurityException.class,()->service.accept(principal,source,event()));verifyNoInteractions(executor,receipts);
    }
    @Test void handlerSubstitutionOrChainingCannotInheritInstalledAuthority() {
        var binding=new InstalledIntegrationBinding(42L,"app","inst",source,"evt","durable",1,Map.of());
        Object actual=new Object();InstalledIntegrationExecution.run(accept,"config",binding,false,actual,()-> {
            InstalledIntegrationExecution.verifyHandler(accept,actual,List.of());
            assertThrows(SecurityException.class,()->InstalledIntegrationExecution.verifyHandler(accept,new Object(),List.of()));
            assertThrows(SecurityException.class,()->InstalledIntegrationExecution.verifyHandler(accept,actual,List.of(new Object())));return null;
        });
    }
    @Test void processTargetAndPayloadMustEqualHostReceiptAndCannotBeClientSelected() {
        var binding=new InstalledIntegrationBinding(42L,"app","inst",source,"evt","durable",1,Map.of());
        InstalledIntegrationExecution.run(process,"config",binding,false,new Object(),"owned-pid",()-> {
            InstalledIntegrationExecution.verifyTarget(process,"owned-pid",Map.of("requestPid","owned-pid"));
            assertThrows(SecurityException.class,()->InstalledIntegrationExecution.verifyTarget(process,"foreign-pid",Map.of("requestPid","foreign-pid")));
            assertThrows(SecurityException.class,()->InstalledIntegrationExecution.verifyTarget(process,"owned-pid",Map.of("requestPid","foreign-pid")));
            assertThrows(SecurityException.class,()->InstalledIntegrationExecution.verifyTarget(process,"owned-pid",Map.of("requestPid","owned-pid","extra",true)));return null;
        });
    }

    @Test void ordinarySystemUserCannotUseInstalledCommandWithoutProof() throws Exception {
        MetaContext.setContext(42L,0L,"system","system");
        var boundary = new com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase(
            mock(com.auraboot.framework.permission.service.UserPermissionService.class),
            mock(com.auraboot.framework.permission.service.RecordShareService.class),mock(org.springframework.beans.factory.ObjectProvider.class));
        var pipeline = com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.builder()
            .commandCode(accept).tenantId(42L).userId(0L).command(metadata.get(accept))
            .execConfig(json.readValue(metadata.get(accept).getExecutionConfig(),Map.class)).build();
        assertThrows(SecurityException.class,()->MetaContext.runWithExternalCommandPermission(registration.commandPermission(),()->boundary.execute(pipeline)));
    }

    @Test void finalResultSettingsCannotRecoverWritableOrIndependentBridges() {
        var binding=new InstalledIntegrationBinding(42L,"app","inst",source,"evt","durable",1,Map.of());
        var settings=new HashMap<String,Object>();
        for (String key:List.of("__independentTransactionAccessor","__asyncTaskAccessor",com.auraboot.framework.plugin.extension.CommandDispatcherAccessor.SETTINGS_KEY,
                "__tenantRoleAssignmentAccessor","__verifiedPhoneGrantAccessor","__biTemporalAccessor","__aiProviderAccessor",
                "__fileAccessor","__recordShareAccessor","__tenantSecretAccessor")) settings.put(key,new Object());
        var delegate=mock(DataAccessor.class);settings.put("__dataAccessor",delegate);Object projection=new Object();settings.put("__tenantProjectionAccessor",projection);
        InstalledIntegrationExecution.run(result,"config",binding,true,new Object(),()-> {
            InstalledIntegrationExecution.finishSettings(settings);return null;
        });
        assertEquals(Set.of("__dataAccessor","__tenantProjectionAccessor"),settings.keySet());
        assertSame(projection,settings.get("__tenantProjectionAccessor"));
        assertThrows(SecurityException.class,()->((DataAccessor)settings.get("__dataAccessor")).create("model",Map.of()));
        var ordinary=new HashMap<String,Object>();ordinary.put("__asyncTaskAccessor",new Object());
        InstalledIntegrationExecution.finishSettings(ordinary);assertTrue(ordinary.containsKey("__asyncTaskAccessor"));
    }

    @Test void resultReaderDelegatesReadsAndRejectsWrites() {
        DataAccessor delegate=mock(DataAccessor.class);when(delegate.getById("model","pid")).thenReturn(Map.of("pid","pid"));
        var reader=InstalledIntegrationReadOnly.wrap(delegate);assertEquals(Map.of("pid","pid"),reader.getById("model","pid"));
        assertThrows(SecurityException.class,()->reader.create("model",Map.of()));assertThrows(SecurityException.class,()->reader.update("model","pid",Map.of()));
        verify(delegate,never()).create(anyString(),anyMap());verify(delegate,never()).update(anyString(),anyString(),anyMap());
    }
}
