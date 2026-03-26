/**
 * Actions Editor
 *
 * Editor for configuring button actions with support for four-phase execution pipeline.
 * Phase flow: PRE -> VALIDATE -> EXECUTE -> POST
 */

import React, { useState, useCallback, useMemo } from 'react';
import type { DslButton, StandardAction } from '~/studio/domain/dsl/types';
import { useCommandActions } from '~/studio/hooks/actions/useCommandActions';
import { ActionPhaseEditor } from '~/studio/workbench/panels/actions/ActionPhaseEditor';
import { CommandSelector } from '~/studio/workbench/panels/actions/CommandSelector';
import type { ActionConfig, ActionPhaseType } from '~/studio/workbench/panels/actions/types';

export interface ActionsEditorProps {
  buttons: DslButton[];
  actions: string[];
  onChange: (buttons: DslButton[], actions: string[]) => void;
  modelCode?: string;
  readonly?: boolean;
  showQuickActions?: boolean;
  showAdvancedConfig?: boolean;
}

/**
 * Standard action definitions
 */
const STANDARD_ACTIONS: {
  action: StandardAction;
  label: string;
  icon: string;
  category: 'crud' | 'form' | 'filter';
}[] = [
  { action: 'create', label: '新建', icon: '+', category: 'crud' },
  { action: 'view', label: '查看', icon: '👁', category: 'crud' },
  { action: 'edit', label: '编辑', icon: '✏', category: 'crud' },
  { action: 'delete', label: '删除', icon: '🗑', category: 'crud' },
  { action: 'batchDelete', label: '批量删除', icon: '🗑', category: 'crud' },
  { action: 'export', label: '导出', icon: '⬇', category: 'crud' },
  { action: 'import', label: '导入', icon: '⬆', category: 'crud' },
  { action: 'search', label: '查询', icon: '🔍', category: 'filter' },
  { action: 'reset', label: '重置', icon: '↺', category: 'filter' },
  { action: 'submit', label: '提交', icon: '✓', category: 'form' },
  { action: 'cancel', label: '取消', icon: '✕', category: 'form' },
];

/**
 * Execution mode options
 */
type ExecutionMode = 'simple' | 'advanced';

export const ActionsEditor: React.FC<ActionsEditorProps> = ({
  buttons,
  actions,
  onChange,
  modelCode,
  readonly,
  showQuickActions = true,
  showAdvancedConfig = true,
}) => {
  const [expandedButton, setExpandedButton] = useState<number | null>(null);
  const [executionMode, setExecutionMode] = useState<ExecutionMode>('simple');

  // Command actions hook for advanced mode
  const commandActions = useCommandActions(modelCode);

  // All configured buttons (merge buttons and actions shorthand)
  const allButtons: DslButton[] = useMemo(
    () => [...buttons, ...actions.map((action) => ({ action }))],
    [buttons, actions],
  );

  // Add button
  const handleAddButton = useCallback(
    (action: StandardAction | string) => {
      if (readonly) return;
      // Check if already exists
      const exists = allButtons.some((b) => b.action === action);
      if (exists) return;

      const newButton: DslButton = { action };
      onChange([...buttons, newButton], actions);
    },
    [buttons, actions, allButtons, onChange, readonly],
  );

  // Add quick action (to actions array)
  const handleAddQuickAction = useCallback(
    (action: string) => {
      if (readonly) return;
      if (actions.includes(action)) return;
      onChange(buttons, [...actions, action]);
    },
    [buttons, actions, onChange, readonly],
  );

  // Remove button
  const handleRemoveButton = useCallback(
    (index: number) => {
      if (readonly) return;
      if (index < buttons.length) {
        const newButtons = [...buttons];
        newButtons.splice(index, 1);
        onChange(newButtons, actions);
      } else {
        const actionIndex = index - buttons.length;
        const newActions = [...actions];
        newActions.splice(actionIndex, 1);
        onChange(buttons, newActions);
      }
    },
    [buttons, actions, onChange, readonly],
  );

  // Update button
  const handleUpdateButton = useCallback(
    (index: number, updates: Partial<DslButton>) => {
      if (readonly) return;
      if (index < buttons.length) {
        const newButtons = [...buttons];
        newButtons[index] = { ...newButtons[index], ...updates };
        onChange(newButtons, actions);
      } else {
        // Convert action shorthand to full button
        const actionIndex = index - buttons.length;
        const newButton: DslButton = { action: actions[actionIndex], ...updates };
        const newActions = [...actions];
        newActions.splice(actionIndex, 1);
        onChange([...buttons, newButton], newActions);
      }
    },
    [buttons, actions, onChange, readonly],
  );

  // Get unused standard actions
  const usedActions = new Set(allButtons.map((b) => b.action));
  const unusedActions = STANDARD_ACTIONS.filter((a) => !usedActions.has(a.action));

  return (
    <div className="space-y-3">
      {/* Execution mode toggle */}
      {showAdvancedConfig && modelCode && (
        <div className="flex items-center justify-between">
          <span className="text-xs text-gray-500">配置模式</span>
          <div className="flex rounded-md border border-gray-200 text-xs">
            <button
              onClick={() => setExecutionMode('simple')}
              className={`px-3 py-1 transition-colors ${
                executionMode === 'simple'
                  ? 'bg-blue-50 text-blue-600'
                  : 'text-gray-600 hover:bg-gray-50'
              }`}
            >
              简单
            </button>
            <button
              onClick={() => setExecutionMode('advanced')}
              className={`border-l border-gray-200 px-3 py-1 transition-colors ${
                executionMode === 'advanced'
                  ? 'bg-blue-50 text-blue-600'
                  : 'text-gray-600 hover:bg-gray-50'
              }`}
            >
              高级
            </button>
          </div>
        </div>
      )}

      {/* Button list */}
      {allButtons.length === 0 ? (
        <div className="rounded border border-dashed border-gray-200 py-4 text-center text-sm text-gray-400">
          暂无按钮，点击下方添加
        </div>
      ) : (
        <div className="space-y-2">
          {allButtons.map((button, index) => (
            <ButtonItem
              key={`${button.action}-${index}`}
              button={button}
              index={index}
              isExpanded={expandedButton === index}
              onToggle={() => setExpandedButton(expandedButton === index ? null : index)}
              onRemove={() => handleRemoveButton(index)}
              onUpdate={(updates) => handleUpdateButton(index, updates)}
              readonly={readonly}
              showAdvanced={executionMode === 'advanced'}
              commandActions={commandActions}
              modelCode={modelCode}
            />
          ))}
        </div>
      )}

      {/* Quick actions */}
      {!readonly && showQuickActions && unusedActions.length > 0 && (
        <div>
          <div className="mb-2 text-xs text-gray-500">快速添加</div>
          <div className="flex flex-wrap gap-1">
            {unusedActions.slice(0, 6).map((actionDef) => (
              <button
                key={actionDef.action}
                onClick={() => handleAddQuickAction(actionDef.action)}
                className="rounded bg-gray-100 px-2 py-1 text-xs text-gray-600 transition-colors hover:bg-gray-200"
              >
                {actionDef.icon} {actionDef.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Add custom action */}
      {!readonly && (
        <div>
          <select
            onChange={(e) => {
              if (e.target.value) {
                handleAddButton(e.target.value);
                e.target.value = '';
              }
            }}
            className="w-full rounded-md border border-gray-200 px-3 py-2 text-sm"
          >
            <option value="">添加操作...</option>
            {unusedActions.map((actionDef) => (
              <option key={actionDef.action} value={actionDef.action}>
                {actionDef.label}
              </option>
            ))}
          </select>
        </div>
      )}
    </div>
  );
};

/**
 * Single button item with optional four-phase execution config
 */
interface ButtonItemProps {
  button: DslButton;
  index: number;
  isExpanded: boolean;
  onToggle: () => void;
  onRemove: () => void;
  onUpdate: (updates: Partial<DslButton>) => void;
  readonly?: boolean;
  showAdvanced?: boolean;
  commandActions?: ReturnType<typeof useCommandActions>;
  modelCode?: string;
}

const ButtonItem: React.FC<ButtonItemProps> = ({
  button,
  index,
  isExpanded,
  onToggle,
  onRemove,
  onUpdate,
  readonly,
  showAdvanced,
  commandActions,
  modelCode,
}) => {
  const actionDef = STANDARD_ACTIONS.find((a) => a.action === button.action);
  const label = actionDef?.label || button.action;
  const icon = actionDef?.icon || '⚡';

  // For advanced mode, find or create action config
  const actionConfig = commandActions?.actions.find((a) => a.commandCode === button.action);

  return (
    <div className="overflow-hidden rounded-md border border-gray-200">
      {/* Button header */}
      <div
        className="flex cursor-pointer items-center gap-2 bg-gray-50 px-3 py-2 hover:bg-gray-100"
        onClick={onToggle}
      >
        <span className="text-base">{icon}</span>
        <span className="flex-1 text-sm font-medium text-gray-700">{label}</span>

        {/* Type badge */}
        {button.type && (
          <span
            className={`rounded px-1.5 py-0.5 text-[10px] ${
              button.type === 'primary' ? 'bg-blue-50 text-blue-600' : 'bg-gray-100 text-gray-600'
            }`}
          >
            {button.type}
          </span>
        )}
        {button.danger && (
          <span className="rounded bg-red-50 px-1.5 py-0.5 text-[10px] text-red-600">危险</span>
        )}

        {/* Expand icon */}
        <svg
          className={`h-4 w-4 text-gray-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </div>

      {/* Expanded content */}
      {isExpanded && (
        <div className="space-y-3 border-t border-gray-100 p-3">
          {/* Simple mode: basic button settings */}
          <ButtonBasicSettings button={button} onUpdate={onUpdate} readonly={readonly} />

          {/* Advanced mode: four-phase execution pipeline */}
          {showAdvanced && modelCode && (
            <AdvancedExecutionConfig
              button={button}
              actionConfig={actionConfig}
              commandActions={commandActions!}
              readonly={readonly}
            />
          )}

          {/* Remove button */}
          {!readonly && (
            <button
              onClick={onRemove}
              className="w-full rounded bg-red-50 px-3 py-1.5 text-xs text-red-600 transition-colors hover:bg-red-100"
            >
              移除按钮
            </button>
          )}
        </div>
      )}
    </div>
  );
};

/**
 * Basic button settings (type, danger, visible, disabled, mode)
 */
interface ButtonBasicSettingsProps {
  button: DslButton;
  onUpdate: (updates: Partial<DslButton>) => void;
  readonly?: boolean;
}

const ButtonBasicSettings: React.FC<ButtonBasicSettingsProps> = ({
  button,
  onUpdate,
  readonly,
}) => {
  return (
    <>
      {/* Button type */}
      <div>
        <label className="mb-1 block text-xs text-gray-500">按钮类型</label>
        <select
          value={button.type || ''}
          onChange={(e) =>
            onUpdate({
              type: (e.target.value || undefined) as DslButton['type'],
            })
          }
          disabled={readonly}
          className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm"
        >
          <option value="">默认</option>
          <option value="primary">主按钮</option>
          <option value="dashed">虚线</option>
          <option value="text">文本</option>
          <option value="link">链接</option>
        </select>
      </div>

      {/* Danger */}
      <div className="flex items-center justify-between">
        <label className="text-xs text-gray-500">危险操作</label>
        <input
          type="checkbox"
          checked={button.danger || false}
          onChange={(e) => onUpdate({ danger: e.target.checked })}
          disabled={readonly}
          className="rounded border-gray-300"
        />
      </div>

      {/* Visible condition */}
      <div>
        <label className="mb-1 block text-xs text-gray-500">显示条件</label>
        <input
          type="text"
          value={button.visible || ''}
          onChange={(e) => onUpdate({ visible: e.target.value || undefined })}
          disabled={readonly}
          className="w-full rounded border border-gray-200 px-2 py-1.5 font-mono text-sm text-xs"
          placeholder="{{ true }}"
        />
      </div>

      {/* Disabled condition */}
      <div>
        <label className="mb-1 block text-xs text-gray-500">禁用条件</label>
        <input
          type="text"
          value={button.disabled || ''}
          onChange={(e) => onUpdate({ disabled: e.target.value || undefined })}
          disabled={readonly}
          className="w-full rounded border border-gray-200 px-2 py-1.5 font-mono text-sm text-xs"
          placeholder="{{ false }}"
        />
      </div>

      {/* Mode (for create/edit) */}
      {(button.action === 'create' || button.action === 'edit') && (
        <div>
          <label className="mb-1 block text-xs text-gray-500">打开方式</label>
          <select
            value={button.mode || ''}
            onChange={(e) =>
              onUpdate({
                mode: (e.target.value || undefined) as DslButton['mode'],
              })
            }
            disabled={readonly}
            className="w-full rounded border border-gray-200 px-2 py-1.5 text-sm"
          >
            <option value="">默认 (Drawer)</option>
            <option value="drawer">抽屉</option>
            <option value="modal">弹窗</option>
            <option value="page">新页面</option>
          </select>
        </div>
      )}

      {/* Confirm config */}
      <ConfirmEditor
        confirm={button.confirm}
        onChange={(confirm) => onUpdate({ confirm })}
        readonly={readonly}
      />
    </>
  );
};

/**
 * Confirm dialog configuration
 */
interface ConfirmEditorProps {
  confirm?: boolean | string;
  onChange: (confirm: boolean | string | undefined) => void;
  readonly?: boolean;
}

const ConfirmEditor: React.FC<ConfirmEditorProps> = ({ confirm, onChange, readonly }) => {
  const enabled = !!confirm;
  const message = typeof confirm === 'string' ? confirm : '';

  return (
    <div>
      <div className="mb-1 flex items-center gap-2">
        <label className="text-xs text-gray-500">确认弹窗</label>
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => onChange(e.target.checked ? true : undefined)}
          disabled={readonly}
          className="rounded border-gray-300"
        />
      </div>
      {enabled && (
        <input
          type="text"
          value={message}
          onChange={(e) => onChange(e.target.value || true)}
          placeholder="确认消息（可选）"
          className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
          disabled={readonly}
        />
      )}
    </div>
  );
};

/**
 * Advanced four-phase execution configuration
 */
interface AdvancedExecutionConfigProps {
  button: DslButton;
  actionConfig?: ActionConfig;
  commandActions: ReturnType<typeof useCommandActions>;
  readonly?: boolean;
}

const AdvancedExecutionConfig: React.FC<AdvancedExecutionConfigProps> = ({
  button,
  actionConfig,
  commandActions,
  readonly,
}) => {
  const {
    commands,
    loadingCommands,
    commandsError,
    refreshCommands,
    addAction,
    updateAction,
    addPhase,
    removePhase,
    updatePhase,
    movePhase,
  } = commandActions;

  // If no action config exists, prompt to bind a command
  if (!actionConfig) {
    return (
      <div className="border-t border-gray-100 pt-3">
        <div className="mb-2 flex items-center gap-1 text-xs font-medium text-gray-600">
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M13 10V3L4 14h7v7l9-11h-7z"
            />
          </svg>
          执行管道
        </div>
        <div className="rounded bg-gray-50 p-3 text-xs text-gray-500">
          <p className="mb-2">绑定命令以配置四阶段执行管道</p>
          {loadingCommands ? (
            <span className="text-gray-400">加载命令中...</span>
          ) : commands.length === 0 ? (
            <span className="text-gray-400">暂无可用命令</span>
          ) : (
            <select
              onChange={(e) => {
                const cmd = commands.find((c) => c.code === e.target.value);
                if (cmd) addAction(cmd);
              }}
              className="w-full rounded border border-gray-200 px-2 py-1.5 text-xs"
              disabled={readonly}
            >
              <option value="">选择命令...</option>
              {commands.map((cmd) => (
                <option key={cmd.pid} value={cmd.code}>
                  {cmd.displayName || cmd.code}
                </option>
              ))}
            </select>
          )}
        </div>
      </div>
    );
  }

  // Full four-phase editor
  return (
    <div className="space-y-3 border-t border-gray-100 pt-3">
      <div className="flex items-center gap-1 text-xs font-medium text-gray-600">
        <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M13 10V3L4 14h7v7l9-11h-7z"
          />
        </svg>
        执行管道
        <span className="ml-1 font-normal text-gray-400">({actionConfig.commandCode})</span>
      </div>

      {/* Command binding */}
      <CommandSelector
        commands={commands}
        loading={loadingCommands}
        error={commandsError}
        value={actionConfig.commandCode}
        onChange={(code, cmd) =>
          updateAction(actionConfig.id, {
            commandCode: code,
            displayName: cmd.displayName || code,
          })
        }
        onRefresh={refreshCommands}
      />

      {/* Four-phase editors */}
      <div className="space-y-2">
        <ActionPhaseEditor
          category="pre"
          phases={actionConfig.phases.pre}
          onAdd={(type: ActionPhaseType) => addPhase(actionConfig.id, 'pre', type)}
          onRemove={(phaseId) => removePhase(actionConfig.id, 'pre', phaseId)}
          onUpdate={(phaseId, updates) => updatePhase(actionConfig.id, 'pre', phaseId, updates)}
          onMove={(phaseId, dir) => movePhase(actionConfig.id, 'pre', phaseId, dir)}
          readonly={readonly}
        />
        <ActionPhaseEditor
          category="validate"
          phases={actionConfig.phases.validate}
          onAdd={(type: ActionPhaseType) => addPhase(actionConfig.id, 'validate', type)}
          onRemove={(phaseId) => removePhase(actionConfig.id, 'validate', phaseId)}
          onUpdate={(phaseId, updates) =>
            updatePhase(actionConfig.id, 'validate', phaseId, updates)
          }
          onMove={(phaseId, dir) => movePhase(actionConfig.id, 'validate', phaseId, dir)}
          readonly={readonly}
        />
        <ActionPhaseEditor
          category="execute"
          phases={actionConfig.phases.execute}
          onAdd={(type: ActionPhaseType) => addPhase(actionConfig.id, 'execute', type)}
          onRemove={(phaseId) => removePhase(actionConfig.id, 'execute', phaseId)}
          onUpdate={(phaseId, updates) => updatePhase(actionConfig.id, 'execute', phaseId, updates)}
          onMove={(phaseId, dir) => movePhase(actionConfig.id, 'execute', phaseId, dir)}
          readonly={readonly}
        />
        <ActionPhaseEditor
          category="post"
          phases={actionConfig.phases.post}
          onAdd={(type: ActionPhaseType) => addPhase(actionConfig.id, 'post', type)}
          onRemove={(phaseId) => removePhase(actionConfig.id, 'post', phaseId)}
          onUpdate={(phaseId, updates) => updatePhase(actionConfig.id, 'post', phaseId, updates)}
          onMove={(phaseId, dir) => movePhase(actionConfig.id, 'post', phaseId, dir)}
          readonly={readonly}
        />
      </div>
    </div>
  );
};

export default ActionsEditor;
