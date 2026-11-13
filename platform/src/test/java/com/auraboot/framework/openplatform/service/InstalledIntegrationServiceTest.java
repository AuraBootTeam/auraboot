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
    @AfterEach void clear() { MetaContext.clear(); }

    void synchronousResults(String state) {
        when(executor.execute(eq(process), any())).thenReturn(CommandExecuteResult.builder()
                .phaseReached("completed").data(Map.of("recordPid", "request-pid", "state", "completed")).build());
        when(executor.execute(eq(result), any())).thenReturn(CommandExecuteResult.builder()
                .phaseReached("completed").data(Map.of("recordPid", "request-pid", "state", state,
                        "items", List.of(Map.of("sourcePid", "source-pid", "taskPid", "task-pid", "status", "added")))).build());
    }

    @Test void synchronousExecutionRunsAllCommandsBeforeCompletingReceiptWithoutDeliveryPolling() {
        synchronousResults("completed");
        var response = service.executeSynchronously(principal, source, event());
        assertEquals("completed", response.get("state"));
        assertEquals(List.of(Map.of("sourcePid", "source-pid", "taskPid", "task-pid", "status", "added")), response.get("items"));
        assertFalse(response.containsKey("deliveryStatus"));
        var order = inOrder(executor, receipts);
        order.verify(executor).execute(eq(accept), any());
        order.verify(receipts).accepted(42L, "inst", source, "evt-1", "request-pid");
        order.verify(executor).execute(eq(process), any());
        order.verify(executor).execute(eq(result), any());
        order.verify(receipts).completed(42L, "inst", source, "evt-1", "request-pid");
        verify(receipts, never()).delivery(anyLong(), anyString());
    }

    @Test void synchronousProcessingFailureDoesNotRecordCompletionOrReadAResult() {
        when(executor.execute(eq(process), any())).thenThrow(new IllegalStateException("source read failed"));
        assertThrows(IllegalStateException.class, () -> service.executeSynchronously(principal, source, event()));
        verify(executor, never()).execute(eq(result), any());
        verify(receipts, never()).completed(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void synchronousResultMustBeBusinessCompletedBeforeReceiptCompletion() {
        synchronousResults("accepted");
        assertThrows(IllegalStateException.class, () -> service.executeSynchronously(principal, source, event()));
        verify(receipts, never()).completed(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void synchronousCompletedReplayReadsResultWithoutRepeatingBusinessWrites() {
        synchronousResults("completed");
        service.executeSynchronously(principal, source, event());
        var original = acceptedReceipt();
        when(receipts.claim(any())).thenReturn(0);
        when(receipts.find(42L, "inst", source, "evt-1")).thenReturn(receiptWith(original,
                "completed", "request-pid", original.bindingSha256(), original.commandSha256()));
        clearInvocations(executor);
        assertEquals("completed", service.executeSynchronously(principal, source, event()).get("state"));
        verify(executor, never()).execute(eq(accept), any());
        verify(executor, never()).execute(eq(process), any());
        verify(executor).execute(eq(result), any());
        verify(receipts, never()).delivery(anyLong(), anyString());
    }

    @Test void synchronousCallCannotTakeOverAnAcceptedAsynchronousRequest() {
        service.accept(principal, source, event());
        var original = acceptedReceipt();
        when(receipts.claim(any())).thenReturn(0);
        when(receipts.find(42L, "inst", source, "evt-1")).thenReturn(original);
        clearInvocations(executor);
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,
                () -> service.executeSynchronously(principal, source, event()));
        verifyNoInteractions(executor);
    }

    @Test void synchronousCallStillEnforcesCurrentInstallationScopeAndHostOwnedFields() {
        when(scopes.findScopes(42L, 9L)).thenReturn(Set.of());
        assertThrows(SecurityException.class, () -> service.executeSynchronously(principal, source, event()));
        verifyNoInteractions(executor, receipts);
        when(scopes.findScopes(42L, 9L)).thenReturn(Set.of(registration.requiredScope()));
        var forged = new HashMap<>(event().payload());
        forged.put("externalSubject", Map.of("type", "purchase-requisition", "pid", "other"));
        assertThrows(IllegalArgumentException.class,
                () -> service.executeSynchronously(principal, source, event(forged)));
        verifyNoInteractions(executor);
    }

    @Test void synchronousProcessingUsesMachineIdentityAndRestoresItAfterFailure() {
        assertFalse(com.auraboot.framework.application.security.MachineDataAccessContext.isActive());
        when(executor.execute(eq(process), any())).thenAnswer(invocation -> {
            assertEquals(principal, com.auraboot.framework.application.security.MachineDataAccessContext.principal());
            throw new IllegalStateException("source read failed");
        });
        assertThrows(IllegalStateException.class, () -> service.executeSynchronously(principal, source, event()));
        assertFalse(com.auraboot.framework.application.security.MachineDataAccessContext.isActive());
        verify(receipts, never()).completed(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void synchronousResultCannotSubstituteAnotherBusinessRecord() {
        synchronousResults("completed");
        when(executor.execute(eq(result), any())).thenReturn(CommandExecuteResult.builder()
                .phaseReached("completed").data(Map.of("recordPid", "foreign-record", "state", "completed")).build());
        assertThrows(IllegalStateException.class, () -> service.executeSynchronously(principal, source, event()));
        verify(receipts, never()).completed(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void synchronousReplayRejectsChangedInputBeforeReadingOrWritingBusinessData() {
        synchronousResults("completed");
        service.executeSynchronously(principal, source, event());
        var original = acceptedReceipt();
        when(receipts.claim(any())).thenReturn(0);
        when(receipts.find(42L, "inst", source, "evt-1")).thenReturn(receiptWith(original,
                "completed", "request-pid", original.bindingSha256(), original.commandSha256()));
        clearInvocations(executor);
        var changed = new HashMap<>(event().payload());
        changed.put("title", "Different inquiry");
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,
                () -> service.executeSynchronously(principal, source, event(changed)));
        verifyNoInteractions(executor);
    }

    void managedLookupConfig() throws Exception {
        for(var command:metadata.values()) {
            var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(command.getExecutionConfig());
            var declaration=(com.fasterxml.jackson.databind.node.ObjectNode)config.path("handlerParams").path("installedIntegration");
            declaration.remove("bindings");declaration.set("bindingLookup",json.valueToTree(Map.of("version",1,"modelCode","managed_registry",
                    "applicationField","reg_application","installationField","reg_installation","revisionField","reg_revision","statusField","reg_status",
                    "allowedStatuses",List.of("active"),"recordPidParameter","connectionPid","referenceReadFields",Map.of())));
            command.setExecutionConfig(json.writeValueAsString(config));
        }
    }
    void installationActorRegistration() {
        var current=new Registration(source,registration.capability(),registration.eventTypes(),registration.requiredScope(),registration.commandPermission(),
                registration.providerPluginId(),registration.commandOwnerPluginId(),accept,process,result);
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(current,"sha256:"+"a".repeat(64))));
    }
    @Test void managedLookupUsesVerifiedPrincipalOwnerAndInjectsItsSelection() throws Exception {
        installationActorRegistration();managedLookupConfig();var lookup=mock(InstalledIntegrationBindingLookup.class);service.setBindingLookup(lookup);
        String record="00000000000000000000000003";
        when(lookup.resolve(eq(principal),eq(registration.commandOwnerPluginId()),any())).thenReturn(new InstalledIntegrationBindingLookup.Selection(4,Map.of("connectionPid",record)));
        when(executor.execute(eq(accept),any())).thenAnswer(call->{
            var settings=new HashMap<String,Object>();InstalledIntegrationExecution.install(accept,42L,settings);
            var installed=(InstalledIntegrationBinding)settings.get(InstalledIntegrationBinding.SETTINGS_KEY);
            assertEquals(4,installed.bindingRevision());assertEquals(Map.of("connectionPid",record),installed.parameters());
            assertEquals("inst",installed.installationPid());assertEquals("app",installed.applicationPid());
            return CommandExecuteResult.builder().phaseReached("completed").data(Map.of("recordPid","request-pid","state","accepted")).build();
        });
        assertFalse(service.accept(principal,source,event()).duplicate());verify(lookup).resolve(eq(principal),eq(registration.commandOwnerPluginId()),any());
    }
    @Test void managedLookupCannotFallBackWhenReaderIsMissingOrHumanDelegationIsRequired() throws Exception {
        managedLookupConfig();assertThrows(SecurityException.class,()->service.accept(principal,source,event()));
        var lookup=mock(InstalledIntegrationBindingLookup.class);service.setBindingLookup(lookup);
        assertThrows(SecurityException.class,()->service.accept(principal,source,event()));verifyNoInteractions(lookup);verify(receipts,never()).claim(any());
        installationActorRegistration();assertThrows(SecurityException.class,()->service.accept(principal,source,event()));
    }
    @Test void inlineBindingKeepsItsExistingPathEvenWhenLookupReaderIsInstalled() {
        var lookup=mock(InstalledIntegrationBindingLookup.class);service.setBindingLookup(lookup);
        assertFalse(service.accept(principal,source,event()).duplicate());verifyNoInteractions(lookup);
    }
    @Test void inlineAndLookupDeclarationsCannotBeCombined() throws Exception {
        var lookup=mock(InstalledIntegrationBindingLookup.class);service.setBindingLookup(lookup);
        for(var command:metadata.values()) {
            var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(command.getExecutionConfig());
            ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("handlerParams").path("installedIntegration")).set("bindingLookup",json.createObjectNode());
            command.setExecutionConfig(json.writeValueAsString(config));
        }
        assertThrows(SecurityException.class,()->service.accept(principal,source,event()));verifyNoInteractions(lookup);verify(receipts,never()).claim(any());
    }
    IntegrationEventEnvelope event() { return event(Map.of("title","Inquiry","selected",List.of(Map.of("requisitionId","10","entryId","11")))); }
    IntegrationEventEnvelope event(Map<String,Object> payload) {
        return new IntegrationEventEnvelope("1.0","ext:inst:"+source+":evt-1","external."+source+".inquiry.requested.v1",
                "open-platform/inst/"+source,"purchase-requisition/10",Instant.parse("2026-10-16T00:00:00Z"),42L,"trace",null,"purchase-requisition/10",0,payload,
                Map.of("applicationPid","app","installationPid","inst","sourceCode",source,"externalEventId","evt-1","externalSubjectType","purchase-requisition","externalSubjectPid","10"));
    }
    InstalledIntegrationReceiptMapper.Receipt acceptedReceipt() {
        var captured=ArgumentCaptor.forClass(InstalledIntegrationReceiptMapper.Receipt.class);verify(receipts).claim(captured.capture());var r=captured.getValue();
        return new InstalledIntegrationReceiptMapper.Receipt(r.tenantId(),r.installationPid(),r.sourceCode(),r.externalEventId(),r.durableEventId(),
                r.envelopeSha256(),r.bindingSha256(),r.commandSha256(),r.providerSha256(),"request-pid","accepted");
    }
    InstalledIntegrationReceiptMapper.Receipt receiptWith(InstalledIntegrationReceiptMapper.Receipt r,
            String state, String pid, String binding, String command) {
        return new InstalledIntegrationReceiptMapper.Receipt(r.tenantId(),r.installationPid(),r.sourceCode(),r.externalEventId(),
                r.durableEventId(),r.envelopeSha256(),binding,command,r.providerSha256(),pid,state);
    }
    void upgradeProvider() {
        String digest="sha256:"+"b".repeat(64);
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(registration,digest)));
        when(plugins.observeLoadedArtifact(any())).thenReturn(Optional.of(new AuraPluginManager.LoadedPluginArtifact(
                registration.providerPluginId(),java.nio.file.Path.of("provider-new.jar"),digest)));
    }
    @Test void unrelatedInstallationAppendDoesNotRevokeTheOriginalCompletedRequest() throws Exception {
        // Authorization-shape check only; durable replay is qualified against real HTTP/PostgreSQL.
        service.accept(principal,source,event());var original=acceptedReceipt();
        when(receipts.claim(any())).thenReturn(0);
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(original,
                "completed","request-pid",original.bindingSha256(),original.commandSha256()));
        appendUnrelatedInstallation();
        clearInvocations(executor);
        assertEquals(new InstalledIntegrationService.Acceptance("request-pid",true),service.accept(principal,source,event()));
        verifyNoInteractions(executor);
    }

    @Test void verifyAcceptedReplayRequiresAnExistingMatchingReceiptAndCannotExecuteWork() {
        // Authorization and call-shape only; persistence is qualified by installed HTTP/PostgreSQL.
        service.accept(principal,source,event());var original=acceptedReceipt();
        clearInvocations(receipts,executor);
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(original);
        assertEquals(new InstalledIntegrationService.Acceptance("request-pid",true),service.verifyAcceptedReplay(principal,source,event()));
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,
                ()->service.verifyAcceptedReplay(principal,source,event(Map.of("title","Changed"))));
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(original,"accepting",null,original.bindingSha256(),original.commandSha256()));
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.verifyAcceptedReplay(principal,source,event()));
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(original,"completed","request-pid","different-binding",original.commandSha256()));
        assertThrows(SecurityException.class,()->service.verifyAcceptedReplay(principal,source,event()));
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(null);
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.verifyAcceptedReplay(principal,source,event()));
        verify(receipts,never()).claim(any());
        verify(receipts,never()).accepted(anyLong(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(executor);
    }

    @Test void legacyFullConfigurationReceiptSurvivesProvenUnrelatedSuffixOnly() throws Exception {
        // This is a command-authorization test, not a substitute for real stored receipt replay.
        Map<String,String> originalConfigs=new HashMap<>();
        metadata.forEach((code,command)->originalConfigs.put(code,command.getExecutionConfig()));
        String legacyHash=service.commandHash(registration,originalConfigs);
        service.accept(principal,source,event());var original=acceptedReceipt();
        when(receipts.claim(any())).thenReturn(0);
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(original,
                "completed","request-pid",original.bindingSha256(),legacyHash));
        appendUnrelatedInstallation();
        clearInvocations(executor);
        assertEquals(new InstalledIntegrationService.Acceptance("request-pid",true),service.accept(principal,source,event()));
        verifyNoInteractions(executor);
        for(var command:metadata.values()) {
            var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(command.getExecutionConfig());
            config.put("machineDataScope","changed");command.setExecutionConfig(json.writeValueAsString(config));
        }
        assertThrows(SecurityException.class,()->service.accept(principal,source,event()));
        verifyNoInteractions(executor);
    }

    void appendUnrelatedInstallation() throws Exception {
        for(var command:metadata.values()) {
            var config=json.readTree(command.getExecutionConfig());
            ((com.fasterxml.jackson.databind.node.ArrayNode)config.path("handlerParams")
                    .path("installedIntegration").path("bindings")).add(json.valueToTree(Map.of(
                            "installationPid","other-installation","revision",1,
                            "parameters",Map.of("ownerUserPid","another-owner","connectionPid","another-connection"))));
            command.setExecutionConfig(json.writeValueAsString(config));
        }
    }

    @Test void commandFingerprintDoesNotDependOnEventSetIterationOrderAndPreservesLegacySingleTypeHashes() {
        var configs=Map.of(accept,"accept-config",process,"process-config",result,"result-config");
        var expanded=spy(new Registration(source,registration.capability(),Set.of(event().eventType(),"external."+source+".inquiry.changed.v1"),
                registration.requiredScope(),registration.commandPermission(),registration.providerPluginId(),registration.commandOwnerPluginId(),
                accept,process,result,registration.serverActorParameter()));
        var events=List.of(event().eventType(),"external."+source+".inquiry.changed.v1");
        doReturn(new LinkedHashSet<>(events)).when(expanded).eventTypes();String first=service.commandHash(expanded,configs);
        doReturn(new LinkedHashSet<>(events.reversed())).when(expanded).eventTypes();
        assertEquals(first,service.commandHash(expanded,configs),"event discovery order must not change a durable receipt fingerprint");
        assertEquals(service.hash(Map.of("registration",registration,"configs",configs)),service.commandHash(registration,configs));
        var legacy=new Registration(source,registration.capability(),registration.eventTypes(),registration.requiredScope(),registration.commandPermission(),
                registration.providerPluginId(),registration.commandOwnerPluginId(),accept,process,result);
        assertEquals(service.hash(Map.of("registration",legacy,"configs",configs)),service.commandHash(legacy,configs));
    }
    @Test void explicitlyDeclaredEventExpansionPreservesOnlyCompletedReceiptQueriesAndReplays() {
        service.accept(principal,source,event());var old=acceptedReceipt();
        var expanded=new Registration(source,registration.capability(),Set.of(event().eventType(),"external."+source+".inquiry.changed.v1"),
                registration.requiredScope(),registration.commandPermission(),registration.providerPluginId(),registration.commandOwnerPluginId(),
                accept,process,result,registration.serverActorParameter());
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(expanded,"sha256:"+"a".repeat(64))));
        when(registry.completedReceiptRegistrations(source)).thenReturn(Set.of(registration));
        when(receipts.claim(any())).thenReturn(0);
        var completed=receiptWith(old,"completed","request-pid",old.bindingSha256(),old.commandSha256());
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(completed);clearInvocations(executor);
        assertTrue(service.accept(principal,source,event()).duplicate());service.consume(event());verifyNoInteractions(executor);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","completed")).build());
        assertEquals("request-pid",service.result(principal,source,"evt-1").get("recordPid"));
        clearInvocations(executor);
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(old);
        assertThrows(SecurityException.class,()->service.accept(principal,source,event()));
        assertThrows(SecurityException.class,()->service.consume(event()));
        assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"));verifyNoInteractions(executor);
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(completed);
        when(registry.completedReceiptRegistrations(source)).thenReturn(Set.of());
        assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"));
    }
    @Test void completedReceiptAllowsOnlyDeclaredReadAdditionOnProcess() throws Exception {
        service.accept(principal,source,event());var original=acceptedReceipt();
        var completed=receiptWith(original,"completed","request-pid",original.bindingSha256(),original.commandSha256());
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(completed);when(receipts.claim(any())).thenReturn(0);
        addMachineReadGrant(process,"internal_address_history");
        when(registry.completedReceiptProcessReadAdditions(eq(registration),anyString())).thenReturn(Set.of("internal_address_history"));
        upgradeProvider();clearInvocations(executor,receipts);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","completed")).build());
        assertEquals("request-pid",service.result(principal,source,"evt-1").get("recordPid"));
        clearInvocations(executor);
        assertTrue(service.accept(principal,source,event()).duplicate());service.consume(event());verifyNoInteractions(executor);
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.accept(principal,source,event(Map.of("title","changed"))));
        assertThrows(SecurityException.class,()->service.consume(event(Map.of("title","changed"))));
        verify(receipts,never()).completed(anyLong(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void completedReceiptAllowsExactReadFieldsWhileKeepingExistingProcessWritesPinned() throws Exception {
        addMachineReadGrant(process,"native_source");
        var baseline=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(process).getExecutionConfig());
        ((com.fasterxml.jackson.databind.node.ObjectNode)baseline.at("/handlerParams/installedMachineData/native_source"))
                .putArray("update").add("selected_address");
        metadata.get(process).setExecutionConfig(baseline.toString());
        service.accept(principal,source,event());var original=acceptedReceipt();
        var completed=receiptWith(original,"completed","request-pid",original.bindingSha256(),original.commandSha256());
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(completed);when(receipts.claim(any())).thenReturn(0);
        appendReadFields();
        when(registry.completedReceiptProcessReadFieldAdditions(eq(registration),anyString()))
                .thenReturn(Map.of("native_source",Set.of("closed","project","organization_snapshot")));
        upgradeProvider();clearInvocations(executor);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","completed")).build());
        assertEquals("request-pid",service.result(principal,source,"evt-1").get("recordPid"));
        clearInvocations(executor);assertTrue(service.accept(principal,source,event()).duplicate());service.consume(event());verifyNoInteractions(executor);
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.accept(principal,source,event(Map.of("title","changed"))));
        for(String state:List.of("accepting","accepted","failed")) {
            when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(original,state,"request-pid",original.bindingSha256(),original.commandSha256()));
            assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"),state);
            assertThrows(SecurityException.class,()->service.accept(principal,source,event()),state);
            assertThrows(SecurityException.class,()->service.consume(event()),state);
        }
        verifyNoInteractions(executor);
    }

    @Test void fieldReadPredecessorNeverAllowsOtherWritesReadsCommandsOrBindingChanges() throws Exception {
        addMachineReadGrant(process,"native_source");service.accept(principal,source,event());var original=acceptedReceipt();
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(original,"completed","request-pid",original.bindingSha256(),original.commandSha256()));
        when(receipts.claim(any())).thenReturn(0);
        var initial=new HashMap<String,String>();metadata.forEach((code,value)->initial.put(code,value.getExecutionConfig()));
        for(String fault:List.of("undeclared","missing-field","other-read","lost-original-read","write","accept","result","binding")) {
            initial.forEach((code,value)->metadata.get(code).setExecutionConfig(value));appendReadFields();
            when(registry.completedReceiptProcessReadFieldAdditions(eq(registration),anyString())).thenReturn(
                    fault.equals("undeclared")?Map.of():Map.of("native_source",fault.equals("missing-field")?Set.of("missing"):Set.of("closed","project","organization_snapshot")));
            String target=fault.equals("accept")?accept:fault.equals("result")?result:process;
            var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(target).getExecutionConfig());
            var processConfig=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(process).getExecutionConfig());
            var grant=(com.fasterxml.jackson.databind.node.ObjectNode)processConfig.at("/handlerParams/installedMachineData/native_source");
            switch(fault) {
                case "other-read" -> ((com.fasterxml.jackson.databind.node.ArrayNode)grant.get("read")).add("other");
                case "lost-original-read" -> ((com.fasterxml.jackson.databind.node.ArrayNode)grant.get("read")).remove(2);
                case "write" -> grant.putArray("update").add("project");
                case "accept","result" -> config.put("description","changed");
                case "binding" -> ((com.fasterxml.jackson.databind.node.ObjectNode)config.at("/handlerParams/installedIntegration/bindings/0/parameters"))
                        .put("connectionPid","changed-connection");
                default -> { }
            }
            if(target.equals(process) && List.of("other-read","lost-original-read","write").contains(fault)) config=processConfig;
            metadata.get(target).setExecutionConfig(config.toString());
            if(fault.equals("binding"))for(String other:List.of(accept,result)) {
                var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(other).getExecutionConfig());
                ((com.fasterxml.jackson.databind.node.ObjectNode)root.at("/handlerParams/installedIntegration/bindings/0/parameters"))
                        .put("connectionPid","changed-connection");metadata.get(other).setExecutionConfig(root.toString());
            }
            clearInvocations(executor);
            assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"),fault);
            assertThrows(SecurityException.class,()->service.accept(principal,source,event()),fault);
            assertThrows(SecurityException.class,()->service.consume(event()),fault);
            verifyNoInteractions(executor);
        }
    }

    private void appendReadFields() throws Exception {
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(process).getExecutionConfig());
        ((com.fasterxml.jackson.databind.node.ArrayNode)config.at("/handlerParams/installedMachineData/native_source/read"))
                .add("closed").add("project").add("organization_snapshot");
        metadata.get(process).setExecutionConfig(config.toString());
    }

    private void addMachineReadGrant(String command,String model) throws Exception {
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(command).getExecutionConfig());
        var parameters=(com.fasterxml.jackson.databind.node.ObjectNode)config.get("handlerParams");
        var grants=parameters.get("installedMachineData") instanceof com.fasterxml.jackson.databind.node.ObjectNode existing
                ? existing : parameters.putObject("installedMachineData");
        grants.putObject(model).putArray("read").add("pid").add("tenant_id").add("selected_address");
        metadata.get(command).setExecutionConfig(config.toString());
    }

    @Test void declaredProcessReadCompatibilityNeverMigratesUnfinishedOrMalformedReceipts() throws Exception {
        service.accept(principal,source,event());var original=acceptedReceipt();addMachineReadGrant(process,"internal_address_history");
        when(registry.completedReceiptProcessReadAdditions(eq(registration),anyString())).thenReturn(Set.of("internal_address_history"));
        when(receipts.claim(any())).thenReturn(0);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","accepted")).build());
        clearInvocations(executor);
        for(String state:List.of("accepting","accepted","failed","completed")) {
            var r=receiptWith(original,state,state.equals("completed")?"invalid pid":"request-pid",original.bindingSha256(),original.commandSha256());
            when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
            assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"),state);
            assertThrows(SecurityException.class,()->service.accept(principal,source,event()),state);
            assertThrows(SecurityException.class,()->service.consume(event()),state);
        }
        verifyNoInteractions(executor);
    }

    @Test void completedReadCompatibilityStillPinsResultWritesOtherConfigurationAndBinding() throws Exception {
        service.accept(principal,source,event());var original=acceptedReceipt();
        var completed=receiptWith(original,"completed","request-pid",original.bindingSha256(),original.commandSha256());
        var initial=new HashMap<String,String>();metadata.forEach((code,value)->initial.put(code,value.getExecutionConfig()));
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(completed);when(receipts.claim(any())).thenReturn(0);
        for(String fault:List.of("result","accept","other-process-setting","write-on-added-model","unrelated-write","binding","undeclared")) {
            initial.forEach((code,value)->metadata.get(code).setExecutionConfig(value));addMachineReadGrant(process,"internal_address_history");
            when(registry.completedReceiptProcessReadAdditions(eq(registration),anyString())).thenReturn(
                    fault.equals("undeclared")?Set.of():Set.of("internal_address_history"));
            String command=fault.equals("result")?result:fault.equals("accept")?accept:process;
            var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(command).getExecutionConfig());
            var params=(com.fasterxml.jackson.databind.node.ObjectNode)config.get("handlerParams");
            switch(fault) {
                case "result","accept" -> params.put("otherSetting",true);
                case "other-process-setting" -> config.put("machineDataScope","changed");
                case "write-on-added-model" -> ((com.fasterxml.jackson.databind.node.ObjectNode)params.path("installedMachineData").path("internal_address_history"))
                        .putArray("update").add("selected_address");
                case "unrelated-write" -> ((com.fasterxml.jackson.databind.node.ObjectNode)params.path("installedMachineData"))
                        .putObject("other_model").putArray("update").add("value");
                case "binding" -> ((com.fasterxml.jackson.databind.node.ObjectNode)params.at("/installedIntegration/bindings/0/parameters"))
                        .put("connectionPid","another-connection");
                case "undeclared" -> { }
                default -> throw new AssertionError(fault);
            }
            metadata.get(command).setExecutionConfig(config.toString());
            if(fault.equals("binding")) {
                for(String other:List.of(accept,result)) {
                    var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(metadata.get(other).getExecutionConfig());
                    ((com.fasterxml.jackson.databind.node.ObjectNode)root.at("/handlerParams/installedIntegration/bindings/0/parameters"))
                            .put("connectionPid","another-connection");
                    metadata.get(other).setExecutionConfig(root.toString());
                }
            }
            clearInvocations(executor);
            assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"),fault);
            assertThrows(SecurityException.class,()->service.accept(principal,source,event()),fault);
            assertThrows(SecurityException.class,()->service.consume(event()),fault);
            verifyNoInteractions(executor);
        }
    }

    @Test void compatibleCompletedQueryStillRejectsAResultForAnotherRecord() throws Exception {
        service.accept(principal,source,event());var original=acceptedReceipt();addMachineReadGrant(process,"internal_address_history");
        when(registry.completedReceiptProcessReadAdditions(eq(registration),anyString())).thenReturn(Set.of("internal_address_history"));
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(original,"completed","request-pid",original.bindingSha256(),original.commandSha256()));
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","foreign-pid","state","completed")).build());
        clearInvocations(executor);
        assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"));
        verify(executor,never()).execute(eq(accept),any());verify(executor,never()).execute(eq(process),any());
    }

    @Test void completedReceiptSurvivesProviderUpgradeWithoutRepeatingBusinessCommands() {
        service.accept(principal,source,event());var r=acceptedReceipt();
        var completed=receiptWith(r,"completed","request-pid",r.bindingSha256(),r.commandSha256());
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(completed);when(receipts.claim(any())).thenReturn(0);
        upgradeProvider();clearInvocations(executor,receipts);
        assertEquals(new InstalledIntegrationService.Acceptance("request-pid",true),service.accept(principal,source,event()));
        service.consume(event());verifyNoInteractions(executor);
        verify(receipts,never()).accepted(anyLong(),anyString(),anyString(),anyString(),anyString());
        verify(receipts,never()).completed(anyLong(),anyString(),anyString(),anyString(),anyString());
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","completed")).build());
        assertEquals("request-pid",service.result(principal,source,"evt-1").get("recordPid"));
        var query=ArgumentCaptor.forClass(CommandExecuteRequest.class);verify(executor).execute(eq(result),query.capture());
        assertEquals("QUERY",query.getValue().getOperationType());
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,
                ()->service.accept(principal,source,event(Map.of("title","different"))));
    }
    @Test void providerUpgradeNeverMigratesAcceptedOrInvalidTerminalReceipts() {
        service.accept(principal,source,event());var r=acceptedReceipt();upgradeProvider();clearInvocations(executor);
        when(receipts.claim(any())).thenReturn(0);
        for(String state:List.of("accepting","accepted","failed","completed")) {
            var invalid=receiptWith(r,state,state.equals("completed")?"invalid pid":"request-pid",r.bindingSha256(),r.commandSha256());
            when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(invalid);
            assertThrows(SecurityException.class,()->service.accept(principal,source,event()),state);
            assertThrows(SecurityException.class,()->service.consume(event()),state);
            assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"),state);
        }
        verifyNoInteractions(executor);
    }
    @Test void terminalUpgradeStillRejectsBindingCommandAndForeignAuthorityDrift() {
        service.accept(principal,source,event());var r=acceptedReceipt();upgradeProvider();clearInvocations(executor);
        when(receipts.claim(any())).thenReturn(0);
        for(var changed:List.of(receiptWith(r,"completed","request-pid","changed",r.commandSha256()),
                receiptWith(r,"completed","request-pid",r.bindingSha256(),"changed"))) {
            when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(changed);
            assertThrows(SecurityException.class,()->service.accept(principal,source,event()));
            assertThrows(SecurityException.class,()->service.consume(event()));
            assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"));
        }
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receiptWith(r,"completed","request-pid",r.bindingSha256(),r.commandSha256()));
        assertThrows(SecurityException.class,()->service.consume(event(Map.of("title","forged"))));
        var foreign=new MachinePrincipal(43L,"token","app",principal.scopes(),"app","inst","test","token");
        assertThrows(SecurityException.class,()->service.result(foreign,source,"evt-1"));
        verifyNoInteractions(executor);
    }
    @Test void currentResultQueryMustReturnExactlyTheOriginalReceiptRecord() {
        service.accept(principal,source,event());var r=acceptedReceipt();
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        for(Map<String,Object> data:List.<Map<String,Object>>of(Map.of("recordPid","foreign-pid"),Map.of("state","completed"))) {
            when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed").data(data).build());
            assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"));
        }
    }
    @Test void nativeAcceptUsesActualCommandAndRestoresCallerIdentity() {
        MetaContext.setContext(77L,8L,"caller","caller");
        var accepted=service.accept(principal,source,event());assertEquals("request-pid",accepted.recordPid());assertFalse(accepted.duplicate());
        var request=ArgumentCaptor.forClass(CommandExecuteRequest.class);verify(executor).execute(eq(accept),request.capture());
        assertEquals("evt-1",request.getValue().getPayload().get("requestId"));
        assertEquals(Map.of("type","purchase-requisition","pid","10"),request.getValue().getPayload().get("externalSubject"));assertNull(request.getValue().getTargetRecordPid());
        assertEquals(77L,MetaContext.getCurrentTenantId());assertEquals("caller",MetaContext.getCurrentUserPid());assertFalse(InstalledIntegrationExecution.active());
    }
    @Test void clientCannotOverrideHostSubjectAndConsumerCannotChangeItsOrderingIdentity() {
        assertThrows(IllegalArgumentException.class,()->service.accept(principal,source,event(Map.of("externalSubject",Map.of("type","forged","pid","99")))));
        verify(executor,never()).execute(anyString(),any());
        var original=event();var tampered=new IntegrationEventEnvelope(original.schemaVersion(),original.eventId(),original.eventType(),original.source(),
                original.subject(),original.occurredAt(),original.tenantId(),original.correlationId(),null,"purchase-requisition/99",0,original.payload(),original.headers());
        assertThrows(SecurityException.class,()->service.accept(principal,source,tampered));
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
    @Test void processReceivesOriginalVerifiedEnvelopeButResultHasNoInventedEvent() {
        service.accept(principal,source,event());var receipt=acceptedReceipt();
        when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(receipt);
        when(executor.execute(eq(process),any())).thenAnswer(call->{
            var settings=new HashMap<String,Object>();InstalledIntegrationExecution.install(process,42L,settings);
            var facts=(InstalledIntegrationEvent)settings.get(InstalledIntegrationEvent.SETTINGS_KEY);
            assertNotNull(facts);assertEquals(event().eventType(),facts.eventType());assertEquals(event().occurredAt(),facts.occurredAt());
            assertEquals(event().sequence(),facts.sequence());assertEquals("10",facts.subjectPid());
            return CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","completed")).build();
        });
        service.consume(event());
        when(executor.execute(eq(result),any())).thenAnswer(call->{
            var settings=new HashMap<String,Object>();settings.put(InstalledIntegrationEvent.SETTINGS_KEY,"configured");
            InstalledIntegrationExecution.install(result,42L,settings);assertFalse(settings.containsKey(InstalledIntegrationEvent.SETTINGS_KEY));
            return CommandExecuteResult.builder().phaseReached("completed").data(Map.of("recordPid","request-pid","state","completed")).build();
        });
        assertEquals("completed",service.result(principal,source,"evt-1").get("state"));
    }

    @Test void failedAttemptIsVisibleWhilePendingOrProcessingAndClearsAfterDelivery() {
        service.accept(principal,source,event());var r=acceptedReceipt();when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","accepted")).build());
        when(receipts.delivery(42L,r.durableEventId())).thenReturn(
                new InstalledIntegrationReceiptMapper.Delivery("pending",true),
                new InstalledIntegrationReceiptMapper.Delivery("processing",true),
                new InstalledIntegrationReceiptMapper.Delivery("delivered",true));
        var pending=service.result(principal,source,"evt-1");
        assertEquals("accepted",pending.get("state"));assertEquals("pending",pending.get("deliveryStatus"));
        assertEquals("integration_delivery_failed",pending.get("deliveryFailureCode"));
        var retry=service.result(principal,source,"evt-1");
        assertEquals("processing",retry.get("deliveryStatus"));assertEquals("integration_delivery_failed",retry.get("deliveryFailureCode"));
        var delivered=service.result(principal,source,"evt-1");
        assertEquals("delivered",delivered.get("deliveryStatus"));assertFalse(delivered.containsKey("deliveryFailureCode"));
        assertEquals(Set.of("recordPid","state","deliveryStatus"),delivered.keySet());
        verify(receipts,times(3)).delivery(42L,r.durableEventId());
    }

    @Test void firstAttemptAndUnavailableDeliveryDoNotInventAnExecutionFailure() {
        service.accept(principal,source,event());var r=acceptedReceipt();when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","accepted")).build());
        when(receipts.delivery(42L,r.durableEventId())).thenReturn(
                new InstalledIntegrationReceiptMapper.Delivery("pending",false),
                new InstalledIntegrationReceiptMapper.Delivery("processing",false),null);
        for(String state:List.of("pending","processing","unavailable")) {
            var answer=service.result(principal,source,"evt-1");assertEquals(state,answer.get("deliveryStatus"));
            assertEquals(Set.of("recordPid","state","deliveryStatus"),answer.keySet());
        }
    }

    @Test void unknownDeliveryStatesStillRejectEvenWithAnEarlierFailedAttempt() {
        service.accept(principal,source,event());var r=acceptedReceipt();when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","accepted")).build());
        when(receipts.delivery(42L,r.durableEventId())).thenReturn(new InstalledIntegrationReceiptMapper.Delivery("forged",true));
        assertThrows(SecurityException.class,()->service.result(principal,source,"evt-1"));
    }

    @Test void hostOwnedFailureCodeCannotLeakStaleBusinessReaderDetailsAfterSuccess() {
        service.accept(principal,source,event());var r=acceptedReceipt();when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        when(executor.execute(eq(result),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed")
                .data(Map.of("recordPid","request-pid","state","completed","deliveryFailureCode","stale internal error text")).build());
        when(receipts.delivery(42L,r.durableEventId())).thenReturn(new InstalledIntegrationReceiptMapper.Delivery("delivered",true));
        var answer=service.result(principal,source,"evt-1");assertEquals("completed",answer.get("state"));
        assertEquals(Set.of("recordPid","state","deliveryStatus"),answer.keySet());
    }

    @Test void ownerScopedResultAlwaysExecutesReadAndNeverCachesInitialAcceptedState() {
        service.accept(principal,source,event());var r=acceptedReceipt();when(receipts.find(42L,"inst",source,"evt-1")).thenReturn(r);
        when(executor.execute(eq(result),any())).thenReturn(
            CommandExecuteResult.builder().phaseReached("completed").data(Map.of("recordPid","request-pid","state","accepted")).build(),
            CommandExecuteResult.builder().phaseReached("completed").data(Map.of("recordPid","request-pid","state","completed","taskPid","task")).build());
        when(receipts.delivery(42L,r.durableEventId())).thenReturn(new InstalledIntegrationReceiptMapper.Delivery("failed",true));
        var failed=service.result(principal,source,"evt-1");
        assertEquals("accepted",failed.get("state"));assertEquals("failed",failed.get("deliveryStatus"));
        assertEquals("integration_delivery_failed",failed.get("deliveryFailureCode"));
        when(receipts.delivery(42L,r.durableEventId())).thenReturn(new InstalledIntegrationReceiptMapper.Delivery("delivered",true));
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
