package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.dto.DecisionActionCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionActionDTO;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionActionCatalogServiceImplTest {

    @Test
    void catalogIncludesProductizedMessagingTaskAndCollaborationActions() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ActionHandler> actionHandlers = mock(ObjectProvider.class);
        when(actionHandlers.stream()).thenAnswer(invocation -> Stream.empty());

        DecisionActionCatalogDTO catalog = new DecisionActionCatalogServiceImpl(actionHandlers).getActionCatalog();
        Map<String, DecisionActionDTO> byType = catalog.getActions().stream()
                .collect(Collectors.toMap(DecisionActionDTO::getActionType, Function.identity()));

        assertThat(byType.keySet()).contains(
                "NOTIFY",
                "SEND_SMS",
                "SEND_IM",
                "WEBHOOK",
                "START_PROCESS",
                "CREATE_TASK",
                "CC_TASK",
                "ADD_COMMENT",
                "UPDATE_RECORD",
                "PATCH_RECORD",
                "WRITE_AUDIT");
        assertThat(byType.get("SEND_SMS").getLabel()).isEqualTo("发送短信");
        assertThat(byType.get("SEND_IM").getLabel()).isEqualTo("发送 IM 消息");
        assertThat(byType.get("CREATE_TASK").getCategory()).isEqualTo("workflow");
        assertThat(byType.get("CC_TASK").getCategory()).isEqualTo("collaboration");
        assertThat(byType.get("SEND_SMS").getHandlerAvailable()).isFalse();
        assertThat(byType.get("SEND_SMS").getAvailabilityStatus()).isEqualTo("UNAVAILABLE");
        assertThat(byType.get("SEND_SMS").getAvailabilityReason()).contains("真实短信 provider");
        assertThat(byType.get("SEND_SMS").getConsumerTypes())
                .containsExactly("SLA", "EVENT_POLICY", "AUTOMATION", "BPM");
        assertThat(byType.get("SEND_SMS").getConsumerAvailability())
                .extracting("consumerType")
                .containsExactly("SLA", "EVENT_POLICY", "AUTOMATION", "BPM");
        assertThat(byType.get("SEND_SMS").getConsumerAvailability())
                .allSatisfy(availability -> {
                    assertThat(availability.getHandlerAvailable()).isFalse();
                    assertThat(availability.getAvailabilityStatus()).isEqualTo("UNAVAILABLE");
                    assertThat(availability.getAvailabilityReason()).contains("真实短信 provider");
                });
        assertThat(byType.get("NOTIFY").getAvailabilityReason()).isEqualTo("当前环境未注册动作处理器");
        @SuppressWarnings("unchecked")
        Map<String, Object> startProcessSchema = byType.get("START_PROCESS").getInputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> startProcessFields = (Map<String, Object>) startProcessSchema.get("fields");
        assertThat(startProcessFields.get("payload.processDefinitionId"))
                .isInstanceOfSatisfying(Map.class, field ->
                        assertThat(field).containsEntry("label", "流程标识"));
        assertThat(startProcessFields.get("payload.businessKey"))
                .isInstanceOfSatisfying(Map.class, field ->
                        assertThat(field).containsEntry("label", "业务主键，默认使用业务记录 PID"));
    }

    @Test
    void handlerAvailabilityHonorsRuntimeAvailability() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ActionHandler> actionHandlers = mock(ObjectProvider.class);
        ActionProviderDependency smsProviderDependency = ActionProviderDependency.of(
                "SMS",
                List.of(),
                "真实短信 provider",
                true,
                false,
                "当前环境未配置真实短信 provider");
        ActionHandler smsUnavailable = testHandler("SEND_SMS", false, List.of(smsProviderDependency));
        ActionHandler imAvailable = testHandler("SEND_IM", true);
        when(actionHandlers.stream()).thenAnswer(invocation -> Stream.of(smsUnavailable, imAvailable));

        DecisionActionCatalogDTO catalog = new DecisionActionCatalogServiceImpl(actionHandlers).getActionCatalog();
        Map<String, DecisionActionDTO> byType = catalog.getActions().stream()
                .collect(Collectors.toMap(DecisionActionDTO::getActionType, Function.identity()));

        assertThat(byType.get("SEND_SMS").getHandlerAvailable()).isFalse();
        assertThat(byType.get("SEND_SMS").getAvailabilityStatus()).isEqualTo("UNAVAILABLE");
        assertThat(byType.get("SEND_SMS").getAvailabilityReason()).contains("真实短信 provider");
        assertThat(byType.get("SEND_SMS").getProviderDependencies())
                .singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.getProviderType()).isEqualTo("SMS");
                    assertThat(dependency.getLabel()).isEqualTo("真实短信 provider");
                    assertThat(dependency.getRequired()).isTrue();
                    assertThat(dependency.getAvailable()).isFalse();
                    assertThat(dependency.getAvailabilityStatus()).isEqualTo("UNAVAILABLE");
                    assertThat(dependency.getAvailabilityReason()).contains("真实短信 provider");
                });
        assertThat(byType.get("SEND_SMS").getConsumerAvailability())
                .allSatisfy(availability -> assertThat(availability.getProviderDependencies())
                        .singleElement()
                        .satisfies(dependency -> assertThat(dependency.getProviderType()).isEqualTo("SMS")));
        assertThat(byType.get("SEND_IM").getHandlerAvailable()).isTrue();
        assertThat(byType.get("SEND_IM").getAvailabilityStatus()).isEqualTo("AVAILABLE");
        assertThat(byType.get("SEND_IM").getAvailabilityReason()).isNull();
        assertThat(byType.get("SEND_IM").getProviderDependencies()).isEmpty();
        assertThat(byType.get("SEND_IM").getConsumerTypes())
                .containsExactly("SLA", "EVENT_POLICY", "AUTOMATION", "BPM");
        assertThat(byType.get("SEND_IM").getConsumerAvailability())
                .allSatisfy(availability -> {
                    assertThat(availability.getHandlerAvailable()).isTrue();
                    assertThat(availability.getAvailabilityStatus()).isEqualTo("AVAILABLE");
                    assertThat(availability.getAvailabilityReason()).isNull();
                });
    }

    @Test
    void catalogPropagatesProviderDependencyMatrixToEveryConsumerAvailabilityRow() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ActionHandler> actionHandlers = mock(ObjectProvider.class);
        when(actionHandlers.stream()).thenAnswer(invocation -> Stream.of(
                testHandler("NOTIFY", true, List.of(dep("NOTIFICATION", "站内通知服务"))),
                testHandler("SEND_SMS", false, List.of(dep("SMS", "真实短信 provider", false))),
                testHandler("SEND_IM", true, List.of(dep("IM", "平台 IM / bot message"))),
                testHandler("WEBHOOK", true, List.of(dep("WEBHOOK", "Webhook 投递子系统"))),
                testHandler("START_PROCESS", true, List.of(dep("BPM", "BPM 流程引擎"))),
                testHandler("CREATE_TASK", true, List.of(dep("INBOX", "平台待办 Inbox"))),
                testHandler("CC_TASK", true, List.of(
                        dep("INBOX", "平台抄送 Inbox"),
                        dep("BPM", "BPM 任务抄送服务"))),
                testHandler("ADD_COMMENT", true, List.of(dep("COMMENT", "记录评论服务"))),
                testHandler("UPDATE_RECORD", true, List.of(dep("LOWCODE_MODEL", "低码动态数据服务"))),
                testHandler("PATCH_RECORD", true, List.of(dep("LOWCODE_MODEL", "低码动态数据服务"))),
                testHandler("WRITE_AUDIT", true, List.of(dep("AUDIT", "规则动作审计表")))));

        DecisionActionCatalogDTO catalog = new DecisionActionCatalogServiceImpl(actionHandlers).getActionCatalog();
        Map<String, DecisionActionDTO> byType = catalog.getActions().stream()
                .collect(Collectors.toMap(DecisionActionDTO::getActionType, Function.identity()));

        assertProviderTypes(byType.get("NOTIFY"), "NOTIFICATION");
        assertProviderTypes(byType.get("SEND_SMS"), "SMS");
        assertProviderTypes(byType.get("SEND_IM"), "IM");
        assertProviderTypes(byType.get("WEBHOOK"), "WEBHOOK");
        assertProviderTypes(byType.get("START_PROCESS"), "BPM");
        assertProviderTypes(byType.get("CREATE_TASK"), "INBOX");
        assertProviderTypes(byType.get("CC_TASK"), "INBOX", "BPM");
        assertProviderTypes(byType.get("ADD_COMMENT"), "COMMENT");
        assertProviderTypes(byType.get("UPDATE_RECORD"), "LOWCODE_MODEL");
        assertProviderTypes(byType.get("PATCH_RECORD"), "LOWCODE_MODEL");
        assertProviderTypes(byType.get("WRITE_AUDIT"), "AUDIT");

        assertThat(byType.get("SEND_SMS").getAvailabilityStatus()).isEqualTo("UNAVAILABLE");
        assertThat(byType.get("SEND_SMS").getProviderDependencies())
                .singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.getAvailable()).isFalse();
                    assertThat(dependency.getAvailabilityReason()).contains("SMS provider unavailable");
                });
    }

    @Test
    void unavailableProviderDependencyBecomesActionAndConsumerAvailabilityReason() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ActionHandler> actionHandlers = mock(ObjectProvider.class);
        ActionProviderDependency webhookDependency = ActionProviderDependency.of(
                "WEBHOOK",
                List.of("platform_webhook_dispatcher"),
                "Webhook 投递子系统",
                true,
                false,
                "dispatcher connection refused");
        when(actionHandlers.stream()).thenAnswer(invocation -> Stream.of(
                testHandler("WEBHOOK", false, List.of(webhookDependency))));

        DecisionActionCatalogDTO catalog = new DecisionActionCatalogServiceImpl(actionHandlers).getActionCatalog();
        Map<String, DecisionActionDTO> byType = catalog.getActions().stream()
                .collect(Collectors.toMap(DecisionActionDTO::getActionType, Function.identity()));

        DecisionActionDTO webhook = byType.get("WEBHOOK");
        assertThat(webhook.getHandlerAvailable()).isFalse();
        assertThat(webhook.getAvailabilityStatus()).isEqualTo("UNAVAILABLE");
        assertThat(webhook.getAvailabilityReason())
                .isEqualTo("Webhook 投递子系统不可用: dispatcher connection refused");
        assertThat(webhook.getProviderDependencies())
                .singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.getProviderType()).isEqualTo("WEBHOOK");
                    assertThat(dependency.getAvailable()).isFalse();
                    assertThat(dependency.getAvailabilityReason()).isEqualTo("dispatcher connection refused");
                });
        assertThat(webhook.getConsumerAvailability())
                .hasSize(4)
                .allSatisfy(availability -> {
                    assertThat(availability.getAvailabilityStatus()).isEqualTo("UNAVAILABLE");
                    assertThat(availability.getAvailabilityReason())
                            .isEqualTo("Webhook 投递子系统不可用: dispatcher connection refused");
                    assertThat(availability.getProviderDependencies())
                            .singleElement()
                            .satisfies(dependency -> assertThat(dependency.getProviderType()).isEqualTo("WEBHOOK"));
                });
    }

    private static ActionHandler testHandler(String type, boolean runtimeAvailable) {
        return testHandler(type, runtimeAvailable, List.of());
    }

    private static ActionHandler testHandler(
            String type,
            boolean runtimeAvailable,
            List<ActionProviderDependency> providerDependencies) {
        return new ActionHandler() {
            @Override
            public boolean supports(String actionType) {
                return type.equals(actionType);
            }

            @Override
            public boolean runtimeAvailable() {
                return runtimeAvailable;
            }

            @Override
            public List<ActionProviderDependency> runtimeProviderDependencies() {
                return providerDependencies;
            }

            @Override
            public void execute(com.auraboot.framework.eventpolicy.model.ResolvedActionPlan plan,
                                com.auraboot.framework.decision.ast.DecisionContext context) {
            }
        };
    }

    private static ActionProviderDependency dep(String providerType, String label) {
        return dep(providerType, label, true);
    }

    private static ActionProviderDependency dep(String providerType, String label, boolean available) {
        return ActionProviderDependency.of(
                providerType,
                List.of(providerType.toLowerCase()),
                label,
                true,
                available,
                available ? null : providerType + " provider unavailable");
    }

    private static void assertProviderTypes(DecisionActionDTO action, String... providerTypes) {
        assertThat(action.getProviderDependencies())
                .extracting("providerType")
                .containsExactly((Object[]) providerTypes);
        assertThat(action.getConsumerAvailability())
                .hasSize(action.getConsumerTypes().size())
                .allSatisfy(availability -> assertThat(availability.getProviderDependencies())
                        .extracting("providerType")
                        .containsExactly((Object[]) providerTypes));
    }
}
