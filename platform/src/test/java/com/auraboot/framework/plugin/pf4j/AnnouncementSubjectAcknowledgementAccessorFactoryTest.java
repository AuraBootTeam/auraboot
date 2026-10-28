package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.*;
import com.auraboot.framework.plugin.extension.CommandSubjectAcknowledgementSource.*;
import com.auraboot.framework.workbench.service.AnnouncementSubjectAcknowledgementService;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual host scope factory with physical JDBC/resource doubles, not a real database integration. */
class AnnouncementSubjectAcknowledgementAccessorFactoryTest {
    final SqlSessionFactory sessions=mock(SqlSessionFactory.class);final ExtensionRegistry registry=mock(ExtensionRegistry.class);
    final BackgroundDataAccessor records=mock(BackgroundDataAccessor.class);final CommandRecordActionScopeAccessorFactory scopes=mock(CommandRecordActionScopeAccessorFactory.class);
    final CommandMembershipLockAccessor memberships=mock(CommandMembershipLockAccessor.class);final IdentityDirectoryAccessor directory=mock(IdentityDirectoryAccessor.class);
    final AnnouncementSubjectAcknowledgementService service=mock(AnnouncementSubjectAcknowledgementService.class);
    final AnnouncementSubjectAcknowledgementAccessorFactory factory=new AnnouncementSubjectAcknowledgementAccessorFactory(sessions,registry,records,scopes,memberships,directory,service);
    final DataSource data=mock(DataSource.class);final Connection connection=mock(Connection.class);
    final CommandHandlerExtension handler=mock(CommandHandlerExtension.class,withSettings().extraInterfaces(CommandSubjectAcknowledgementSource.class));
    final IdentityDirectoryAccessor.TenantUserIdentity operator=new IdentityDirectoryAccessor.TenantUserIdentity(new IdentityDirectoryAccessor.UserIdentity(2L,"operator","operator","Operator"),"operator-member",3L);
    final IdentityDirectoryAccessor.TenantUserIdentity applicant=new IdentityDirectoryAccessor.TenantUserIdentity(new IdentityDirectoryAccessor.UserIdentity(4L,"applicant","applicant","Applicant"),"applicant-member",5L);
    final String hash="a".repeat(64);final Instant now=Instant.parse("2026-10-28T00:00:00Z");
    CommandPipelineContext context;ConnectionHolder holder;SubjectSource source;SubjectSourceContext callback;List<Check> checks;
    @BeforeEach void setup(){
        MetaContext.setContext(1L,2L,"operator","Operator");MetaContext.setMemberId(3L);TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        holder=spy(new ConnectionHolder(connection));holder.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(data,holder);
        when(sessions.getConfiguration()).thenReturn(new Configuration(new Environment("unit",new JdbcTransactionFactory(),data)));
        var command=new CommandDefinition();command.setCode("application:arrive");command.setModelCode("application");var request=new CommandExecuteRequest();request.setTargetRecordId("target");
        context=CommandPipelineContext.builder().tenantId(1L).userId(2L).commandCode("application:arrive").command(command).request(request).execConfig(Map.of("handler","app:arrive","handlerParams",Map.of("tenantProjectionRead",true))).build();
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("arrive"));context.setTargetRecordReadable(true);
        context.setHandlerInvocationAuthorization(new CommandPipelineContext.HandlerInvocationAuthorization("application:arrive","application","target","update"));
        source=source("applicant","applicant-member","target","application:arrive",hash);
        when(registry.getCommandHandler("app:arrive")).thenReturn(Optional.of(handler));when(scopes.forCommand(context)).thenReturn((m,p,a)->true);
        when(((CommandSubjectAcknowledgementSource)handler).resolveSubjectAcknowledgementSource(any())).thenAnswer(c->{callback=c.getArgument(0);return source;});
        when(directory.supportsStrictApprovalIdentity()).thenReturn(true);when(directory.findEnabledTenantMembership(1L,"operator")).thenReturn(operator);when(directory.findEnabledTenantMembership(1L,"applicant")).thenReturn(applicant);
        when(memberships.requireEnabledMemberships(anyList())).thenReturn(List.of(operator,applicant));
        checks=List.of(new Check("version","version",hash,Status.SATISFIED,true,true,true,true,now.minusSeconds(1),hash,now,now.plusSeconds(60)));
        when(service.checkSubject(eq(1L),eq(4L),eq("applicant"),eq("applicant-member"),anyList())).thenAnswer(c->checks);
    }
    SubjectSource source(String user,String member,String target,String command,String revision){return new SubjectSource(command,"application",target,"application-pid","revision",revision,user,member,
            List.of(new CommandMembershipLockAccessor.MembershipAssertion("operator","operator-member"),new CommandMembershipLockAccessor.MembershipAssertion("applicant","applicant-member")),
            List.of(new PurposeRequirement("operating-policy","version",hash),new PurposeRequirement("arrival-policy","version",hash)));}
    @AfterEach void clear(){TransactionSynchronizationManager.unbindResourceIfPossible(data);TransactionSynchronizationManager.clear();MetaContext.clear();}
    <T>T command(Supplier<T> action){return MetaContext.runWithCommandPermitPlan("TARGET",1L,"application","target",()->MetaContext.runWithAuthorizedCommandCode("application:arrive",action));}
    void commit(){TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false));}
    @Test void hostIssuesOnlyTheOriginalSubjectAndDeduplicatesPurposeVersions(){
        factory.prepare(context);command(()->{try(var scope=factory.forCommand(context)){var proof=scope.accessor().checkSubjectForCommand();assertEquals(source,proof.source());assertEquals(Map.of("operating-policy",0,"arrival-policy",0),proof.purposeIndexes());assertEquals(1,proof.checks().size());}return null;});
        commit();assertEquals("operator",MetaContext.getCurrentUserPid());verify(service,atLeastOnce()).checkSubject(1L,4L,"applicant","applicant-member",List.of(new Requirement("version",hash,"applicant-member")));
        verifyNoInteractions(records);assertFalse(holder.isRollbackOnly());
    }
    @Test void sourceCapabilitiesCannotEscapeTheResolverCallback(){factory.prepare(context);assertThrows(AccessDeniedException.class,()->callback.records().getById("application","target"));assertThrows(AccessDeniedException.class,()->callback.recordScopes().permits("application","target","read"));verifyNoInteractions(records);}
    @Test void noAccessorCanBeIssuedWithoutTheReplayGate(){command(()->{assertThrows(AccessDeniedException.class,()->factory.forCommand(context));return null;});verifyNoInteractions(service);}
    @Test void escapedCapabilitiesFailClosed(){
        factory.prepare(context);command(()->{var scope=factory.forCommand(context);var escaped=scope.accessor();scope.close();assertThrows(AccessDeniedException.class,escaped::checkSubjectForCommand);return null;});assertTrue(holder.isRollbackOnly());
    }
    @ParameterizedTest @ValueSource(strings={"subject","member","target","command","revision"}) void sourceChangesCannotRetargetAnIssuedCapability(String mode){
        factory.prepare(context);source=switch(mode){case "subject"->source("stranger","applicant-member","target","application:arrive",hash);case "member"->source("applicant","replacement","target","application:arrive",hash);case "target"->source("applicant","applicant-member","other","application:arrive",hash);case "command"->source("applicant","applicant-member","target","application:other",hash);default->source("applicant","applicant-member","target","application:arrive","b".repeat(64));};
        assertThrows(AccessDeniedException.class,this::commit);assertTrue(holder.isRollbackOnly());
    }
    @ParameterizedTest @ValueSource(strings={"tenant","operator","holder","connection","no-tx","read-only","scope"}) void originalActorPhysicalTransactionAndScopeAreRequired(String mode){
        factory.prepare(context);command(()->{try(var scope=factory.forCommand(context)){
            switch(mode){case "tenant"->MetaContext.setContext(9L,2L,"operator","Wrong");case "operator"->MetaContext.setMemberId(99L);case "holder"->{TransactionSynchronizationManager.unbindResource(data);var other=new ConnectionHolder(connection);other.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(data,other);}case "connection"->doReturn(mock(Connection.class)).when(holder).getConnection();case "no-tx"->TransactionSynchronizationManager.setActualTransactionActive(false);case "read-only"->TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);case "scope"->{return MetaContext.runWithAuthorizedCommandCode("application:other",()->{assertThrows(AccessDeniedException.class,()->scope.accessor().checkSubjectForCommand());return null;});}}
            assertThrows(AccessDeniedException.class,()->scope.accessor().checkSubjectForCommand());
        }return null;});
    }
    @Test void aNestedNativeFrameCannotUseTheOriginalCapability(){factory.prepare(context);command(()->{try(var scope=factory.forCommand(context)){MetaContext.runWithAuthorizedCommandCode("application:arrive",()->{assertThrows(AccessDeniedException.class,()->scope.accessor().checkSubjectForCommand());return null;});}return null;});}
    @Test void declaredSubjectMustBelongToTheFullLockedParticipants(){source=source("stranger","stranger-member","target","application:arrive",hash);assertThrows(AccessDeniedException.class,()->factory.prepare(context));verifyNoInteractions(service);}
    @Test void originalTargetReadScopeCannotBeReplacedByKnowingItsPid(){when(scopes.forCommand(context)).thenReturn((m,p,a)->false);assertThrows(AccessDeniedException.class,()->factory.prepare(context));verifyNoInteractions(service);}
    @Test void aForeignThreadCannotUseAnEscapedCapability()throws Exception{
        factory.prepare(context);command(()->{try(var scope=factory.forCommand(context)){var executor=java.util.concurrent.Executors.newSingleThreadExecutor();try{executor.submit(()->assertThrows(AccessDeniedException.class,()->scope.accessor().checkSubjectForCommand())).get(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception error){throw new IllegalStateException(error);}finally{executor.shutdownNow();}}return null;});assertFalse(holder.isRollbackOnly());
    }
    @ParameterizedTest @ValueSource(strings={"operator","applicant"}) void disabledMembershipCannotPassReplayGate(String disabled){when(directory.findEnabledTenantMembership(1L,disabled)).thenReturn(null);assertThrows(AccessDeniedException.class,()->factory.prepare(context));verifyNoInteractions(service);}
    @Test void anUnacknowledgedSubjectCannotPassThePreReplayGate(){checks=List.of(new Check("version","version",hash,Status.NOT_ACKNOWLEDGED,true,true,true,true,null,null,now,now.plusSeconds(60)));assertThrows(AccessDeniedException.class,()->factory.prepare(context));assertTrue(holder.isRollbackOnly());}
    @Test void targetScopeAndOriginalInvocationAuthorizationAreMandatory(){context.setHandlerInvocationAuthorization(null);assertThrows(AccessDeniedException.class,()->factory.prepare(context));verifyNoInteractions(service,memberships);}
    @Test void retainedCapabilitiesCannotSurviveTransactionCompletion(){factory.prepare(context);command(()->{try(var scope=factory.forCommand(context)){var capability=scope.accessor();TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(0));assertThrows(AccessDeniedException.class,capability::checkSubjectForCommand);}return null;});}
    @Test void aWrongTargetInTheNativeFrameCannotAcquireTheCapability(){factory.prepare(context);MetaContext.runWithCommandPermitPlan("TARGET",1L,"application","other-target",()->MetaContext.runWithAuthorizedCommandCode("application:arrive",()->{assertThrows(AccessDeniedException.class,()->factory.forCommand(context));return null;}));}
    @Test void callbackRecordsRetainOriginalTenantAndExactTargetBoundaries(){when(records.getById(1L,"application","target")).thenReturn(Map.of("tenant_id",9L,"pid","target"));when(((CommandSubjectAcknowledgementSource)handler).resolveSubjectAcknowledgementSource(any())).thenAnswer(c->{SubjectSourceContext sourceContext=c.getArgument(0);sourceContext.records().getById("application","target");return source;});assertThrows(AccessDeniedException.class,()->factory.prepare(context));verifyNoInteractions(service);}
    @Test void nullSourceOnAnUnrelatedCreateActionDoesNotBecomeASubjectGate(){context.getRequest().setTargetRecordId(null);context.setTargetRecordReadable(false);context.setHandlerInvocationAuthorization(new CommandPipelineContext.HandlerInvocationAuthorization("application:arrive","application",null,null));when(((CommandSubjectAcknowledgementSource)handler).resolveSubjectAcknowledgementSource(any())).thenReturn(null);factory.prepare(context);assertNull(factory.forCommand(context));commit();verifyNoInteractions(service,memberships);}
    @Test void aLateAcknowledgementChangeFailsEvenIfItStillLooksSatisfied(){factory.prepare(context);checks=List.of(new Check("version","version",hash,Status.SATISFIED,true,true,true,true,now,hash,now,now.plusSeconds(60)));assertThrows(AccessDeniedException.class,this::commit);}
    @Test void aLatePolicyVersionChangeFailsBeforeCommit(){factory.prepare(context);source=new SubjectSource(source.commandCode(),source.modelCode(),source.targetRecordPid(),source.applicationPid(),source.revisionPid(),source.revisionHash(),source.subjectUserPid(),source.subjectMemberPid(),source.participants(),List.of(new PurposeRequirement("operating-policy","replacement",hash)));assertThrows(AccessDeniedException.class,this::commit);}
    @Test void evenANullSourceCannotChangeTheOriginalOperatorDuringResolution(){when(((CommandSubjectAcknowledgementSource)handler).resolveSubjectAcknowledgementSource(any())).thenAnswer(c->{MetaContext.setMemberId(99L);return null;});assertThrows(AccessDeniedException.class,()->factory.prepare(context));assertTrue(holder.isRollbackOnly());verifyNoInteractions(service,memberships);}
    @Test void nonSubjectPrimaryHandlersKeepTheirOriginalBehavior(){when(registry.getCommandHandler("app:arrive")).thenReturn(Optional.of(mock(CommandHandlerExtension.class)));factory.prepare(context);assertNull(factory.forCommand(context));verifyNoInteractions(service,memberships,directory);}
}
