import { getLocalizedText } from '~/utils/i18n';
import messages from './EventPolicyDesignerWorkflow.i18n.json';
import type { DecisionAction } from '../api/decisionApi';
import type { MatchMode, PolicyRulesValue } from './PolicyRulesEditor';
import type { DecisionOption } from '~/ui/smart/decision/DecisionRuleBindingBlock';
import type {
  DesignerStep,
  PolicyPhase,
  ExecutionMode,
  FailureStrategy,
  ConflictStrategy,
  DedupStrategy,
  PolicyActionDraft,
} from './EventPolicyDesignerWorkflow';
import {
  actionDefinitionFor,
  actionSchemaFields,
  type ActionSchemaField,
} from './actionSchemaFields';
import { resolveDecisionActionAvailability } from './actionAvailability';
import { recordOf, stringOr, parsePayload } from './eventPolicyValues';

export function createEventPolicyPresentation(locale: string) {
  function copy(key: keyof typeof messages, params: Record<string, string | number> = {}): string {
    return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
      Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
    );
  }

  const STEPS: { key: DesignerStep; label: string }[] = [
    { key: 'trigger', label: copy('trigger') },
    { key: 'rules', label: copy('rule_conditions') },
    { key: 'actions', label: copy('actions') },
    { key: 'test', label: copy('test_run') },
    { key: 'publish', label: copy('publishing') },
    { key: 'history', label: copy('version_history') },
  ];

  const EVENT_POLICY_CONSUMER_TYPE = 'EVENT_POLICY';

  const SAFE_ACTIONS: DecisionAction[] = [
    { actionType: 'NOTIFY', label: copy('send_in_app_notification'), handlerAvailable: true },
    {
      actionType: 'SEND_SMS',
      label: copy('send_sms'),
      handlerAvailable: false,
      availabilityStatus: 'UNAVAILABLE',
      availabilityReason: copy('no_live_sms_provider_is_configured_in_this_environment'),
    },
    { actionType: 'SEND_IM', label: copy('send_im_message'), handlerAvailable: true },
    { actionType: 'START_PROCESS', label: copy('start_process'), handlerAvailable: true },
    { actionType: 'CREATE_TASK', label: copy('create_task'), handlerAvailable: true },
    { actionType: 'CC_TASK', label: copy('cc_task'), handlerAvailable: true },
    { actionType: 'ADD_COMMENT', label: copy('add_comment'), handlerAvailable: true },
    { actionType: 'UPDATE_RECORD', label: copy('update_record'), handlerAvailable: true },
    { actionType: 'PATCH_RECORD', label: copy('update_record'), handlerAvailable: true },
    { actionType: 'WEBHOOK', label: copy('call_webhook'), handlerAvailable: true },
    { actionType: 'WRITE_AUDIT', label: copy('write_audit_entry'), handlerAvailable: true },
  ];

  const PHASE_LABELS: Record<PolicyPhase, string> = {
    BEFORE_SUBMIT: copy('check_before_submission'),
    AFTER_COMMIT: copy('execute_after_commit'),
    ASYNC_WORKER: copy('execute_asynchronously'),
  };

  const EXECUTION_MODE_LABELS: Record<ExecutionMode, string> = {
    ORDERED: copy('execute_in_order'),
    UNORDERED: copy('execute_in_parallel'),
  };

  const FAILURE_STRATEGY_LABELS: Record<FailureStrategy, string> = {
    FAIL_FAST: copy('stop_on_failure'),
    CONTINUE_ON_ERROR: copy('continue_on_error'),
    ALL_OR_NOTHING: copy('commit_only_if_all_succeed'),
    RETRY_ASYNC: copy('retry_asynchronously'),
    DEAD_LETTER: copy('send_to_dead_letter_queue'),
  };

  const CONFLICT_STRATEGY_LABELS: Record<ConflictStrategy, string> = {
    REJECT_ON_CONFLICT: copy('reject_on_conflict'),
    PRIORITY_WINS: copy('higher_priority_wins'),
    LAST_WRITE_WINS: copy('last_write_wins'),
    MERGE_IF_COMPATIBLE: copy('merge_if_compatible'),
  };

  const DEDUP_STRATEGY_LABELS: Record<DedupStrategy, string> = {
    NONE: copy('no_deduplication'),
    BY_IDEMPOTENCY_KEY: copy('deduplicate_by_idempotency_key'),
    BY_ACTION_TYPE_AND_TARGET: copy('deduplicate_by_action_and_target'),
  };

  const MATCH_MODE_LABELS: Record<MatchMode, string> = {
    FIRST_MATCH: copy('stop_at_first_match'),
    COLLECT_ALL: copy('collect_all_matches'),
    UNIQUE: copy('require_a_unique_match'),
    PRIORITY_FIRST: copy('match_by_priority'),
  };

  const ACTION_LABELS: Record<string, string> = {
    NOTIFY: copy('send_in_app_notification'),
    SEND_SMS: copy('send_sms'),
    SEND_IM: copy('send_im_message'),
    START_PROCESS: copy('start_process'),
    ADD_COMMENT: copy('add_comment'),
    UPDATE_RECORD: copy('update_record'),
    PATCH_RECORD: copy('update_record'),
    WEBHOOK: copy('call_webhook'),
    WRITE_AUDIT: copy('write_audit_entry'),
    CREATE_TASK: copy('create_task'),
    CC_TASK: copy('cc_task'),
  };

  const ACTION_RESULT_LABELS: Record<string, string> = {
    channel: copy('channel'),
    recipientType: copy('recipient_type'),
    recipientId: copy('recipient'),
    targetType: copy('recipient_type'),
    target: copy('recipient'),
    targetUserId: copy('recipient_users'),
    assigneeUserId: copy('assignees'),
    invalidTarget: copy('invalid_recipient'),
    sentCount: copy('sent_count'),
    recipientCount: copy('recipient_count'),
    targetUserIds: copy('recipient_users'),
    assigneeUserIds: copy('assignees'),
    createdCount: copy('created_count'),
    inboxItemIds: copy('inbox_records'),
    itemType: copy('inbox_item_type'),
    ccCount: copy('cc_count'),
    delivery: copy('delivery_method'),
    taskId: copy('task_id'),
    sourceId: copy('source'),
    processDefinitionId: copy('process_definition'),
    processInstanceId: copy('process_instance'),
    businessKey: copy('business_key'),
    recordPid: copy('business_record'),
    modelCode: copy('model'),
    ruleCode: copy('rule'),
    updatedFields: copy('updated_fields'),
    actionType: copy('action_type'),
    commentPid: copy('comment'),
    content: copy('comment_content'),
    mentions: copy('mentions'),
    auditPid: copy('audit_entry'),
    message: copy('message'),
    eventType: copy('event'),
    tenantId: copy('tenant'),
    dispatchAccepted: copy('dispatch_accepted'),
    deliveryEventId: copy('delivery_trace'),
    deliveryTraceStatus: copy('delivery_status'),
    deliveryLogPids: copy('delivery_logs'),
    deliveryReceipts: copy('delivery_receipts'),
    payloadKeys: copy('payload_fields'),
    validationError: copy('validation_error'),
    field: copy('field'),
    actualLength: copy('actual_length'),
    maxLength: copy('maximum_length'),
    attemptCount: copy('attempt_count'),
    maxAttempts: copy('maximum_attempts'),
    failureReason: copy('failure_reason'),
    errorMessage: copy('error_message'),
    requiredContext: copy('required_context'),
    fieldCount: copy('field_count'),
    resolvedCount: copy('resolved_user_count'),
  };

  const ACTION_RESULT_ORDER = [
    'sentCount',
    'recipientCount',
    'createdCount',
    'ccCount',
    'targetUserIds',
    'assigneeUserIds',
    'inboxItemIds',
    'itemType',
    'delivery',
    'failureReason',
    'errorMessage',
    'targetType',
    'target',
    'resolvedCount',
    'taskId',
    'channel',
    'recipientType',
    'recipientId',
    'processInstanceId',
    'processDefinitionId',
    'businessKey',
    'modelCode',
    'recordPid',
    'ruleCode',
    'updatedFields',
    'commentPid',
    'content',
    'mentions',
    'auditPid',
    'message',
    'eventType',
    'dispatchAccepted',
    'deliveryEventId',
    'deliveryTraceStatus',
    'deliveryLogPids',
    'deliveryReceipts',
    'validationError',
    'field',
    'requiredContext',
    'actualLength',
    'maxLength',
    'attemptCount',
    'maxAttempts',
    'fieldCount',
    'payloadKeys',
    'sourceId',
    'actionType',
    'tenantId',
  ];

  const ACTION_RESULT_VALUE_LABELS: Record<string, string> = {
    pending_async_delivery: copy('async_delivery_pending'),
    tracked_delivery_logs: copy('delivery_logs_recorded'),
    validation_failed: copy('validation_failed'),
    dispatch_failed: copy('dispatch_failed'),
    inbox: copy('inbox'),
    task: copy('task'),
    mention: copy('cc_task'),
    cc_task: copy('cc_task'),
    ROLE: copy('role'),
    USER: copy('user'),
    UNKNOWN: copy('unknown'),
    GROUP: copy('group'),
    TEAM: copy('team'),
    target_resolved_no_users: copy('no_users_matched_the_target'),
    target_resolved_no_phone_numbers: copy('no_phone_numbers_matched_the_target'),
    action_target_missing: copy('recipient_is_missing'),
    payload_content_missing: copy('message_content_is_missing'),
    payload_title_missing: copy('title_is_missing'),
    tenant_context_missing: copy('tenant_context_is_missing'),
    target_invalid: copy('invalid_recipient_format'),
    target_role_code_missing: copy('role_code_is_missing'),
    target_value_missing: copy('recipient_value_is_missing'),
    sms_delivery_failed: copy('sms_delivery_failed'),
    im_delivery_failed: copy('im_delivery_failed'),
    task_write_failed: copy('task_creation_failed'),
    cc_task_write_failed: copy('task_cc_failed'),
    notify_delivery_failed: copy('in_app_notification_delivery_failed'),
    action_payload_serialization_failed: copy('action_payload_serialization_failed'),
    webhook_dispatch_failed: copy('webhook_dispatch_failed'),
    process_definition_missing: copy('process_definition_is_missing'),
    process_start_failed: copy('process_start_failed'),
    record_context_missing: copy('business_record_context_is_missing'),
    update_fields_missing: copy('update_fields_are_missing'),
    record_update_failed: copy('record_update_failed'),
    comment_context_missing: copy('business_record_context_is_missing'),
    comment_content_missing: copy('comment_content_is_missing'),
    comment_write_failed: copy('comment_creation_failed'),
    audit_tenant_missing: copy('tenant_context_is_missing'),
    audit_write_failed: copy('audit_write_failed'),
    'record.entityCode': copy('record_model'),
    'record.recordPid': copy('business_record'),
    tenantId: copy('tenant'),
    'payload.processDefinitionId': copy('process_definition'),
    'payload.fields': copy('updated_fields'),
    'payload.content': copy('comment_content'),
    NOTIFY: copy('send_in_app_notification'),
    SEND_SMS: copy('send_sms'),
    SEND_IM: copy('send_im_message'),
    CREATE_TASK: copy('create_task'),
    CC_TASK: copy('cc_task'),
    START_PROCESS: copy('start_process'),
    WEBHOOK: copy('call_webhook'),
    UPDATE_RECORD: copy('update_record'),
    PATCH_RECORD: copy('update_record'),
    ADD_COMMENT: copy('add_comment'),
    WRITE_AUDIT: copy('write_audit_entry'),
    modelCode: copy('model'),
    recordPid: copy('business_record'),
    'payload._eventId exceeds max length': copy('delivery_trace_id_exceeds_64_characters'),
  };

  const POLICY_STATUS_LABELS: Record<string, string> = {
    UNSAVED: copy('unsaved'),
    DRAFT: copy('draft'),
    VALIDATED: copy('validated'),
    PUBLISHED: copy('published'),
    ENABLED: copy('enabled'),
    DISABLED: copy('disabled'),
    DEPRECATED: copy('deprecated'),
    RETIRED: copy('disabled'),
  };

  const RUN_STATUS_LABELS: Record<string, string> = {
    MATCHED: copy('matched'),
    NOT_MATCHED: copy('not_matched'),
    SUCCESS: copy('succeeded'),
    ERROR: copy('execution_error'),
    SKIPPED: copy('skipped'),
    UNKNOWN: copy('unknown'),
  };

  const EXECUTION_STATUS_LABELS: Record<string, string> = {
    ALL_SUCCESS: copy('all_succeeded'),
    PARTIAL_SUCCESS: copy('partially_succeeded'),
    FAILED: copy('failed'),
    NOTHING_TO_DO: copy('no_actions'),
    SUCCESS: copy('succeeded'),
    SKIPPED: copy('skipped_by_idempotency'),
    NO_HANDLER: copy('no_handler'),
    RETRY_PENDING: copy('retry_pending'),
    DEAD_LETTER: copy('dead_letter'),
    NOT_EXECUTED: copy('not_executed'),
  };

  function actionLabel(action: DecisionAction): string {
    return ACTION_LABELS[action.actionType] ?? action.label ?? action.actionType;
  }

  function actionAvailability(action?: DecisionAction) {
    return resolveDecisionActionAvailability(action, EVENT_POLICY_CONSUMER_TYPE, locale);
  }

  function actionOptionLabel(action: DecisionAction): string {
    const label = actionLabel(action);
    return actionAvailability(action).unavailable ? copy('unavailable_sentence', { label }) : label;
  }

  function actionAvailabilityForType(type: string, catalog: DecisionAction[]) {
    return actionAvailability(catalog.find((action) => action.actionType === type));
  }

  function actionTypeLabel(type: string): string {
    return ACTION_LABELS[type] ?? type;
  }

  function editableActionFields(
    action: PolicyActionDraft,
    catalog: DecisionAction[],
  ): ActionSchemaField[] {
    const fields = actionSchemaFields(actionDefinitionFor(action.type, catalog));
    if (fields.length > 0) return fields;
    return [{ path: 'target', label: copy('action_target'), dataType: 'string', required: false }];
  }

  function uniqueDecisions(rules: PolicyRulesValue['rules']): DecisionOption[] {
    const decisions: DecisionOption[] = [
      { code: 'approval_routing', name: copy('approval_routing') },
      { code: 'leave_request_automation', name: copy('leave_request_decision') },
      { code: 'complaint_sla_deadline', name: copy('complaint_sla_deadline') },
      { code: 'task_assignee', name: copy('task_assignment') },
    ];
    const seen = new Set(decisions.map((decision) => decision.code));
    rules.forEach((rule) => {
      const decisionCode = rule.decisionBinding?.decisionCode;
      if (decisionCode && !seen.has(decisionCode)) {
        seen.add(decisionCode);
        decisions.push({ code: decisionCode, name: decisionCode });
      }
    });
    return decisions;
  }

  function payloadTitle(payloadJson: string): string {
    try {
      const payload = parsePayload(payloadJson);
      const title = payload.title;
      const message = payload.message ?? payload.content;
      if (typeof title === 'string' && title.trim()) return title;
      if (typeof message === 'string' && message.trim()) return message;
    } catch {
      return copy('payload_needs_correction');
    }
    return copy('no_payload_configured');
  }

  function runStatus(value: unknown): string {
    const result = recordOf(value);
    const policy = recordOf(result?.policy) ?? result;
    const status = stringOr(
      policy?.status,
      value === null || value === undefined ? '-' : String(value),
    );
    return RUN_STATUS_LABELS[status] ?? status;
  }

  function executionStatus(value: unknown): string {
    const status = String(value ?? '-').toUpperCase();
    return EXECUTION_STATUS_LABELS[status] ?? (value == null ? '-' : String(value));
  }

  function resultPayloadRows(
    action: Record<string, unknown>,
  ): Array<{ key: string; label: string; value: string }> {
    const payload = recordOf(action.resultPayload);
    if (!payload) return [];
    const keys = [
      ...ACTION_RESULT_ORDER.filter((key) => Object.prototype.hasOwnProperty.call(payload, key)),
      ...Object.keys(payload).filter((key) => !ACTION_RESULT_ORDER.includes(key)),
    ];
    return keys
      .map((key) => ({
        key,
        label: ACTION_RESULT_LABELS[key] ?? key,
        value: resultPayloadValue(key, payload[key]),
      }))
      .filter((row) => row.value !== '-');
  }

  function resultPayloadValue(key: string, value: unknown): string {
    if (value === undefined || value === null || value === '') return '-';
    if (typeof value === 'boolean') return value ? copy('yes') : copy('no');
    if (key === 'deliveryReceipts' && Array.isArray(value)) {
      return (
        value
          .map(formatDeliveryReceipt)
          .filter((item) => item !== '-')
          .join('; ') || '-'
      );
    }
    if (Array.isArray(value))
      return (
        value
          .map((item) => resultPayloadValue('', item))
          .filter((item) => item !== '-')
          .join(', ') || '-'
      );
    if (typeof value === 'string') return ACTION_RESULT_VALUE_LABELS[value] ?? value;
    if (typeof value === 'object') {
      try {
        return JSON.stringify(value);
      } catch {
        return String(value);
      }
    }
    return String(value);
  }

  function idempotencyEvidence(value: unknown): string {
    return value == null || value === ''
      ? copy('idempotency_key')
      : copy('idempotency_key_recorded');
  }

  function idempotencyTitle(value: unknown): string | undefined {
    return value == null || value === '' ? undefined : String(value);
  }

  function formatDeliveryReceipt(value: unknown): string {
    const receipt = recordOf(value);
    if (!receipt) return resultPayloadValue('', value);
    return [receipt.subscriptionPid, receipt.deliveryLogPid, receipt.deliveryStatus]
      .map((item) => (item == null || item === '' ? '-' : String(item)))
      .join(' / ');
  }

  function policyStatusLabel(status: unknown): string {
    const value = String(status ?? '').toUpperCase();
    return POLICY_STATUS_LABELS[value] ?? (status ? String(status) : '-');
  }

  return {
    copy,
    STEPS,
    SAFE_ACTIONS,
    PHASE_LABELS,
    EXECUTION_MODE_LABELS,
    FAILURE_STRATEGY_LABELS,
    CONFLICT_STRATEGY_LABELS,
    DEDUP_STRATEGY_LABELS,
    MATCH_MODE_LABELS,
    actionOptionLabel,
    actionAvailabilityForType,
    actionTypeLabel,
    editableActionFields,
    uniqueDecisions,
    payloadTitle,
    runStatus,
    executionStatus,
    resultPayloadRows,
    idempotencyEvidence,
    idempotencyTitle,
    policyStatusLabel,
  };
}
