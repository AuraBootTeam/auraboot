import {
  DecisionRuleBindingBlock,
  type RuleConsumerBindingDraft,
} from '~/ui/smart/decision/DecisionRuleBindingBlock';
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder';

type RuleCenterMode = 'condition' | 'decision' | 'combined';

interface RuleCenterBindingSectionProps {
  title: string;
  enabledLabel: string;
  enabled: boolean;
  defaultEnabled?: boolean;
  value?: RuleConsumerBindingDraft;
  mode?: RuleCenterMode;
  consumerCode?: string;
  consumerNodeId?: string;
  initialDecisionCode?: string;
  initialContextJson?: string;
  showTestRunner?: boolean;
  testId: string;
  onToggle: (enabled: boolean, initialValue?: RuleConsumerBindingDraft) => void;
  onChange: (value: RuleConsumerBindingDraft) => void;
}

const BPM_RULE_FIELDS: FieldOption[] = [
  { scope: 'process', path: 'nodeId', label: '流程节点', dataType: 'string' },
  { scope: 'record', path: 'amount', label: '流程金额', dataType: 'decimal' },
  { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
  { scope: 'record', path: 'data.wd_req_type', label: '请假类型', dataType: 'string' },
  { scope: 'record', path: 'data.wd_req_no', label: '申请编号', dataType: 'string' },
  { scope: 'actor', path: 'roles', label: '审批角色', dataType: 'collection' },
  {
    scope: 'record',
    path: 'data.priority',
    label: '流程变量 priority',
    dataType: 'enum',
    options: ['HIGH', 'NORMAL', 'LOW'],
  },
  { scope: 'record', path: 'requester.departmentId', label: '发起人部门', dataType: 'department' },
  { scope: 'record', path: 'businessKey', label: '业务主键', dataType: 'string' },
  { scope: 'actor', path: 'departmentId', label: '当前处理人部门', dataType: 'department' },
];

const BPM_RULE_DECISIONS = [
  {
    code: 'approval_routing',
    name: '审批路由',
    outputs: [
      { id: 'approverRole', label: '审批角色', dataType: 'string' },
      { id: 'candidateGroups', label: '候选组', dataType: 'collection' },
      { id: 'route', label: '流程路由', dataType: 'string' },
    ],
  },
  {
    code: 'task_assignee',
    name: '任务分派',
    outputs: [
      { id: 'candidateUserIds', label: '候选审批人', dataType: 'collection' },
      { id: 'assigneeUserId', label: '指定审批人', dataType: 'string' },
      { id: 'candidateGroups', label: '候选组', dataType: 'collection' },
    ],
  },
  {
    code: 'sla_deadline',
    name: 'SLA 截止时间',
    outputs: [
      { id: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
      { id: 'warningBeforeMinutes', label: '提前提醒分钟', dataType: 'integer' },
    ],
  },
];

const BPM_RULE_FIELD_CATALOG_MODEL_CODE = 'wd_leave_request';

function defaultBpmContext(processKey?: string, nodeId?: string): string {
  return JSON.stringify(
    {
      process: {
        processKey: processKey ?? 'wd_leave_approval',
        nodeId: nodeId ?? 'task_manager_approve',
        taskKey: nodeId ?? 'task_manager_approve',
      },
      record: {
        entityCode: 'wd_leave_request',
        recordPid: 'REQ-LONG-LEAVE-SAMPLE',
        data: {
          wd_req_no: 'REQ-LONG-LEAVE-SAMPLE',
          wd_req_days: 5,
          wd_req_type: 'annual',
          targetKey: nodeId ?? 'task_manager_approve',
        },
      },
      actor: {
        roles: ['employee'],
      },
    },
    null,
    2,
  );
}

function defaultBinding(
  mode: RuleCenterMode,
  consumerCode?: string,
  consumerNodeId?: string,
  initialDecisionCode?: string,
): RuleConsumerBindingDraft {
  const showCondition = mode === 'condition' || mode === 'combined';
  const showDecision = mode === 'decision' || mode === 'combined';
  return {
    consumerType: 'BPM',
    consumerCode,
    consumerNodeId,
    bindingKind: showDecision ? 'DECISION_REF' : 'CONDITION',
    conditionSpec: showCondition
      ? {
          root: { type: 'group', op: 'AND', children: [] },
          decisionBindings: [],
        }
      : undefined,
    decisionBinding: showDecision
      ? {
          decisionCode: initialDecisionCode ?? (mode === 'decision' ? 'task_assignee' : 'approval_routing'),
          versionPolicy: 'LATEST_PUBLISHED',
          inputMappings: [],
          outputMappings: [],
          fallbackPolicy: { mode: 'FAIL_CLOSED' },
          traceMode: 'SAMPLED',
          enabled: true,
        }
      : undefined,
    enabled: true,
  };
}

function withBpmConsumer(
  value: RuleConsumerBindingDraft,
  consumerCode?: string,
  consumerNodeId?: string,
): RuleConsumerBindingDraft {
  return {
    ...value,
    consumerType: 'BPM',
    consumerCode: consumerCode ?? value.consumerCode,
    consumerNodeId: consumerNodeId ?? value.consumerNodeId,
  };
}

export function RuleCenterBindingSection({
  title,
  enabledLabel,
  enabled,
  defaultEnabled = false,
  value,
  mode = 'decision',
  consumerCode,
  consumerNodeId,
  initialDecisionCode,
  initialContextJson,
  showTestRunner = true,
  testId,
  onToggle,
  onChange,
}: RuleCenterBindingSectionProps) {
  const effectiveEnabled = enabled || defaultEnabled;
  const effectiveValue = effectiveEnabled
    ? withBpmConsumer(
        value ?? defaultBinding(mode, consumerCode, consumerNodeId, initialDecisionCode),
        consumerCode,
        consumerNodeId,
      )
    : undefined;
  const handleToggle = (nextEnabled: boolean) => {
    onToggle(
      nextEnabled,
      nextEnabled
        ? withBpmConsumer(
            value ?? defaultBinding(mode, consumerCode, consumerNodeId, initialDecisionCode),
            consumerCode,
            consumerNodeId,
          )
        : undefined,
    );
  };

  return (
    <div className="mb-4 rounded-md border border-gray-200 p-3" data-testid={testId}>
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={effectiveEnabled}
          onChange={(event) => handleToggle(event.target.checked)}
          data-testid={`${testId}-toggle`}
        />
        <span className="text-sm font-semibold text-gray-700">{title}</span>
      </label>
      {enabledLabel ? <p className="mt-1 text-xs text-gray-500">{enabledLabel}</p> : null}

      {effectiveEnabled && (
        <div className="mt-3" data-testid={`${testId}-editor`}>
          <DecisionRuleBindingBlock
            value={effectiveValue}
            onChange={(next) => onChange(withBpmConsumer(next, consumerCode, consumerNodeId))}
            block={{
              props: {
                mode,
                consumerType: 'BPM',
                consumerCode,
                consumerNodeId,
                fields: BPM_RULE_FIELDS,
                fieldCatalogMode: 'merge',
                fieldCatalogModelCode: BPM_RULE_FIELD_CATALOG_MODEL_CODE,
                decisions: BPM_RULE_DECISIONS,
                initialDecisionCode,
                initialContextJson: initialContextJson ?? defaultBpmContext(consumerCode, consumerNodeId),
                showImpactPreview: true,
                showTestRunner,
              },
            }}
          />
        </div>
      )}
    </div>
  );
}
