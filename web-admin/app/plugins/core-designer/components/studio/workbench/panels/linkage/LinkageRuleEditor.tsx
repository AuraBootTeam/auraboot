import React from 'react';
import type { LinkageRule, LinkageAction } from './types';
import { TriggerConfig } from './TriggerConfig';
import { LinkageActionConfig } from './LinkageActionConfig';

interface LinkageRuleEditorProps {
  rule: LinkageRule;
  onUpdateTrigger: (updates: Partial<LinkageRule['trigger']>) => void;
  onAddAction: (action: LinkageAction) => void;
  onRemoveAction: (index: number) => void;
  onUpdateAction: (index: number, updates: Partial<LinkageAction>) => void;
  onUpdateName: (name: string) => void;
  fieldOptions: { code: string; label: string }[];
  readonly?: boolean;
}

/**
 * LinkageRuleEditor - editor for a single linkage rule (trigger + actions).
 *
 * @since 3.5.0
 */
export const LinkageRuleEditor: React.FC<LinkageRuleEditorProps> = ({
  rule,
  onUpdateTrigger,
  onAddAction,
  onRemoveAction,
  onUpdateAction,
  onUpdateName,
  fieldOptions,
  readonly = false,
}) => {
  return (
    <div className="space-y-3">
      {/* Rule name */}
      <div>
        <input
          type="text"
          value={rule.name ?? ''}
          onChange={(e) => onUpdateName(e.target.value)}
          placeholder="规则名称（可选）"
          className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
          disabled={readonly}
        />
      </div>

      {/* Trigger configuration */}
      <div>
        <div className="mb-1.5 text-xs font-medium text-gray-600">触发条件</div>
        <TriggerConfig
          trigger={rule.trigger}
          onChange={onUpdateTrigger}
          fieldOptions={fieldOptions}
          readonly={readonly}
        />
      </div>

      {/* Actions */}
      <div>
        <LinkageActionConfig
          actions={rule.actions}
          onAdd={onAddAction}
          onRemove={onRemoveAction}
          onUpdate={onUpdateAction}
          fieldOptions={fieldOptions}
          readonly={readonly}
        />
      </div>
    </div>
  );
};
