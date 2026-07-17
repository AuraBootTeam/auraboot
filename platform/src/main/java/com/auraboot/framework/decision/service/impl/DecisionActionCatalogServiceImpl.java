package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.dto.DecisionActionCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionActionConsumerAvailabilityDTO;
import com.auraboot.framework.decision.dto.DecisionActionDTO;
import com.auraboot.framework.decision.dto.DecisionActionProviderDependencyDTO;
import com.auraboot.framework.decision.service.DecisionActionCatalogService;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DecisionActionCatalogServiceImpl implements DecisionActionCatalogService {

    private static final String AVAILABLE = "AVAILABLE";
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final List<String> RULE_ACTION_CONSUMERS = List.of("SLA", "EVENT_POLICY", "AUTOMATION", "BPM");

    private final ObjectProvider<ActionHandler> actionHandlers;

    @Override
    public DecisionActionCatalogDTO getActionCatalog() {
        DecisionActionCatalogDTO catalog = new DecisionActionCatalogDTO();
        catalog.setActions(ACTION_DEFINITIONS.stream()
                .map(this::toDTO)
                .toList());
        return catalog;
    }

    private DecisionActionDTO toDTO(ActionDefinition definition) {
        DecisionActionDTO dto = new DecisionActionDTO();
        dto.setActionType(definition.actionType());
        dto.setLabel(definition.label());
        dto.setCategory(definition.category());
        dto.setDescription(definition.description());
        dto.setScopes(definition.scopes());
        dto.setConsumerTypes(definition.consumerTypes());
        ActionAvailability availability = availability(definition);
        dto.setHandlerAvailable(availability.handlerAvailable());
        dto.setAvailabilityStatus(availability.handlerAvailable() ? AVAILABLE : UNAVAILABLE);
        dto.setAvailabilityReason(availability.handlerAvailable() ? null : availability.reason());
        dto.setProviderDependencies(providerDependencies(availability.providerDependencies()));
        dto.setConsumerAvailability(definition.consumerTypes().stream()
                .map(consumerType -> consumerAvailability(consumerType, availability))
                .toList());
        dto.setInputSchema(definition.inputSchema());
        return dto;
    }

    private DecisionActionConsumerAvailabilityDTO consumerAvailability(
            String consumerType,
            ActionAvailability availability) {
        DecisionActionConsumerAvailabilityDTO dto = new DecisionActionConsumerAvailabilityDTO();
        dto.setConsumerType(consumerType);
        dto.setHandlerAvailable(availability.handlerAvailable());
        dto.setAvailabilityStatus(availability.handlerAvailable() ? AVAILABLE : UNAVAILABLE);
        dto.setAvailabilityReason(availability.handlerAvailable() ? null : availability.reason());
        dto.setProviderDependencies(providerDependencies(availability.providerDependencies()));
        return dto;
    }

    private ActionAvailability availability(ActionDefinition definition) {
        boolean handlerFound = false;
        List<ActionProviderDependency> providerDependencies = new ArrayList<>();
        for (ActionHandler handler : actionHandlers.stream().toList()) {
            boolean supported;
            try {
                supported = handler.supports(definition.actionType());
            } catch (Exception ignored) {
                supported = false;
            }
            if (!supported) {
                continue;
            }
            handlerFound = true;
            try {
                providerDependencies.addAll(handler.runtimeProviderDependencies());
            } catch (Exception ignored) {
                // Provider dependency reporting must not hide the coarse availability reason.
            }
            try {
                if (handler.runtimeAvailable()) {
                    return ActionAvailability.available(providerDependencies);
                }
            } catch (Exception ignored) {
                // handled below with the productized unavailable reason
            }
        }
        String reason = unavailableProviderReason(providerDependencies);
        if (reason == null || reason.isBlank()) {
            reason = definition.unavailableReason();
        }
        if (reason == null || reason.isBlank()) {
            reason = handlerFound ? "动作处理器当前运行配置不可用" : "当前环境未注册动作处理器";
        }
        return ActionAvailability.unavailable(reason, providerDependencies);
    }

    private String unavailableProviderReason(List<ActionProviderDependency> providerDependencies) {
        return providerDependencies.stream()
                .filter(dependency -> dependency.required()
                        && (!dependency.available() || UNAVAILABLE.equals(dependency.availabilityStatus())))
                .findFirst()
                .map(this::unavailableProviderReason)
                .orElse(null);
    }

    private String unavailableProviderReason(ActionProviderDependency dependency) {
        String label = firstNonBlank(dependency.label(), dependency.providerType(), "外部 provider");
        String reason = firstNonBlank(dependency.availabilityReason(), dependency.availabilityStatus(), null);
        if (reason == null) {
            return label + "不可用";
        }
        return label + "不可用: " + reason;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<DecisionActionProviderDependencyDTO> providerDependencies(
            List<ActionProviderDependency> dependencies) {
        return dependencies.stream()
                .map(this::providerDependency)
                .toList();
    }

    private DecisionActionProviderDependencyDTO providerDependency(ActionProviderDependency dependency) {
        DecisionActionProviderDependencyDTO dto = new DecisionActionProviderDependencyDTO();
        dto.setProviderType(dependency.providerType());
        dto.setProviderCodes(dependency.providerCodes());
        dto.setLabel(dependency.label());
        dto.setRequired(dependency.required());
        dto.setAvailable(dependency.available());
        dto.setAvailabilityStatus(dependency.availabilityStatus());
        dto.setAvailabilityReason(dependency.availabilityReason());
        return dto;
    }

    private record ActionDefinition(
            String actionType,
            String label,
            String category,
            String description,
            List<String> scopes,
            List<String> consumerTypes,
            String unavailableReason,
            Map<String, Object> inputSchema) {
    }

    private record ActionAvailability(
            boolean handlerAvailable,
            String reason,
            List<ActionProviderDependency> providerDependencies) {
        static ActionAvailability available(List<ActionProviderDependency> providerDependencies) {
            return new ActionAvailability(true, null, List.copyOf(providerDependencies));
        }

        static ActionAvailability unavailable(
                String reason,
                List<ActionProviderDependency> providerDependencies) {
            return new ActionAvailability(false, reason, List.copyOf(providerDependencies));
        }
    }

    private static final List<ActionDefinition> ACTION_DEFINITIONS = List.of(
            new ActionDefinition(
                    "NOTIFY",
                    "发送通知",
                    "messaging",
                    "向规则解析出的用户、角色或组织发送站内通知。",
                    List.of("actor", "record", "event"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("target", "payload.title", "payload.content"),
                            Map.of(
                                    "target", field("string", "通知接收人", true),
                                    "payload.title", field("string", "通知标题", true),
                                    "payload.content", field("text", "通知内容", true)))),
            new ActionDefinition(
                    "SEND_SMS",
                    "发送短信",
                    "messaging",
                    "向规则解析出的手机号或用户发送短信。",
                    List.of("actor", "record", "event"),
                    RULE_ACTION_CONSUMERS,
                    "当前环境未配置真实短信 provider",
                    schema(
                            List.of("target", "payload.content"),
                            Map.of(
                                    "target", field("string", "手机号或接收人表达式", true),
                                    "payload.template", field("string", "短信模板编码", false),
                                    "payload.content", field("text", "短信内容", true)))),
            new ActionDefinition(
                    "SEND_IM",
                    "发送 IM 消息",
                    "messaging",
                    "向企业 IM、群组或用户发送即时消息。",
                    List.of("actor", "record", "event"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("target", "payload.content"),
                            Map.of(
                                    "target", field("string", "IM 接收人或群组表达式", true),
                                    "payload.channel", field("string", "IM 渠道", false),
                                    "payload.content", field("text", "消息内容", true)))),
            new ActionDefinition(
                    "START_PROCESS",
                    "启动流程",
                    "workflow",
                    "启动一个 BPM 流程实例，并把规则上下文写入流程变量。",
                    List.of("record", "actor", "event"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("payload.processDefinitionId"),
                            Map.of(
                                    "payload.processDefinitionId", field("string", "流程标识", true),
                                    "payload.businessKey", field("string", "业务主键，默认使用业务记录 PID", false),
                                    "payload.variables", field("object", "流程变量", false)))),
            new ActionDefinition(
                    "CREATE_TASK",
                    "创建任务",
                    "workflow",
                    "根据规则命中结果创建待办任务。",
                    List.of("record", "actor", "event"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("payload.title", "payload.assignee"),
                            Map.of(
                                    "target", field("string", "任务归属对象", false),
                                    "payload.title", field("string", "任务标题", true),
                                    "payload.assignee", field("string", "处理人表达式", true),
                                    "payload.dueDate", field("string", "截止时间表达式", false)))),
            new ActionDefinition(
                    "CC_TASK",
                    "抄送任务",
                    "collaboration",
                    "把命中的流程任务或业务事项抄送给指定用户、角色或组织。",
                    List.of("record", "actor", "event"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("target"),
                            Map.of(
                                    "target", field("string", "抄送接收人表达式", true),
                                    "payload.taskId", field("string", "任务 ID 表达式", false),
                                    "payload.message", field("text", "抄送消息", false)))),
            new ActionDefinition(
                    "ADD_COMMENT",
                    "添加评论",
                    "collaboration",
                    "在命中的业务记录上追加一条评论。",
                    List.of("record", "actor"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("payload.content"),
                            Map.of(
                                    "payload.content", field("text", "评论内容", true),
                                    "payload.mentions", field("string", "提及对象表达式", false)))),
            new ActionDefinition(
                    "UPDATE_RECORD",
                    "更新记录",
                    "data",
                    "更新命中业务记录上的字段值。",
                    List.of("record"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("payload.fields"),
                            Map.of("payload.fields", field("object", "按模型字段编码组织的字段值", true)))),
            new ActionDefinition(
                    "PATCH_RECORD",
                    "修补记录",
                    "data",
                    "按补丁方式更新命中业务记录上的字段值。",
                    List.of("record"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("payload.fields"),
                            Map.of("payload.fields", field("object", "按模型字段编码组织的字段值", true)))),
            new ActionDefinition(
                    "WEBHOOK",
                    "发送 Webhook",
                    "integration",
                    "通过平台 Webhook 子系统向外部系统投递事件。",
                    List.of("event", "record", "tenant"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of("payload.eventType"),
                            Map.of(
                                    "payload.eventType", field("string", "Webhook 事件类型", true),
                                    "payload._eventId", field("string", "投递追踪 ID，最多 64 字符", false),
                                    "payload", field("object", "Webhook 请求体字段", false)))),
            new ActionDefinition(
                    "WRITE_AUDIT",
                    "写入审计",
                    "governance",
                    "为命中的策略动作写入一条业务审计记录。",
                    List.of("record", "event", "tenant"),
                    RULE_ACTION_CONSUMERS,
                    null,
                    schema(
                            List.of(),
                            Map.of(
                                    "payload.message", field("text", "审计消息", false),
                                    "payload", field("object", "审计载荷", false)))));

    private static Map<String, Object> schema(List<String> required, Map<String, Object> fields) {
        return Map.of(
                "type", "object",
                "required", required,
                "fields", fields);
    }

    private static Map<String, Object> field(String dataType, String label, boolean required) {
        return Map.of(
                "dataType", dataType,
                "label", label,
                "required", required);
    }
}
