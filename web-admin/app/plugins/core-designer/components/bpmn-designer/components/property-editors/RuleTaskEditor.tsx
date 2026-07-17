import type { RuleTaskConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import { RuleCenterBindingSection } from './RuleCenterBindingSection';

export function RuleTaskEditor({
  config,
  processKey,
  nodeId,
  onChange,
}: {
  config?: RuleTaskConfig;
  processKey?: string;
  nodeId?: string;
  onChange: (config: RuleTaskConfig) => void;
}) {
  const handleChange = (field: keyof RuleTaskConfig, value: RuleTaskConfig[keyof RuleTaskConfig]) => {
    onChange({ name: config?.name ?? '', ...config, [field]: value });
  };

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700" htmlFor="ruletask-rule-code">
          规则编码
        </label>
        <input
          id="ruletask-rule-code"
          type="text"
          value={config?.ruleCode ?? ''}
          onChange={(event) => handleChange('ruleCode', event.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder="wd_leave_routing"
          data-testid="ruletask-rule-code"
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700" htmlFor="ruletask-facts-vars">
          事实变量
        </label>
        <input
          id="ruletask-facts-vars"
          type="text"
          value={config?.factsVars ?? ''}
          onChange={(event) => handleChange('factsVars', event.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          placeholder="days,type,department"
          data-testid="ruletask-facts-vars"
        />
      </div>

      <RuleCenterBindingSection
        title="规则中心路由"
        enabledLabel="使用规则中心决策输出审批人、候选组或动作参数。"
        enabled
        value={config?.ruleBinding}
        mode="decision"
        consumerCode={processKey}
        consumerNodeId={nodeId}
        initialDecisionCode="approval_routing"
        showTestRunner
        testId="ruletask-rule-binding"
        onToggle={(_, initialValue) => handleChange('ruleBinding', initialValue)}
        onChange={(ruleBinding) => handleChange('ruleBinding', ruleBinding)}
      />
    </>
  );
}
