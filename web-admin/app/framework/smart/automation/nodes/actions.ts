// web-admin/app/smart/automation/nodes/actions.ts
import type { FlowNodeDefinition } from '~/plugins/core-designer/components/flow-designer-sdk';

export const actionNodes: FlowNodeDefinition[] = [
  {
    type: 'action-update-record',
    label: '$i18n:automation.action.updateRecord',
    icon: 'Save',
    category: 'action',
    description: '$i18n:automation.action.updateRecord.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
        group: 'target',
      },
      {
        key: 'recordId',
        label: '$i18n:automation.field.recordId',
        type: 'expression',
        required: true,
        placeholder: '${trigger.recordId}',
        description: '$i18n:automation.field.recordId.desc',
        dependsOn: { field: 'modelCode' },
        group: 'target',
      },
      {
        key: 'fields',
        label: '$i18n:automation.field.updateFields',
        type: 'json',
        required: true,
        placeholder: '{ "status": "completed" }',
        description: '$i18n:automation.field.updateFields.desc',
        dependsOn: { field: 'modelCode' },
        group: 'fields_mapping',
      },
    ],
    defaultConfig: {
      actionType: 'update_record',
    },
  },
  {
    type: 'action-create-record',
    label: '$i18n:automation.action.createRecord',
    icon: 'FilePlus',
    category: 'action',
    description: '$i18n:automation.action.createRecord.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
        group: 'target',
      },
      {
        key: 'fields',
        label: '$i18n:automation.field.createFields',
        type: 'json',
        required: true,
        placeholder: '{ "name": "${trigger.name}", "status": "new" }',
        description: '$i18n:automation.field.createFields.desc',
        dependsOn: { field: 'modelCode' },
        group: 'fields_mapping',
      },
    ],
    defaultConfig: {
      actionType: 'create_record',
    },
  },
  {
    type: 'action-send-notification',
    label: '$i18n:automation.action.sendNotification',
    icon: 'Bell',
    category: 'action',
    description: '$i18n:automation.action.sendNotification.desc',
    configSchema: [
      {
        key: 'notificationType',
        label: '$i18n:automation.field.notificationType',
        type: 'select',
        required: true,
        options: [
          { label: '$i18n:automation.field.notificationType.email', value: 'email' },
          { label: '$i18n:automation.field.notificationType.sms', value: 'sms' },
          { label: '$i18n:automation.field.notificationType.push', value: 'push' },
          { label: '$i18n:automation.field.notificationType.inApp', value: 'in_app' },
        ],
        group: 'notification',
      },
      {
        key: 'title',
        label: '$i18n:automation.field.notificationTitle',
        type: 'expression',
        required: true,
        placeholder: 'Task ${trigger.name} completed',
        group: 'notification',
      },
      {
        key: 'content',
        label: '$i18n:automation.field.notificationContent',
        type: 'expression',
        required: true,
        group: 'notification',
      },
      {
        key: 'recipients',
        label: '$i18n:automation.field.recipients',
        type: 'expression',
        required: true,
        placeholder: '${trigger.assignee}',
        description: '$i18n:automation.field.recipients.desc',
        group: 'notification',
      },
    ],
    defaultConfig: {
      actionType: 'send_notification',
      notificationType: 'in_app',
    },
  },
  {
    type: 'action-execute-command',
    label: '$i18n:automation.action.executeCommand',
    icon: 'Terminal',
    category: 'action',
    description: '$i18n:automation.action.executeCommand.desc',
    configSchema: [
      {
        key: 'commandCode',
        label: '$i18n:automation.field.commandCode',
        type: 'command-select',
        required: true,
        description: '$i18n:automation.field.commandCode.desc',
        group: 'target',
      },
      {
        key: 'params',
        label: '$i18n:automation.field.commandParams',
        type: 'json',
        placeholder: '{ "recordId": "${trigger.recordId}" }',
        description: '$i18n:automation.field.commandParams.desc',
        group: 'fields_mapping',
      },
    ],
    defaultConfig: {
      actionType: 'execute_command',
    },
  },
  {
    type: 'action-call-api',
    label: '$i18n:automation.action.callApi',
    icon: 'Globe',
    category: 'action',
    description: '$i18n:automation.action.callApi.desc',
    configSchema: [
      {
        key: 'url',
        label: '$i18n:automation.field.apiUrl',
        type: 'expression',
        required: true,
        placeholder: 'https://api.example.com/endpoint',
        group: 'request',
      },
      {
        key: 'method',
        label: '$i18n:automation.field.httpMethod',
        type: 'select',
        required: true,
        options: [
          { label: 'GET', value: 'get' },
          { label: 'POST', value: 'post' },
          { label: 'PUT', value: 'put' },
          { label: 'PATCH', value: 'patch' },
          { label: 'DELETE', value: 'delete' },
        ],
        group: 'request',
      },
      {
        key: 'headers',
        label: '$i18n:automation.field.httpHeaders',
        type: 'json',
        placeholder: '{ "Authorization": "Bearer ${secret.apiToken}" }',
        group: 'advanced',
      },
      {
        key: 'body',
        label: '$i18n:automation.field.httpBody',
        type: 'json',
        placeholder: '{ "data": "${trigger.record}" }',
        group: 'request',
      },
    ],
    defaultConfig: {
      actionType: 'call_api',
      method: 'post',
    },
  },
  {
    type: 'action-send-webhook',
    label: '$i18n:automation.action.sendWebhook',
    icon: 'Send',
    category: 'action',
    description: '$i18n:automation.action.sendWebhook.desc',
    configSchema: [
      {
        key: 'url',
        label: '$i18n:automation.field.webhookUrl',
        type: 'text',
        required: true,
        placeholder: 'https://hooks.example.com/webhook',
        group: 'target',
      },
      {
        key: 'payload',
        label: '$i18n:automation.field.webhookPayload',
        type: 'json',
        required: true,
        placeholder: '{ "event": "record_updated", "data": "${trigger.record}" }',
        group: 'fields_mapping',
      },
    ],
    defaultConfig: {
      actionType: 'send_webhook',
    },
  },
  {
    type: 'action-start-process',
    label: '$i18n:automation.action.startProcess',
    icon: 'Play',
    category: 'action',
    description: '$i18n:automation.action.startProcess.desc',
    configSchema: [
      {
        key: 'processKey',
        label: '$i18n:automation.field.processKey',
        type: 'process-select',
        required: true,
        group: 'process',
      },
      {
        key: 'businessKey',
        label: '$i18n:automation.field.businessKey',
        type: 'expression',
        placeholder: '${trigger.recordId}',
        description: '$i18n:automation.field.businessKey.desc',
        group: 'process',
      },
      {
        key: 'variables',
        label: '$i18n:automation.field.processVariables',
        type: 'json',
        placeholder: '{ "assignee": "${trigger.assignee}" }',
        description: '$i18n:automation.field.processVariables.desc',
        group: 'fields_mapping',
      },
    ],
    defaultConfig: {
      actionType: 'start_process',
    },
  },
  {
    // P1 — Workflow LLM action node. Lets users embed an LLM inference step
    // (summarise / classify / extract / decide) inside an automation flow.
    // Resolves variables in the prompt template against the current execution
    // context, calls the configured LLM provider, and stores the response text
    // under context.<outputVariableName> so downstream nodes can consume it
    // (e.g. ${llmOutput} in a subsequent send-notification step).
    type: 'action-llm-call',
    label: '$i18n:automation.action.llmCall',
    icon: 'Sparkles',
    category: 'action',
    description: '$i18n:automation.action.llmCall.desc',
    configSchema: [
      {
        key: 'model',
        label: '$i18n:automation.field.llmModel',
        type: 'select',
        required: true,
        options: [
          { label: 'Claude Sonnet 4.6', value: 'claude-sonnet-4-6' },
          { label: 'Claude Opus 4', value: 'claude-opus-4' },
          { label: 'Claude Haiku 4', value: 'claude-haiku-4' },
        ],
        description: '$i18n:automation.field.llmModel.desc',
        group: 'model',
      },
      {
        key: 'systemPrompt',
        label: '$i18n:automation.field.llmSystemPrompt',
        type: 'textarea',
        placeholder: 'You are a helpful assistant.',
        description: '$i18n:automation.field.llmSystemPrompt.desc',
        group: 'prompt',
      },
      {
        key: 'userPromptTemplate',
        label: '$i18n:automation.field.llmUserPrompt',
        type: 'textarea',
        required: true,
        placeholder: 'Summarise the following: ${trigger.text}',
        description: '$i18n:automation.field.llmUserPrompt.desc',
        group: 'prompt',
      },
      {
        key: 'maxTokens',
        label: '$i18n:automation.field.llmMaxTokens',
        type: 'number',
        description: '$i18n:automation.field.llmMaxTokens.desc',
        group: 'model',
      },
      {
        key: 'thinkingEnabled',
        label: '$i18n:automation.field.llmThinkingEnabled',
        type: 'boolean',
        description: '$i18n:automation.field.llmThinkingEnabled.desc',
        group: 'advanced',
      },
      {
        key: 'thinkingBudgetTokens',
        label: '$i18n:automation.field.llmThinkingBudget',
        type: 'number',
        description: '$i18n:automation.field.llmThinkingBudget.desc',
        dependsOn: { field: 'thinkingEnabled', value: true },
        group: 'advanced',
      },
      {
        key: 'outputVariableName',
        label: '$i18n:automation.field.llmOutputVariable',
        type: 'text',
        description: '$i18n:automation.field.llmOutputVariable.desc',
        placeholder: 'llmOutput',
        group: 'output',
      },
      {
        // E.2 — Vision input. Each entry must name a workflow context
        // variable whose runtime value is a data:image/{png|jpeg|gif|webp};
        // base64,<...> URI. The backend executor (LlmCallExecutor) reads
        // these names from config, resolves them from the trigger context,
        // and emits Anthropic image content blocks. Non-vision providers
        // (openai-compat / DeepSeek / Qwen) reject outright — no silent
        // drop. The chip-list captures the names; the data URI itself
        // typically arrives via an upstream file-upload trigger or an
        // explicit "Build image data URI" pre-step.
        key: 'imageVariableNames',
        label: '$i18n:automation.field.llmImageVariableNames',
        // JSON array of context variable names — e.g. ["screenshot", "attachment"].
        // We use 'json' rather than 'multiselect' because the candidate
        // names depend on the upstream trigger schema and can't be
        // enumerated at design time. The 'tags' / chip-input field type
        // is not yet part of the shared PropertyType union; revisit when
        // it lands so we can give a friendlier UX.
        type: 'json',
        description: '$i18n:automation.field.llmImageVariableNames.desc',
        placeholder: '["screenshot", "attachment"]',
        group: 'prompt',
      },
    ],
    defaultConfig: {
      actionType: 'llm_call',
      model: 'claude-sonnet-4-6',
      maxTokens: 1024,
      thinkingEnabled: false,
      thinkingBudgetTokens: 8000,
      outputVariableName: 'llmOutput',
      imageVariableNames: [],
    },
  },
];
