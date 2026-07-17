package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.eventpolicy.executor.ActionProviderDependency;

import java.util.List;

final class ActionProviderDependencies {

    private ActionProviderDependencies() {
    }

    static ActionProviderDependency notification() {
        return available("NOTIFICATION", List.of("IN_APP"), "站内通知服务");
    }

    static ActionProviderDependency sms(String providerCode, String label, boolean available, String reason) {
        return ActionProviderDependency.of("SMS", providerCode == null || providerCode.isBlank()
                        ? List.of()
                        : List.of(providerCode),
                label,
                true,
                available,
                reason);
    }

    static ActionProviderDependency im() {
        return available("IM", List.of("SYSTEM_BOT_MESSAGE"), "平台 IM / bot message");
    }

    static ActionProviderDependency inboxTask() {
        return available("INBOX", List.of("TASK"), "平台待办 Inbox");
    }

    static ActionProviderDependency inboxMention() {
        return available("INBOX", List.of("MENTION"), "平台抄送 Inbox");
    }

    static ActionProviderDependency bpmTaskCc() {
        return available("BPM", List.of("TASK_CC"), "BPM 任务抄送服务");
    }

    static ActionProviderDependency webhookDispatcher() {
        return available("WEBHOOK", List.of("PLATFORM_WEBHOOK_DISPATCHER"), "Webhook 投递子系统");
    }

    static ActionProviderDependency bpmEngine() {
        return available("BPM", List.of("SMART_ENGINE"), "BPM 流程引擎");
    }

    static ActionProviderDependency recordComment() {
        return available("COMMENT", List.of("RECORD_COMMENT_SERVICE"), "记录评论服务");
    }

    static ActionProviderDependency dynamicData() {
        return available("LOWCODE_MODEL", List.of("DYNAMIC_DATA_SERVICE"), "低码动态数据服务");
    }

    static ActionProviderDependency actionAudit() {
        return available("AUDIT", List.of("DRT_ACTION_AUDIT"), "规则动作审计表");
    }

    private static ActionProviderDependency available(String providerType, List<String> providerCodes, String label) {
        return ActionProviderDependency.of(providerType, providerCodes, label, true, true, null);
    }
}
