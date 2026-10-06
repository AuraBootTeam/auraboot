import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder';
import type {
  DecisionOption,
  DecisionVersionPolicy,
  DecisionBindingDraft,
  OutputTargetKind,
} from './DecisionRuleBindingBlock';
import { bindingText } from './decisionBindingText';

export function bindingPresentation(locale: string) {
  const DEFAULT_FIELDS: FieldOption[] = [
    {
      scope: 'record',
      path: 'data.amount',
      label: bindingText('amount', locale),
      dataType: 'decimal',
    },
    {
      scope: 'record',
      path: 'data.priority',
      label: bindingText('priority', locale),
      dataType: 'enum',
      options: ['HIGH', 'NORMAL', 'LOW'],
    },
    {
      scope: 'actor',
      path: 'departmentId',
      label: bindingText('userDepartment', locale),
      dataType: 'department',
    },
  ];

  const DEFAULT_DECISIONS: DecisionOption[] = [
    // Keep the legacy default marker for decisionDisplayName; authored names stay intact.
    { code: 'approval_routing', name: bindingText('approvalRouting', 'zh-CN') },
    { code: 'sla_deadline', name: bindingText('sLADeadline', locale) },
    {
      code: 'complaint_sla_deadline',
      name: bindingText('leaveApprovalSLADeadline', locale),
      outputs: [
        {
          id: 'deadlineMinutes',
          label: bindingText('deadlineMinutes', locale),
          dataType: 'integer',
        },
        {
          id: 'warningBeforeMinutes',
          label: bindingText('warningMinutesBeforeDeadline', locale),
          dataType: 'integer',
        },
        {
          id: 'escalationLevel',
          label: bindingText('escalationLevel', locale),
          dataType: 'string',
        },
      ],
    },
  ];

  const DECISION_NAME_OVERRIDES: Record<string, string> = {
    complaint_sla_deadline: bindingText('leaveApprovalSLADeadline', locale),
    approval_routing: bindingText('leaveApprovalAssignment', locale),
    leave_request_automation: bindingText('leaveRequestAutomationPolicy', locale),
  };

  const VERSION_POLICY_LABELS: Record<DecisionVersionPolicy, string> = {
    LATEST_PUBLISHED: bindingText('latestPublished', locale),
    FIXED_VERSION: bindingText('fixedVersion', locale),
    VERSION_TAG: bindingText('versionTag', locale),
    ROLLOUT: bindingText('rollout', locale),
  };

  const FALLBACK_MODE_LABELS: Record<DecisionBindingDraft['fallbackMode'], string> = {
    FAIL_CLOSED: bindingText('blockOnError', locale),
    FAIL_OPEN: bindingText('allowOnError', locale),
    DEFAULT_VALUE: bindingText('useDefaultValue', locale),
  };

  const OUTPUT_TARGET_KIND_LABELS: Record<OutputTargetKind, string> = {
    ACTION_PARAM: bindingText('actionParameter', locale),
    FIELD: bindingText('businessField', locale),
    PROCESS_VARIABLE: bindingText('processVariable', locale),
    SLA_FIELD: bindingText('sLAField', locale),
    PERMISSION_CONTEXT: bindingText('permissionContext', locale),
  };

  const RESULT_STATUS_LABELS: Record<string, string> = {
    MATCHED: bindingText('matched', locale),
    NOT_MATCHED: bindingText('notMatched', locale),
    ERROR: bindingText('executionError', locale),
    SKIPPED: bindingText('skipped', locale),
    UNKNOWN: bindingText('unknownStatus', locale),
  };

  const FIELD_SCOPE_LABELS: Record<FieldOption['scope'], string> = {
    meta: bindingText('metadata', locale),
    event: bindingText('event', locale),
    record: bindingText('businessRecord', locale),
    before: bindingText('beforeChange', locale),
    after: bindingText('afterChange', locale),
    process: bindingText('process', locale),
    task: bindingText('task', locale),
    sla: 'SLA',
    actor: bindingText('actor', locale),
    tenant: bindingText('tenant', locale),
    time: bindingText('time', locale),
    env: bindingText('environment', locale),
  };
  return {
    DEFAULT_FIELDS,
    DEFAULT_DECISIONS,
    DECISION_NAME_OVERRIDES,
    VERSION_POLICY_LABELS,
    FALLBACK_MODE_LABELS,
    OUTPUT_TARGET_KIND_LABELS,
    RESULT_STATUS_LABELS,
    FIELD_SCOPE_LABELS,
  };
}
