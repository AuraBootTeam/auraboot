import React from 'react';
import { useLinkageRules } from '~/studio/hooks/linkage/useLinkageRules';
import { LinkageRuleEditor } from './LinkageRuleEditor';
import type { LinkageRule } from './types';

interface LinkagePanelProps {
  fieldOptions?: { code: string; label: string }[];
  readonly?: boolean;
}

/**
 * LinkagePanel - main panel for configuring field linkage rules.
 * Displays rule list on the left, rule editor on the right (within panel).
 *
 * @since 3.5.0
 */
export const LinkagePanel: React.FC<LinkagePanelProps> = ({
  fieldOptions = [],
  readonly = false,
}) => {
  const {
    rules,
    selectedRule,
    selectedRuleId,
    setSelectedRuleId,
    addRule,
    removeRule,
    updateRule,
    updateTrigger,
    addAction,
    removeAction,
    updateAction,
    toggleRuleEnabled,
    duplicateRule,
  } = useLinkageRules();

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-gray-200 p-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold text-gray-900">联动规则</h3>
            <p className="mt-0.5 text-xs text-gray-400">配置字段间的联动关系</p>
          </div>
          {!readonly && (
            <button
              onClick={addRule}
              className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
            >
              + 新规则
            </button>
          )}
        </div>
      </div>

      {/* Rule list */}
      <div className="flex-1 overflow-y-auto">
        {rules.length === 0 && (
          <div className="flex items-center justify-center p-8">
            <div className="text-center text-gray-400">
              <svg
                className="mx-auto mb-2 h-10 w-10 text-gray-300"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"
                />
              </svg>
              <p className="text-sm">暂无联动规则</p>
              <p className="mt-1 text-xs">点击"+ 新规则"开始配置</p>
            </div>
          </div>
        )}

        {rules.map((rule) => (
          <RuleListItem
            key={rule.id}
            rule={rule}
            isSelected={rule.id === selectedRuleId}
            onSelect={() => setSelectedRuleId(rule.id)}
            onToggle={() => toggleRuleEnabled(rule.id)}
            onDuplicate={() => duplicateRule(rule.id)}
            onRemove={() => removeRule(rule.id)}
            fieldOptions={fieldOptions}
            readonly={readonly}
          />
        ))}
      </div>

      {/* Selected rule editor */}
      {selectedRule && (
        <div className="max-h-[50%] overflow-y-auto border-t border-gray-200">
          <div className="p-4">
            <div className="mb-3 flex items-center justify-between">
              <span className="text-xs font-medium text-gray-600">编辑规则</span>
              <button
                onClick={() => setSelectedRuleId(null)}
                className="p-0.5 text-gray-400 hover:text-gray-600"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>
            <LinkageRuleEditor
              rule={selectedRule}
              onUpdateTrigger={(trigger) => updateTrigger(selectedRule.id, trigger)}
              onAddAction={(action) => addAction(selectedRule.id, action)}
              onRemoveAction={(index) => removeAction(selectedRule.id, index)}
              onUpdateAction={(index, updates) => updateAction(selectedRule.id, index, updates)}
              onUpdateName={(name) => updateRule(selectedRule.id, { name: name || undefined })}
              fieldOptions={fieldOptions}
              readonly={readonly}
            />
          </div>
        </div>
      )}
    </div>
  );
};

const RuleListItem: React.FC<{
  rule: LinkageRule;
  isSelected: boolean;
  onSelect: () => void;
  onToggle: () => void;
  onDuplicate: () => void;
  onRemove: () => void;
  fieldOptions: { code: string; label: string }[];
  readonly: boolean;
}> = ({ rule, isSelected, onSelect, onToggle, onDuplicate, onRemove, fieldOptions, readonly }) => {
  const triggerLabel =
    fieldOptions.find((f) => f.code === rule.trigger.fieldCode)?.label ??
    rule.trigger.fieldCode ??
    '未设置';

  return (
    <div
      className={`group cursor-pointer border-b border-gray-100 px-4 py-2.5 transition-colors ${
        isSelected ? 'bg-blue-50' : 'hover:bg-gray-50'
      }`}
      onClick={onSelect}
    >
      <div className="flex items-center justify-between">
        <div className="flex min-w-0 items-center gap-2">
          {/* Enable toggle */}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onToggle();
            }}
            className={`h-3 w-3 shrink-0 rounded-full border-2 ${
              rule.enabled ? 'border-green-400 bg-green-400' : 'border-gray-300 bg-gray-200'
            }`}
            title={rule.enabled ? '已启用' : '已禁用'}
            disabled={readonly}
          />
          <div className="min-w-0">
            <div className="truncate text-xs font-medium text-gray-700">
              {rule.name || `规则 (${triggerLabel})`}
            </div>
            <div className="mt-0.5 text-[10px] text-gray-400">
              当 {triggerLabel}{' '}
              {rule.trigger.event === 'change'
                ? '变化'
                : rule.trigger.event === 'blur'
                  ? '失焦'
                  : '聚焦'}
              {rule.actions.length > 0 && ` → ${rule.actions.length} 个动作`}
            </div>
          </div>
        </div>

        {/* Actions */}
        {!readonly && (
          <div className="flex items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
            <button
              onClick={(e) => {
                e.stopPropagation();
                onDuplicate();
              }}
              className="p-0.5 text-gray-400 hover:text-blue-500"
              title="复制规则"
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                />
              </svg>
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation();
                onRemove();
              }}
              className="p-0.5 text-gray-400 hover:text-red-500"
              title="删除规则"
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                />
              </svg>
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
