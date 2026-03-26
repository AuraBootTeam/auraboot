import React from 'react';
import { useCommandActions } from '~/studio/hooks/actions/useCommandActions';
import { CommandSelector } from './CommandSelector';
import { ActionPhaseEditor } from './ActionPhaseEditor';
import { ActionConditionEditor } from './ActionConditionEditor';
import type { ActionConfig } from './types';

interface ActionPanelProps {
  modelCode?: string;
  readonly?: boolean;
}

/**
 * Action Panel - configure command-based actions for the current page.
 * Each action maps to a backend CommandDefinition and defines a four-phase
 * execution pipeline (PRE → VALIDATE → EXECUTE → POST).
 *
 * @since 3.3.0
 */
export const ActionPanel: React.FC<ActionPanelProps> = ({ modelCode, readonly = false }) => {
  const {
    commands,
    loadingCommands,
    commandsError,
    refreshCommands,
    actions,
    selectedActionId,
    selectAction,
    addAction,
    removeAction,
    updateAction,
    addPhase,
    removePhase,
    updatePhase,
    movePhase,
  } = useCommandActions(modelCode);

  const selectedAction = actions.find((a) => a.id === selectedActionId);

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-gray-200 p-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold text-gray-900">动作配置</h3>
            <p className="mt-0.5 text-xs text-gray-400">配置按钮动作与命令绑定</p>
          </div>
        </div>
      </div>

      {/* No model selected */}
      {!modelCode && (
        <div className="flex flex-1 items-center justify-center p-4">
          <div className="text-center text-gray-400">
            <p className="text-sm">请先关联模型</p>
            <p className="mt-1 text-xs">动作面板依赖模型的命令定义</p>
          </div>
        </div>
      )}

      {modelCode && (
        <div className="flex-1 overflow-y-auto">
          {/* Action list */}
          <div className="border-b border-gray-200 p-4">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-xs font-medium text-gray-600">已配置动作</span>
              {!readonly && (
                <AddActionButton
                  commands={commands}
                  loading={loadingCommands}
                  error={commandsError}
                  existingCodes={actions.map((a) => a.commandCode)}
                  onAdd={addAction}
                  onRefresh={refreshCommands}
                />
              )}
            </div>

            {actions.length === 0 ? (
              <div className="py-4 text-center text-xs text-gray-400">暂无动作，点击 "+" 添加</div>
            ) : (
              <div className="space-y-1">
                {actions.map((action) => (
                  <ActionListItem
                    key={action.id}
                    action={action}
                    selected={action.id === selectedActionId}
                    onSelect={() => selectAction(action.id)}
                    onRemove={() => removeAction(action.id)}
                    readonly={readonly}
                  />
                ))}
              </div>
            )}
          </div>

          {/* Selected action detail */}
          {selectedAction && (
            <div className="space-y-4 p-4">
              {/* Command binding */}
              <CommandSelector
                commands={commands}
                loading={loadingCommands}
                error={commandsError}
                value={selectedAction.commandCode}
                onChange={(code, cmd) =>
                  updateAction(selectedAction.id, {
                    commandCode: code,
                    displayName: cmd.displayName || code,
                  })
                }
                onRefresh={refreshCommands}
              />

              {/* Display name */}
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-500">按钮名称</label>
                <input
                  type="text"
                  value={selectedAction.displayName}
                  onChange={(e) => updateAction(selectedAction.id, { displayName: e.target.value })}
                  className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:ring-1 focus:ring-blue-400 focus:outline-none"
                  disabled={readonly}
                />
              </div>

              {/* Variant */}
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-500">按钮样式</label>
                <select
                  value={selectedAction.variant || 'default'}
                  onChange={(e) =>
                    updateAction(selectedAction.id, { variant: e.target.value as any })
                  }
                  className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:ring-1 focus:ring-blue-400 focus:outline-none"
                  disabled={readonly}
                >
                  <option value="primary">主要 (Primary)</option>
                  <option value="secondary">次要 (Secondary)</option>
                  <option value="danger">危险 (Danger)</option>
                  <option value="default">默认 (Default)</option>
                </select>
              </div>

              {/* Confirm config */}
              <ConfirmEditor
                confirm={selectedAction.confirm}
                onChange={(confirm) => updateAction(selectedAction.id, { confirm })}
                readonly={readonly}
              />

              {/* Phase editors */}
              <div className="space-y-2">
                <span className="text-xs font-medium text-gray-600">执行管道</span>
                <ActionPhaseEditor
                  category="pre"
                  phases={selectedAction.phases.pre}
                  onAdd={(type) => addPhase(selectedAction.id, 'pre', type)}
                  onRemove={(phaseId) => removePhase(selectedAction.id, 'pre', phaseId)}
                  onUpdate={(phaseId, updates) =>
                    updatePhase(selectedAction.id, 'pre', phaseId, updates)
                  }
                  onMove={(phaseId, dir) => movePhase(selectedAction.id, 'pre', phaseId, dir)}
                  readonly={readonly}
                />
                <ActionPhaseEditor
                  category="validate"
                  phases={selectedAction.phases.validate}
                  onAdd={(type) => addPhase(selectedAction.id, 'validate', type)}
                  onRemove={(phaseId) => removePhase(selectedAction.id, 'validate', phaseId)}
                  onUpdate={(phaseId, updates) =>
                    updatePhase(selectedAction.id, 'validate', phaseId, updates)
                  }
                  onMove={(phaseId, dir) => movePhase(selectedAction.id, 'validate', phaseId, dir)}
                  readonly={readonly}
                />
                <ActionPhaseEditor
                  category="execute"
                  phases={selectedAction.phases.execute}
                  onAdd={(type) => addPhase(selectedAction.id, 'execute', type)}
                  onRemove={(phaseId) => removePhase(selectedAction.id, 'execute', phaseId)}
                  onUpdate={(phaseId, updates) =>
                    updatePhase(selectedAction.id, 'execute', phaseId, updates)
                  }
                  onMove={(phaseId, dir) => movePhase(selectedAction.id, 'execute', phaseId, dir)}
                  readonly={readonly}
                />
                <ActionPhaseEditor
                  category="post"
                  phases={selectedAction.phases.post}
                  onAdd={(type) => addPhase(selectedAction.id, 'post', type)}
                  onRemove={(phaseId) => removePhase(selectedAction.id, 'post', phaseId)}
                  onUpdate={(phaseId, updates) =>
                    updatePhase(selectedAction.id, 'post', phaseId, updates)
                  }
                  onMove={(phaseId, dir) => movePhase(selectedAction.id, 'post', phaseId, dir)}
                  readonly={readonly}
                />
              </div>

              {/* Condition */}
              <ActionConditionEditor
                condition={selectedAction.condition}
                onChange={(condition) => updateAction(selectedAction.id, { condition })}
                readonly={readonly}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
};

// --- Sub-components ---

const ActionListItem: React.FC<{
  action: ActionConfig;
  selected: boolean;
  onSelect: () => void;
  onRemove: () => void;
  readonly: boolean;
}> = ({ action, selected, onSelect, onRemove, readonly }) => {
  const variantColors: Record<string, string> = {
    primary: 'bg-blue-100 text-blue-700',
    secondary: 'bg-gray-100 text-gray-700',
    danger: 'bg-red-100 text-red-700',
    default: 'bg-gray-100 text-gray-600',
  };

  return (
    <div
      onClick={onSelect}
      className={`group flex cursor-pointer items-center justify-between rounded-md px-3 py-2 transition-colors ${
        selected
          ? 'border border-blue-200 bg-blue-50'
          : 'border border-transparent hover:bg-gray-50'
      }`}
    >
      <div className="flex min-w-0 items-center gap-2">
        <span
          className={`rounded px-1.5 py-0.5 text-[10px] ${variantColors[action.variant || 'default']}`}
        >
          {action.variant || 'default'}
        </span>
        <span className="truncate text-sm text-gray-700">{action.displayName}</span>
      </div>
      {!readonly && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          className="p-0.5 text-gray-400 opacity-0 group-hover:opacity-100 hover:text-red-500"
          title="删除动作"
        >
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      )}
    </div>
  );
};

const AddActionButton: React.FC<{
  commands: any[];
  loading: boolean;
  error: string | null;
  existingCodes: string[];
  onAdd: (command: any) => void;
  onRefresh: () => void;
}> = ({ commands, loading, existingCodes, onAdd, onRefresh }) => {
  const [open, setOpen] = React.useState(false);
  const available = commands.filter((c) => !existingCodes.includes(c.code));

  return (
    <div className="relative">
      <button
        onClick={() => {
          if (available.length === 0) onRefresh();
          setOpen(!open);
        }}
        className="rounded p-1 text-gray-400 hover:bg-blue-50 hover:text-blue-600"
        title="添加动作"
      >
        <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
        </svg>
      </button>
      {open && (
        <div className="absolute top-full right-0 z-10 mt-1 max-h-48 min-w-[180px] overflow-y-auto rounded-md border border-gray-200 bg-white py-1 shadow-lg">
          {loading ? (
            <div className="px-3 py-2 text-xs text-gray-400">加载中...</div>
          ) : available.length === 0 ? (
            <div className="px-3 py-2 text-xs text-gray-400">无更多可用命令</div>
          ) : (
            available.map((cmd) => (
              <button
                key={cmd.pid}
                onClick={() => {
                  onAdd(cmd);
                  setOpen(false);
                }}
                className="w-full px-3 py-1.5 text-left text-xs hover:bg-gray-50"
              >
                <div className="font-medium text-gray-700">{cmd.displayName || cmd.code}</div>
                <div className="font-mono text-gray-400">{cmd.code}</div>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
};

const ConfirmEditor: React.FC<{
  confirm?: { title: string; message: string; confirmText?: string; cancelText?: string };
  onChange: (confirm: any) => void;
  readonly: boolean;
}> = ({ confirm, onChange, readonly }) => {
  const [enabled, setEnabled] = React.useState(!!confirm);

  const handleToggle = () => {
    if (enabled) {
      onChange(undefined);
      setEnabled(false);
    } else {
      onChange({ title: '确认操作', message: '确认执行此操作？' });
      setEnabled(true);
    }
  };

  return (
    <div>
      <div className="mb-1 flex items-center gap-2">
        <label className="text-xs font-medium text-gray-500">确认弹窗</label>
        <input
          type="checkbox"
          checked={enabled}
          onChange={handleToggle}
          disabled={readonly}
          className="rounded"
        />
      </div>
      {enabled && confirm && (
        <div className="ml-1 space-y-1.5 border-l-2 border-gray-200 pl-3">
          <div>
            <input
              type="text"
              value={confirm.title}
              onChange={(e) => onChange({ ...confirm, title: e.target.value })}
              placeholder="标题"
              className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
              disabled={readonly}
            />
          </div>
          <div>
            <input
              type="text"
              value={confirm.message}
              onChange={(e) => onChange({ ...confirm, message: e.target.value })}
              placeholder="确认消息"
              className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
              disabled={readonly}
            />
          </div>
        </div>
      )}
    </div>
  );
};
