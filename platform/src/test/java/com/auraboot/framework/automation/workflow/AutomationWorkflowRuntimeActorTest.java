package com.auraboot.framework.automation.workflow;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutomationWorkflowRuntimeActorTest {
    private final WorkflowCapabilityRegistry registry = mock(WorkflowCapabilityRegistry.class);
    private final AutomationFlowCompiler compiler = mock(AutomationFlowCompiler.class);
    private final AutomationWorkflowRuntime runtime = new AutomationWorkflowRuntime(compiler, registry, new ObjectMapper(),
            new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("actions",
                    mock(com.auraboot.framework.automation.executor.ActionExecutor.class)))
                    .getBeanProvider(com.auraboot.framework.automation.executor.ActionExecutor.class), actorScope());

    @AfterEach void clear() { MetaContext.clear(); }

    private Automation automation() {
        Automation automation = new Automation();
        automation.setPid("owned-automation"); automation.setTenantId(12L);
        lenient().when(compiler.compile(automation)).thenReturn(new AutomationFlowCompiler.CompiledFlow("auto_owned", null, Map.of()));
        return automation;
    }
    private void actor() { MetaContext.setContext(12L, 34L, "actor-pid", "operator"); MetaContext.setMemberId(56L); }

    @Test void propagatesOnlyTheTrustedActorEvenWhenTriggerPayloadForgeriesExist() {
        actor();
        when(registry.execute(eq("automation.run"), any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(1, WorkflowCapability.WorkflowRequest.class);
            assertThat(request.tenantId()).isEqualTo(12L);
            assertThat(request.actorUserId()).isEqualTo(34L);
            assertThat(request.payload().get("automationActor")).isEqualTo(Map.of("tenantId", 12L, "userId", 34L, "userPid", "actor-pid", "memberId", 56L, "username", "operator"));
            return WorkflowCapability.WorkflowResult.empty();
        });
        runtime.run(automation(), "record-pid", Map.of("automationActor", Map.of("userId", 0L), "_automation_user_id", 0L), 8L);
        verify(registry).execute(eq("automation.run"), any());
    }
    @Test void failsClosedWithoutAnActorContext() {
        assertThatThrownBy(() -> runtime.run(automation(), null, Map.of(), null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(registry);
    }
    @Test void failsClosedForAnActorFromAnotherTenant() {
        MetaContext.setContext(99L, 34L, "actor-pid", "operator"); MetaContext.setMemberId(56L);
        assertThatThrownBy(() -> runtime.run(automation(), null, Map.of(), null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(registry);
    }
    @Test void failsClosedWithoutAnActiveTenantMember() {
        MetaContext.setContext(12L, 34L, "actor-pid", "operator");
        assertThatThrownBy(() -> runtime.run(automation(), null, Map.of(), null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(registry);
    }
    @Test void rejectsSyntheticZeroActor() {
        MetaContext.setContext(12L, 0L, "automation:owned", "automation"); MetaContext.setMemberId(56L);
        assertThatThrownBy(() -> runtime.run(automation(), null, Map.of(), null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(registry);
    }
    @Test void rollsBackFailedBusinessWorkWhileTheEnclosingFailureAuditCanCommit() {
        actor();
        Automation automation = automation();
        RecordingTransactions manager = new RecordingTransactions();
        ProxyFactory proxyFactory = new ProxyFactory(runtime);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        AutomationWorkflowRuntime proxied = (AutomationWorkflowRuntime) proxyFactory.getProxy();
        when(registry.execute(eq("automation.run"), any())).thenAnswer(invocation -> {
            // A nested REQUIRED command marks its enclosing action transaction rollback-only.
            new TransactionTemplate(manager).execute(status -> {
                manager.write("business mutation");
                throw new IllegalStateException("Command permission denied");
            });
            return WorkflowCapability.WorkflowResult.empty();
        });
        new TransactionTemplate(manager).execute(status -> {
            manager.write("automation log inserted");
            assertThatThrownBy(() -> proxied.run(automation, "record", Map.of(), 8L))
                    .isInstanceOf(AutomationWorkflowRuntime.AutomationWorkflowRunException.class);
            manager.write("automation log failed");
            return null;
        });
        assertThat(manager.committed).containsExactly("automation log inserted", "automation log failed");
        assertThat(manager.rolledBack).containsExactly("business mutation");
    }

    /** Hermetic transaction resource: real Spring propagation, no database integration claim. */
    static class RecordingTransactions extends AbstractPlatformTransactionManager {
        final ThreadLocal<Resource> current = new ThreadLocal<>();
        final List<String> committed = new ArrayList<>();
        final List<String> rolledBack = new ArrayList<>();
        void write(String value) { current.get().writes.add(value); }
        @Override protected Object doGetTransaction() { return new Holder(current.get()); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Holder) transaction).resource != null; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            Resource resource = new Resource(); ((Holder) transaction).resource = resource; current.set(resource);
        }
        @Override protected Object doSuspend(Object transaction) {
            Holder holder = (Holder) transaction; Resource previous = holder.resource; holder.resource = null; current.remove(); return previous;
        }
        @Override protected void doResume(Object transaction, Object suspendedResources) {
            ((Holder) transaction).resource = (Resource) suspendedResources; current.set((Resource) suspendedResources);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed.addAll(((Holder) status.getTransaction()).resource.writes); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack.addAll(((Holder) status.getTransaction()).resource.writes); }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { ((Holder) status.getTransaction()).resource.rollback = true; }
        @Override protected void doCleanupAfterCompletion(Object transaction) { current.remove(); }
        static class Resource { boolean rollback; final List<String> writes = new ArrayList<>(); }
        static class Holder implements SmartTransactionObject {
            Resource resource;
            Holder(Resource resource) { this.resource = resource; }
            @Override public boolean isRollbackOnly() { return resource != null && resource.rollback; }
            @Override public void flush() { }
        }
    }

    private static com.auraboot.framework.permission.service.HumanActorContext actorScope() {
        var actors = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.HumanActorContext.class);
        org.mockito.Mockito.lenient().when(actors.call(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(4, java.util.function.Supplier.class).get());
        return actors;
    }

    private AutomationWorkflowRuntime runtimeWith(com.auraboot.framework.permission.service.HumanActorContext actors) {
        return new AutomationWorkflowRuntime(compiler, registry, new ObjectMapper(),
                new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("actions",
                        mock(com.auraboot.framework.automation.executor.ActionExecutor.class)))
                        .getBeanProvider(com.auraboot.framework.automation.executor.ActionExecutor.class), actors);
    }

    @Test void aRevokedHumanCannotReachTheWorkflowProductEvenWithACompleteCapturedTuple() {
        actor();
        var principals = mock(com.auraboot.framework.permission.mapper.HumanActorMapper.class);
        when(principals.findActive(12L, 34L, "actor-pid")).thenReturn(List.of());
        var actors = new com.auraboot.framework.permission.service.HumanActorContext(principals,
                mock(com.auraboot.framework.rbac.mapper.RoleMapper.class));
        assertThatThrownBy(() -> runtimeWith(actors).run(automation(), null, Map.of(), null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(registry);
    }

    @Test void workflowHandoffUsesFreshRolesAndRestoresTheCallingThread() {
        MetaContext.setContext(12L, 34L, "actor-pid", "operator", java.util.Set.of(999L)); MetaContext.setMemberId(56L);
        var before = MetaContext.snapshot();
        var principals = mock(com.auraboot.framework.permission.mapper.HumanActorMapper.class);
        var roles = mock(com.auraboot.framework.rbac.mapper.RoleMapper.class);
        when(principals.findActive(12L, 34L, "actor-pid")).thenReturn(List.of(Map.of(
                "user_id", 34L, "user_pid", "actor-pid", "member_id", 56L, "username", "current operator")));
        var role = new com.auraboot.framework.rbac.entity.Role(); role.setId(77L);
        when(roles.findByMemberIdAndTenantId(56L, 12L)).thenReturn(List.of(role));
        when(registry.execute(eq("automation.run"), any())).thenAnswer(invocation -> {
            assertThat(MetaContext.getCurrentRoleIds()).containsExactly(77L);
            assertThat(MetaContext.hasCommandPermitScope()).isFalse();
            var request = invocation.getArgument(1, WorkflowCapability.WorkflowRequest.class);
            assertThat((Map<String,Object>)request.payload().get("automationActor")).containsValue("current operator");
            return WorkflowCapability.WorkflowResult.empty();
        });
        runtimeWith(new com.auraboot.framework.permission.service.HumanActorContext(principals, roles))
                .run(automation(), null, Map.of(), null);
        assertThat(MetaContext.snapshot()).isEqualTo(before);
    }
}
