package com.auraboot.framework.bpm.config;

import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.service.AssigneeResolverService;
import com.auraboot.framework.bpm.service.BpmRuleBindingRuntimeService;
import com.auraboot.framework.bpm.service.BpmRuleBindingRuntimeService.TaskAssignmentResult;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.smart.framework.engine.constant.AssigneeTypeConstant;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.Activity;
import com.auraboot.smart.framework.engine.model.assembly.ExtensionElementContainer;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdAndGroupTaskAssigneeDispatcherRuleBindingTest {

    interface RuleBoundActivity extends Activity, ExtensionElementContainer {}

    @Test
    void ruleBindingAssigneesOverrideStaticFallback() {
        AssigneeResolverService assigneeResolverService = mock(AssigneeResolverService.class);
        BpmExtensionAccessor accessor = mock(BpmExtensionAccessor.class);
        BpmRuleBindingRuntimeService runtime = mock(BpmRuleBindingRuntimeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmExtensionAccessor> accessorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmRuleBindingRuntimeService> runtimeProvider = mock(ObjectProvider.class);
        IdAndGroupTaskAssigneeDispatcher dispatcher =
                new IdAndGroupTaskAssigneeDispatcher(
                        assigneeResolverService, accessorProvider, runtimeProvider);
        RuleBoundActivity activity = mock(RuleBoundActivity.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        RuleConsumerBinding binding = new RuleConsumerBinding(
                "BPM", "expense", "approve", RuleBindingKind.DECISION_REF, null, null, true);
        HashMap<String, Object> request = new HashMap<>();

        when(activity.getId()).thenReturn("approve");
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(context.getRequest()).thenReturn(request);
        when(processInstance.getProcessDefinitionId()).thenReturn("expense");
        when(processInstance.getInstanceId()).thenReturn("pi-1");
        when(accessorProvider.getIfAvailable()).thenReturn(accessor);
        when(runtimeProvider.getIfAvailable()).thenReturn(runtime);
        when(accessor.getRuleConsumerBinding(activity, "expense", "approve"))
                .thenReturn(Optional.of(binding));
        when(runtime.resolveTaskAssignment(binding, "expense", "approve", "pi-1", request))
                .thenReturn(new TaskAssignmentResult(List.of("u-rule-1", "u-rule-2"), List.of(), false, null));

        var candidates = dispatcher.getTaskAssigneeCandidateInstance(activity, context);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getAssigneeId()).isEqualTo("u-rule-1");
        assertThat(candidates.get(1).getAssigneeId()).isEqualTo("u-rule-2");
        assertThat(candidates).extracting("assigneeType")
                .containsExactly(AssigneeTypeConstant.USER, AssigneeTypeConstant.USER);
    }

    @Test
    void ruleBindingCanResolveCandidateGroups() {
        AssigneeResolverService assigneeResolverService = mock(AssigneeResolverService.class);
        BpmExtensionAccessor accessor = mock(BpmExtensionAccessor.class);
        BpmRuleBindingRuntimeService runtime = mock(BpmRuleBindingRuntimeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmExtensionAccessor> accessorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmRuleBindingRuntimeService> runtimeProvider = mock(ObjectProvider.class);
        IdAndGroupTaskAssigneeDispatcher dispatcher =
                new IdAndGroupTaskAssigneeDispatcher(
                        assigneeResolverService, accessorProvider, runtimeProvider);
        RuleBoundActivity activity = mock(RuleBoundActivity.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        RuleConsumerBinding binding = new RuleConsumerBinding(
                "BPM", "expense", "approve", RuleBindingKind.DECISION_REF, null, null, true);
        HashMap<String, Object> request = new HashMap<>();

        when(activity.getId()).thenReturn("approve");
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(context.getRequest()).thenReturn(request);
        when(processInstance.getProcessDefinitionId()).thenReturn("expense");
        when(processInstance.getInstanceId()).thenReturn("pi-2");
        when(accessorProvider.getIfAvailable()).thenReturn(accessor);
        when(runtimeProvider.getIfAvailable()).thenReturn(runtime);
        when(accessor.getRuleConsumerBinding(activity, "expense", "approve"))
                .thenReturn(Optional.of(binding));
        when(runtime.resolveTaskAssignment(binding, "expense", "approve", "pi-2", request))
                .thenReturn(new TaskAssignmentResult(
                        List.of("u-rule-1"),
                        List.of("finance-managers", "risk-owners"),
                        false,
                        null));

        var candidates = dispatcher.getTaskAssigneeCandidateInstance(activity, context);

        assertThat(candidates).extracting("assigneeId")
                .containsExactly("u-rule-1", "finance-managers", "risk-owners");
        assertThat(candidates).extracting("assigneeType")
                .containsExactly(
                        AssigneeTypeConstant.USER,
                        AssigneeTypeConstant.GROUP,
                        AssigneeTypeConstant.GROUP);
    }

    @Test
    void failClosedRuleBindingDoesNotFallBackToProcessStarter() {
        AssigneeResolverService assigneeResolverService = mock(AssigneeResolverService.class);
        BpmExtensionAccessor accessor = mock(BpmExtensionAccessor.class);
        BpmRuleBindingRuntimeService runtime = mock(BpmRuleBindingRuntimeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmExtensionAccessor> accessorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BpmRuleBindingRuntimeService> runtimeProvider = mock(ObjectProvider.class);
        IdAndGroupTaskAssigneeDispatcher dispatcher =
                new IdAndGroupTaskAssigneeDispatcher(
                        assigneeResolverService, accessorProvider, runtimeProvider);
        RuleBoundActivity activity = mock(RuleBoundActivity.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        RuleConsumerBinding binding = new RuleConsumerBinding(
                "BPM", "expense", "approve", RuleBindingKind.DECISION_REF, null, null, true);
        HashMap<String, Object> request = new HashMap<>();
        request.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, "starter-1");

        when(activity.getId()).thenReturn("approve");
        when(context.getProcessInstance()).thenReturn(processInstance);
        when(context.getRequest()).thenReturn(request);
        when(processInstance.getProcessDefinitionId()).thenReturn("expense");
        when(processInstance.getInstanceId()).thenReturn("pi-3");
        when(accessorProvider.getIfAvailable()).thenReturn(accessor);
        when(runtimeProvider.getIfAvailable()).thenReturn(runtime);
        when(accessor.getRuleConsumerBinding(activity, "expense", "approve"))
                .thenReturn(Optional.of(binding));
        when(runtime.resolveTaskAssignment(binding, "expense", "approve", "pi-3", request))
                .thenReturn(new TaskAssignmentResult(List.of(), List.of(), true, null));

        var candidates = dispatcher.getTaskAssigneeCandidateInstance(activity, context);

        assertThat(candidates).isEmpty();
    }
}
