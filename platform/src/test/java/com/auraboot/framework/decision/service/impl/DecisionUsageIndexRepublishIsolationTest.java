package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.decision.dto.DecisionUsageIndexRebuildDTO;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyVersionMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.plugin.pf4j.DecisionUsageSourceRegistry;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The product-source pull must never touch the caller's transaction: contributors
 * run raw product SQL, and on PostgreSQL any failure there aborts the caller's
 * whole transaction (reproduced as plugin imports dying on a fresh database where
 * the staged BPM module had no tables). These tests pin the orchestration: the
 * projection swap in REQUIRES_NEW, the pull deferred to after-commit on a worker
 * thread — never on the caller's still-bound connection.
 */
class DecisionUsageIndexRepublishIsolationTest {

    private final DecisionUsageRefMapper usageRefMapper = mock(DecisionUsageRefMapper.class);
    private final DrtVersionMapper versionMapper = mock(DrtVersionMapper.class);
    private final AutomationMapper automationMapper = mock(AutomationMapper.class);
    private final DrtPolicyVersionMapper policyVersionMapper = mock(DrtPolicyVersionMapper.class);
    private final DrtPolicyDefinitionMapper policyDefinitionMapper = mock(DrtPolicyDefinitionMapper.class);
    private final NamedQueryMapper namedQueryMapper = mock(NamedQueryMapper.class);
    private final RolePermissionMapper rolePermissionMapper = mock(RolePermissionMapper.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DecisionUsageSourceRegistry> registryProvider = mock(ObjectProvider.class);
    private final DecisionUsageSourceRegistry registry = mock(DecisionUsageSourceRegistry.class);

    private DecisionUsageIndexServiceImpl service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        service = new DecisionUsageIndexServiceImpl(usageRefMapper, versionMapper, automationMapper,
                policyVersionMapper, policyDefinitionMapper, namedQueryMapper, rolePermissionMapper,
                permissionMapper, new ObjectMapper(), transactionManager, registryProvider);
        MetaContext.setContext(123L, 456L, "user", "owner");
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        MetaContext.clear();
    }

    @Test
    void rebuildRunsCoreInRequiresNewTransactionAndDefersPullUntilAfterCommit() {
        doAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            // A caller-side transaction (e.g. a plugin import) must never see the
            // core's statements, and the pull's product SQL must never poison it.
            assertThat(definition.getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return transactionStatus;
        }).when(transactionManager).getTransaction(any());
        TransactionSynchronizationManager.initSynchronization();

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        // Inside the caller's transaction the pull is deferred, not executed.
        assertThat(summary).isNotNull();
        verify(registry, never()).republishAll();

        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        // The pull runs off the caller's thread (afterCommit leaves the caller's
        // connection thread-bound, so it must not be reused) — await its landing.
        verify(registry, timeout(5000)).republishAll();
    }

    @Test
    void productSourcePullFailureDoesNotFailTheRebuild() {
        when(registry.republishAll()).thenThrow(
                new RuntimeException("relation \"ab_sla_config\" does not exist"));

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        // No caller-side transaction here, so the pull is inline; its failure is
        // the registry's own best-effort contract and must degrade to a warn,
        // never bubble into the caller (a plugin import).
        assertThat(summary).isNotNull();
        verify(registry).republishAll();
    }

    @Test
    void rebuildWithoutRegistryPresentStillCompletes() {
        when(registryProvider.getIfAvailable()).thenReturn(null);

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        assertThat(summary).isNotNull();
        verify(registry, never()).republishAll();
    }
}
