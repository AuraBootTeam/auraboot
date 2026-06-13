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
  value?: RuleConsumerBindingDraft;
  mode?: RuleCenterMode;
  consumerCode?: string;
  consumerNodeId?: string;
  testId: string;
  onToggle: (enabled: boolean, initialValue?: RuleConsumerBindingDraft) => void;
  onChange: (value: RuleConsumerBindingDraft) => void;
}

const BPM_RULE_FIELDS: FieldOption[] = [
  { scope: 'record', path: 'amount', label: '流程变量 amount', dataType: 'decimal' },
  {
    scope: 'record',
    path: 'priority',
    label: '流程变量 priority',
    dataType: 'enum',
    options: ['HIGH', 'NORMAL', 'LOW'],
  },
  { scope: 'record', path: 'requester.departmentId', label: '发起人部门', dataType: 'department' },
  { scope: 'record', path: 'businessKey', label: '业务主键', dataType: 'string' },
  { scope: 'actor', path: 'departmentId', label: '当前处理人部门', dataType: 'department' },
];

const BPM_RULE_DECISIONS = [
  { code: 'approval_routing', name: '审批路由' },
  { code: 'task_assignee', name: '任务分派' },
  { code: 'sla_deadline', name: 'SLA 截止时间' },
];

function defaultBinding(
  mode: RuleCenterMode,
  consumerCode?: string,
  consumerNodeId?: string,
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
          decisionCode: mode === 'decision' ? 'task_assignee' : 'approval_routing',
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
  value,
  mode = 'decision',
  consumerCode,
  consumerNodeId,
  testId,
  onToggle,
  onChange,
}: RuleCenterBindingSectionProps) {
  const handleToggle = (nextEnabled: boolean) => {
    onToggle(
      nextEnabled,
      nextEnabled
        ? withBpmConsumer(
            value ?? defaultBinding(mode, consumerCode, consumerNodeId),
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
          checked={enabled}
          onChange={(event) => handleToggle(event.target.checked)}
          data-testid={`${testId}-toggle`}
        />
        <span className="text-sm font-semibold text-gray-700">{title}</span>
      </label>
      {enabledLabel ? <p className="mt-1 text-xs text-gray-500">{enabledLabel}</p> : null}

      {enabled && (
        <div className="mt-3" data-testid={`${testId}-editor`}>
          <DecisionRuleBindingBlock
            value={value}
            onChange={(next) => onChange(withBpmConsumer(next, consumerCode, consumerNodeId))}
            block={{
              props: {
                mode,
                consumerType: 'BPM',
                consumerCode,
                consumerNodeId,
                fields: BPM_RULE_FIELDS,
                decisions: BPM_RULE_DECISIONS,
                showImpactPreview: true,
                showTestRunner: false,
              },
            }}
          />
        </div>
      )}
    </div>
  );
}
