package com.auraboot.framework.scheduler.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic delegation boundaries; the actual scheduler, IAM and command pipeline need real-stack proof. */
class ScheduledTaskCommandHandlerTest {
    final ScheduledCommandProperties properties = new ScheduledCommandProperties();
    final UserService users = mock(UserService.class);
    final TenantMemberService members = mock(TenantMemberService.class);
    final UserRoleService roles = mock(UserRoleService.class);
    final CommandService commands = mock(CommandService.class);
    final DynamicDataService data = mock(DynamicDataService.class);
    final CommandExecutor executor = mock(CommandExecutor.class);
    final ScheduledTaskCommandHandler handler = new ScheduledTaskCommandHandler(properties, new ObjectMapper(), users, members, roles, commands, data, executor);
    final ScheduledCommandProperties.Binding binding = new ScheduledCommandProperties.Binding();
    final ScheduledTask task = new ScheduledTask();
    final User actor = new User();
    final TenantMember member = new TenantMember();
    final CommandDefinitionDTO command = new CommandDefinitionDTO();

    @BeforeEach void setup() {
        properties.setEnabled(true);
        binding.setCode("nightly-close"); binding.setTaskPid("task-7"); binding.setTenantId(7L);
        binding.setActorUserPid("actor-7"); binding.setCommandCode("test:sync");
        binding.setModelCode("test_execution"); binding.setTargetRecordPid("execution-7");
        properties.setBindings(List.of(binding));
        task.setPid("task-7"); task.setTenantId(7L); task.setParams("{\"bindingCode\":\"nightly-close\"}");
        task.setHandlerBean("scheduledTaskCommandHandler"); task.setHandlerMethod("execute"); task.setMaxRetries(0);
        actor.setId(41L); actor.setPid("actor-7");
        member.setId(51L); member.setTenantId(7L); member.setUserId(41L); member.setStatus("active"); member.setDeletedFlag(false);
        command.setCode("test:sync"); command.setStatus("published"); command.setModelCode("test_execution");
        when(users.findByPid("actor-7")).thenReturn(actor);
        when(members.findByTenantIdAndUserId(7L,41L)).thenReturn(member);
        when(roles.getRoleIdsByMemberIdAndTenantId(51L,7L)).thenReturn(List.of(61L));
        when(commands.findByCode("test:sync")).thenReturn(command);
        when(data.getById("test_execution","execution-7")).thenReturn(Map.of("pid","execution-7","tenant_id",7L,"row_version",4));
        when(executor.execute(eq("test:sync"), any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed").data(Map.of("closed",2)).build());
        MetaContext.setContext(99L,91L,"previous-actor","previous",Set.of(92L));
    }

    @AfterEach void clear() { MetaContext.clear(); }

    @Test void delegatesExactTargetAndEmptyPayloadWithFreshMembershipAndRestoresCaller() {
        when(executor.execute(eq("test:sync"),any())).thenAnswer(call -> {
            assertEquals(7L,MetaContext.getCurrentTenantId()); assertEquals(41L,MetaContext.getCurrentUserId());
            assertEquals("actor-7",MetaContext.getCurrentUserPid()); assertEquals(Set.of(61L),MetaContext.getCurrentRoleIds());
            assertEquals(51L,MetaContext.getCurrentMemberId()); assertNull(MetaContext.getExternalCommandPermission());
            CommandExecuteRequest request=call.getArgument(1);
            assertEquals("execution-7",request.getTargetRecordPid()); assertEquals(4,request.getExpectedVersion());
            assertEquals(Map.of(),request.getPayload()); assertNotNull(request.getClientRequestId());
            assertEquals("task-7",request.getAuditContext().get("taskPid"));
            return CommandExecuteResult.builder().phaseReached("completed").data(Map.of("closed",2)).build();
        });
        handler.execute(task);
        assertEquals(99L,MetaContext.getCurrentTenantId()); assertEquals("previous-actor",MetaContext.getCurrentUserPid());
        verify(executor,times(1)).execute(eq("test:sync"),any());
    }

    @Test void disabledDelegationCannotReadIdentityOrRunCommand() {
        properties.setEnabled(false); assertThrows(SecurityException.class,()->handler.execute(task));
        verifyNoInteractions(users,members,roles,commands,data,executor);
    }

    @Test void paramsCannotOverrideActorTargetPayloadOrTenant() {
        for(String key:List.of("actorUserPid","targetRecordPid","payload","tenantId")) {
            task.setParams("{\"bindingCode\":\"nightly-close\",\""+key+"\":\"foreign\"}");
            assertThrows(SecurityException.class,()->handler.execute(task));
        }
        verifyNoInteractions(users,members,roles,commands,data,executor);
    }

    @Test void duplicateJsonKeysAndMalformedParametersAreRejected() {
        for(String params:List.of("{}","[]","null","broken","{\"bindingCode\":\"nightly-close\",\"bindingCode\":\"nightly-close\"}")) {
            task.setParams(params); assertThrows(SecurityException.class,()->handler.execute(task));
        }
        verifyNoInteractions(users,members,roles,commands,data,executor);
    }

    @Test void copiedTaskAndOtherTenantCannotUseRegisteredBinding() {
        task.setPid("copied-task"); assertThrows(SecurityException.class,()->handler.execute(task));
        task.setPid("task-7"); task.setTenantId(8L); assertThrows(SecurityException.class,()->handler.execute(task));
        verifyNoInteractions(users,members,roles,commands,data,executor);
    }

    @Test void automaticCommandRetryIsRejected() {
        task.setMaxRetries(1); assertThrows(SecurityException.class,()->handler.execute(task));
        verifyNoInteractions(users,members,roles,commands,data,executor);
    }

    @Test void ambiguousDeploymentAndInvalidIdentifiersAreRejected() {
        properties.setBindings(List.of(binding,binding)); assertThrows(SecurityException.class,()->handler.execute(task));
        properties.setBindings(List.of(binding)); binding.setTargetRecordPid(" ");
        assertThrows(SecurityException.class,()->handler.execute(task));
        verifyNoInteractions(users,members,roles,commands,data,executor);
    }

    @Test void disabledActorAndForeignMembershipCannotDelegate() {
        actor.setEnabled(false); assertThrows(SecurityException.class,()->handler.execute(task));
        actor.setEnabled(true); member.setTenantId(8L); assertThrows(SecurityException.class,()->handler.execute(task));
        verifyNoInteractions(commands,data,executor); assertEquals(99L,MetaContext.getCurrentTenantId());
    }

    @Test void removedOrInactiveMembershipCannotDelegate() {
        member.setDeletedFlag(true); assertThrows(SecurityException.class,()->handler.execute(task));
        member.setDeletedFlag(false); member.setStatus("suspended"); assertThrows(SecurityException.class,()->handler.execute(task));
        verifyNoInteractions(commands,data,executor); assertEquals(99L,MetaContext.getCurrentTenantId());
    }

    @Test void commandMustStillBePublishedOnExactlyTheBoundModel() {
        command.setStatus("draft"); assertThrows(SecurityException.class,()->handler.execute(task));
        command.setStatus("published"); command.setModelCode("foreign_model"); assertThrows(SecurityException.class,()->handler.execute(task));
        verifyNoInteractions(data,executor);
    }

    @Test void targetMustBelongToBoundTenantAndHaveExactVersion() {
        for(Map<String,Object> row:List.<Map<String,Object>>of(
                Map.of("pid","foreign","tenant_id",7L,"row_version",4),
                Map.of("pid","execution-7","tenant_id",8L,"row_version",4),
                Map.of("pid","execution-7","tenant_id",7L),
                Map.of("pid","execution-7","tenant_id",7L,"row_version",-1),
                Map.of("pid","execution-7","tenant_id",7L,"row_version","4.5"))) {
            when(data.getById("test_execution","execution-7")).thenReturn(row);
            assertThrows(SecurityException.class,()->handler.execute(task));
        }
        verifyNoInteractions(executor);
    }

    @Test void pipelinePermissionFailureIsNotRetriedOrReportedSuccessful() {
        when(executor.execute(eq("test:sync"),any())).thenThrow(new SecurityException("denied"));
        assertThrows(SecurityException.class,()->handler.execute(task));
        verify(executor,times(1)).execute(eq("test:sync"),any()); assertEquals(99L,MetaContext.getCurrentTenantId());
    }

    @Test void incompletePipelineResultDoesNotBecomeSuccessfulTask() {
        when(executor.execute(eq("test:sync"),any())).thenReturn(CommandExecuteResult.builder().phaseReached("state-check").build());
        assertThrows(IllegalStateException.class,()->handler.execute(task)); assertEquals(99L,MetaContext.getCurrentTenantId());
    }

    @Test void deploymentCannotContainMissingOrArbitraryAuthoritySelectors() {
        List<Consumer<ScheduledTaskCommandHandlerTest>> invalid=List.of(
                f->f.properties.setBindings(null),f->f.properties.setBindings(List.of()),
                f->f.properties.setBindings(java.util.Collections.nCopies(129,f.binding)),
                f->f.properties.setBindings(java.util.Arrays.asList((ScheduledCommandProperties.Binding)null)),
                f->f.binding.setCode(null),f->f.binding.setCode("INVALID"),f->f.binding.setTaskPid(null),
                f->f.binding.setTenantId(null),f->f.binding.setTenantId(0L),f->f.binding.setActorUserPid(" "),
                f->f.binding.setCommandCode("not-a-command"),f->f.binding.setCommandCode("a:"+"b".repeat(200)),
                f->f.binding.setModelCode("other/model"),f->f.binding.setTargetRecordPid(null),
                f->{var duplicate=new ScheduledCommandProperties.Binding();duplicate.setCode("another");duplicate.setTaskPid("task-7");duplicate.setTenantId(7L);duplicate.setActorUserPid("actor-7");duplicate.setCommandCode("test:sync");duplicate.setModelCode("test_execution");duplicate.setTargetRecordPid("execution-7");f.properties.setBindings(List.of(f.binding,duplicate));});
        for(var patch:invalid) {
            var f=new ScheduledTaskCommandHandlerTest();f.setup();patch.accept(f);
            assertThrows(SecurityException.class,()->f.handler.execute(f.task));verifyNoInteractions(f.users,f.executor);
        }
    }

    @Test void taskCannotRedirectOrSmuggleParametersThroughAlternateEntrypoints() {
        List<Consumer<ScheduledTaskCommandHandlerTest>> invalid=List.of(
                f->f.task.setHandlerBean("anotherBean"),f->f.task.setHandlerMethod("otherMethod"),
                f->f.task.setParams(null),f->f.task.setParams(" ".repeat(513)),
                f->f.task.setParams("{\"bindingCode\":\"unregistered\"}"),f->f.task.setParams("{\"bindingCode\":7}"));
        for(var patch:invalid) {
            var f=new ScheduledTaskCommandHandlerTest();f.setup();patch.accept(f);
            assertThrows(SecurityException.class,()->f.handler.execute(f.task));verifyNoInteractions(f.users,f.executor);
        }
        assertThrows(SecurityException.class,()->handler.execute(null));
        try(var existing=com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.open()) {
            assertThrows(SecurityException.class,()->handler.execute(task));verifyNoInteractions(users,executor);
        }
    }

    @Test void lostUserAndExpiredAccountsCannotRetainScheduledAuthority() {
        List<Consumer<ScheduledTaskCommandHandlerTest>> invalid=List.of(
                f->when(f.users.findByPid("actor-7")).thenReturn(null),f->f.actor.setId(null),f->f.actor.setId(0L),
                f->f.actor.setPid("other"),f->f.actor.setDeletedFlag(true),f->f.actor.setAccountNonExpired(false),
                f->f.actor.setAccountNonLocked(false),f->f.actor.setCredentialsNonExpired(false),
                f->when(f.members.findByTenantIdAndUserId(7L,41L)).thenReturn(null),
                f->f.member.setId(null),f->f.member.setId(0L),f->f.member.setUserId(42L));
        for(var patch:invalid) {
            var f=new ScheduledTaskCommandHandlerTest();f.setup();patch.accept(f);
            assertThrows(SecurityException.class,()->f.handler.execute(f.task));
            verifyNoInteractions(f.commands,f.data,f.executor);assertEquals(99L,MetaContext.getCurrentTenantId());
        }
    }

    @Test void metadataAndTargetDisappearanceRemainFailures() {
        when(commands.findByCode("test:sync")).thenReturn(null);
        assertThrows(SecurityException.class,()->handler.execute(task));
        when(commands.findByCode("test:sync")).thenReturn(command);command.setCode("other:command");
        assertThrows(SecurityException.class,()->handler.execute(task));command.setCode("test:sync");
        when(data.getById("test_execution","execution-7")).thenReturn(null);
        assertThrows(SecurityException.class,()->handler.execute(task));verifyNoInteractions(executor);
    }

    @Test void noCurrentRolesCannotReuseTheCallersRolesAndNullResultIsNotSuccess() {
        when(roles.getRoleIdsByMemberIdAndTenantId(51L,7L)).thenReturn(null);
        when(executor.execute(eq("test:sync"),any())).thenAnswer(call->{assertTrue(MetaContext.getCurrentRoleIds().isEmpty());throw new SecurityException("full pipeline denied");});
        assertThrows(SecurityException.class,()->handler.execute(task));assertEquals(Set.of(92L),MetaContext.getCurrentRoleIds());
        when(executor.execute(eq("test:sync"),any())).thenReturn(null);
        assertThrows(IllegalStateException.class,()->handler.execute(task));assertEquals(99L,MetaContext.getCurrentTenantId());
    }
}
