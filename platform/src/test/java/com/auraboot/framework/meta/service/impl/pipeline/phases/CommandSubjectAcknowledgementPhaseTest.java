package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.pf4j.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual invocation/subject/idempotency phases in the real pipeline; hermetic host boundary doubles. */
class CommandSubjectAcknowledgementPhaseTest {
    final ExtensionRegistry registry=mock(ExtensionRegistry.class);
    final AnnouncementSubjectAcknowledgementAccessorFactory factory=mock(AnnouncementSubjectAcknowledgementAccessorFactory.class);
    final IdempotencyService cache=mock(IdempotencyService.class);
    final CommandHandlerExtension handler=mock(CommandHandlerExtension.class,withSettings().extraInterfaces(CommandInvocationAuthorizer.class));
    final CommandInvocationAuthorizationPhase invocation=new CommandInvocationAuthorizationPhase(registry);
    final CommandSubjectAcknowledgementPhase subject=new CommandSubjectAcknowledgementPhase(factory);
    final IdempotencyPhase idempotency=new IdempotencyPhase(cache);
    final CommandPipeline pipeline=new CommandPipeline(List.of(invocation),List.of(subject,idempotency));
    @BeforeEach void setup(){
        MetaContext.setContext(1L,2L,"operator","Operator");MetaContext.setMemberId(3L);
        when(registry.getCommandHandler("app:arrive")).thenReturn(Optional.of(handler));when(((CommandInvocationAuthorizer)handler).authorizeInvocation(any())).thenReturn(true);
        when(cache.claimScopedIdempotency(anyString(),anyString(),anyMap(),anyLong())).thenReturn(Map.of("visitPid","original-visit"));
    }
    @AfterEach void clear(){MetaContext.clear();}
    CommandPipelineContext context(){
        var command=new CommandDefinition();command.setCode("application:arrive");command.setModelCode("application");var request=new CommandExecuteRequest();request.setTargetRecordId("original-target");request.setClientRequestId("original-request-key");
        return CommandPipelineContext.builder().tenantId(1L).userId(2L).commandCode("application:arrive").command(command).request(request).execConfig(Map.of("handler","app:arrive","handlerParams",Map.of("invocationAuthorization","handler"))).build();
    }
    void run(CommandPipelineContext context){assertNull(pipeline.executePreGuardPhases(context));pipeline.executeGuardedPhases(context);}
    @Test void cachedOutcomeFollowsBothCurrentAuthorityGates(){
        var context=context();doAnswer(c->{assertNotNull(context.getHandlerInvocationAuthorization());return null;}).when(factory).prepare(context);
        run(context);assertTrue(context.isShortCircuited());assertTrue(context.getShortCircuitResult().isIdempotentReplay());assertEquals("original-visit",context.getShortCircuitResult().getData().get("visitPid"));
        var order=inOrder(handler,factory,cache);order.verify((CommandInvocationAuthorizer)handler).authorizeInvocation(any());order.verify(factory).prepare(context);order.verify(cache).claimScopedIdempotency(eq("original-request-key"),eq("application:arrive"),anyMap(),eq(1L));
    }
    @Test void replayCannotBypassChangedOriginalSubjectAuthority(){
        run(context());reset(cache);doThrow(new AccessDeniedException("subject disabled or policy changed")).when(factory).prepare(any());
        assertThrows(AccessDeniedException.class,()->run(context()));verifyNoInteractions(cache);verify((CommandInvocationAuthorizer)handler,times(2)).authorizeInvocation(any());
    }
    @Test void actualHandlerDeliveryInjectsAndClosesOnlyTheHostIssuedSubjectAccessor()throws Exception{
        var primary=mock(CommandHandlerExtension.class,withSettings().extraInterfaces(CommandSubjectAcknowledgementSource.class));
        when(registry.getCommandHandler("app:arrive")).thenReturn(Optional.of(primary));
        var scope=mock(AnnouncementSubjectAcknowledgementAccessorFactory.Scope.class);
        var checked=mock(AnnouncementSubjectAcknowledgementAccessor.SubjectChecks.class);
        when(scope.accessor()).thenReturn(()->checked);when(factory.forCommand(any())).thenReturn(scope);
        when(primary.execute(any())).thenAnswer(call->{CommandHandlerExtension.CommandContext original=call.getArgument(0);assertEquals("operator",original.currentUserPid());assertSame(checked,original.announcementSubjectAcknowledgementAccessor().checkSubjectForCommand());return Map.of("subjectChecked",true);});
        var phase=mock(HandlerPhase.class,CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(phase,"extensionRegistry",registry);
        org.springframework.test.util.ReflectionTestUtils.setField(phase,"subjectAcknowledgements",factory);
        var context=context();var results=new HashMap<String,Object>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(phase,"executePluginCommandHandler",context.getCommandCode(),"application",Map.of(),1L,2L,context.getRequest(),Map.of(),results,context.getExecConfig(),false,context);
        assertEquals(true,results.get("subjectChecked"));verify(scope).close();verify(factory).forCommand(context);
    }
    @Test void actualHandlerDeliveryCannotSilentlyOmitTheRequiredHostFactory(){
        var primary=mock(CommandHandlerExtension.class,withSettings().extraInterfaces(CommandSubjectAcknowledgementSource.class));when(registry.getCommandHandler("app:arrive")).thenReturn(Optional.of(primary));
        var phase=mock(HandlerPhase.class,CALLS_REAL_METHODS);org.springframework.test.util.ReflectionTestUtils.setField(phase,"extensionRegistry",registry);var context=context();
        assertThrows(AccessDeniedException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(phase,"executePluginCommandHandler",context.getCommandCode(),"application",Map.of(),1L,2L,context.getRequest(),Map.of(),new HashMap<>(),context.getExecConfig(),false,context));
    }
    @Test void ordinaryInvocationDenialStillPrecedesSubjectAndReplay(){when(((CommandInvocationAuthorizer)handler).authorizeInvocation(any())).thenReturn(false);assertThrows(AccessDeniedException.class,()->run(context()));verifyNoInteractions(factory,cache);}
}
