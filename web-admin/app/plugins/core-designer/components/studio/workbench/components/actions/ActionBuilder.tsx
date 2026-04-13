/**
 * 动作构建器组件
 * 用于在设计器中可视化构建动作
 */

import React, { useState, useCallback, useMemo } from 'react';
import { Plus, Trash2, Copy, Move, ChevronDown, ChevronRight, Play, Settings } from 'lucide-react';
import { useToastContext } from '~/contexts/ToastContext';
import type {
  Action,
  ActionChain,
  ActionType,
  ActionRegistryEntry,
  ActionContext,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/types';
import { globalActionRegistry } from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionRegistry';
import { globalActionScheduler } from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionScheduler';
import { ExpressionEvaluator } from '~/plugins/core-designer/components/studio/services/runtime/execution/ExpressionEvaluator';

interface ActionBuilderProps {
  /** 当前动作链 */
  actionChain?: ActionChain;
  /** 动作链变更回调 */
  onChange?: (actionChain: ActionChain) => void;
  /** 是否只读 */
  readonly?: boolean;
  /** 上下文数据 */
  context?: ActionContext;
  /** 类名 */
  className?: string;
}

/**
 * 动作构建器主组件
 */
export const ActionBuilder: React.FC<ActionBuilderProps> = ({
  actionChain,
  onChange,
  readonly = false,
  context,
  className = '',
}) => {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const actionContext = useMemo<ActionContext>(() => {
    if (context) return context;
    return {
      componentId: '',
      pageId: '',
      pageState: {},
      globalState: {},
      env: {},
      utils: {
        formatDate: (date: Date) => date.toLocaleDateString(),
        formatNumber: (num: number) => num.toString(),
        validateEmail: (email: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email),
        generateId: () => Math.random().toString(36).slice(2),
      },
    };
  }, [context]);
  const [expandedActions, setExpandedActions] = useState<Set<string>>(new Set());
  const [selectedAction, setSelectedAction] = useState<string | null>(null);
  const [showActionSelector, setShowActionSelector] = useState(false);

  // 获取所有可用动作
  const availableActions = useMemo(() => {
    return globalActionRegistry.getAll();
  }, []);

  // 获取动作分类
  const actionCategories = useMemo(() => {
    return globalActionRegistry.getCategories();
  }, []);

  // 处理添加动作
  const handleAddAction = useCallback(
    (type: ActionType, insertIndex?: number) => {
      if (readonly || !onChange) return;

      const newAction = globalActionRegistry.createAction(type, {});
      const currentActions = actionChain?.actions || [];

      const newActions = [...currentActions];
      if (insertIndex !== undefined) {
        newActions.splice(insertIndex, 0, newAction);
      } else {
        newActions.push(newAction);
      }

      const newActionChain: ActionChain = {
        id: actionChain?.id || `chain_${Date.now()}`,
        name: actionChain?.name || '动作链',
        description: actionChain?.description || '',
        actions: newActions,
        stopOnError: actionChain?.stopOnError ?? true,
      };

      onChange(newActionChain);
      setShowActionSelector(false);
    },
    [actionChain, onChange, readonly],
  );

  // 处理删除动作
  const handleDeleteAction = useCallback(
    (actionId: string) => {
      if (readonly || !onChange || !actionChain) return;

      const newActions = actionChain.actions.filter((action) => action.id !== actionId);
      onChange({
        ...actionChain,
        actions: newActions,
      });
    },
    [actionChain, onChange, readonly],
  );

  // 处理复制动作
  const handleCopyAction = useCallback(
    (action: Action) => {
      if (readonly || !onChange || !actionChain) return;

      const copiedAction: Action = {
        ...action,
        id: `${action.id}_copy_${Date.now()}`,
        name: `${action.name} (副本)`,
      };

      const actionIndex = actionChain.actions.findIndex((a) => a.id === action.id);
      const newActions = [...actionChain.actions];
      newActions.splice(actionIndex + 1, 0, copiedAction);

      onChange({
        ...actionChain,
        actions: newActions,
      });
    },
    [actionChain, onChange, readonly],
  );

  // 处理移动动作
  const handleMoveAction = useCallback(
    (actionId: string, direction: 'up' | 'down') => {
      if (readonly || !onChange || !actionChain) return;

      const actions = [...actionChain.actions];
      const currentIndex = actions.findIndex((action) => action.id === actionId);

      if (currentIndex === -1) return;

      const newIndex = direction === 'up' ? currentIndex - 1 : currentIndex + 1;

      if (newIndex < 0 || newIndex >= actions.length) return;

      // 交换位置
      [actions[currentIndex], actions[newIndex]] = [actions[newIndex], actions[currentIndex]];

      onChange({
        ...actionChain,
        actions,
      });
    },
    [actionChain, onChange, readonly],
  );

  // 处理动作参数更新
  const handleUpdateAction = useCallback(
    (actionId: string, updates: Partial<Action>) => {
      if (readonly || !onChange || !actionChain) return;

      const newActions = actionChain.actions.map((action) =>
        action.id === actionId ? { ...action, ...updates } : action,
      );

      onChange({
        ...actionChain,
        actions: newActions,
      });
    },
    [actionChain, onChange, readonly],
  );

  // 处理展开/折叠
  const handleToggleExpand = useCallback((actionId: string) => {
    setExpandedActions((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(actionId)) {
        newSet.delete(actionId);
      } else {
        newSet.add(actionId);
      }
      return newSet;
    });
  }, []);

  // 处理测试动作
  const handleTestAction = useCallback(
    async (action: Action) => {
      try {
        const result = await globalActionScheduler.executeAction(action, actionContext);
        if (result.success) {
          showSuccessToast('动作执行成功');
        } else {
          showErrorToast(`动作执行失败: ${result.error?.message}`);
        }
      } catch (error) {
        console.error('Action test error:', error);
        showErrorToast(`动作测试失败: ${error instanceof Error ? error.message : '未知错误'}`);
      }
    },
    [context],
  );

  return (
    <div className={`action-builder ${className}`}>
      {/* 动作链配置 */}
      {actionChain && (
        <div className="action-chain-config mb-4 rounded-lg border p-4">
          <div className="mb-2 flex items-center justify-between">
            <h3 className="text-lg font-semibold">动作链配置</h3>
            {!readonly && (
              <button
                onClick={() => setShowActionSelector(true)}
                className="flex items-center gap-2 rounded bg-blue-500 px-3 py-1 text-white hover:bg-blue-600"
              >
                <Plus size={16} />
                添加动作
              </button>
            )}
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium">名称</label>
              <input
                type="text"
                value={actionChain.name}
                onChange={(e) => !readonly && onChange?.({ ...actionChain, name: e.target.value })}
                className="w-full rounded border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                disabled={readonly}
              />
            </div>
            <div>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={actionChain.stopOnError}
                  onChange={(e) =>
                    !readonly && onChange?.({ ...actionChain, stopOnError: e.target.checked })
                  }
                  disabled={readonly}
                />
                <span className="text-sm">遇到错误时停止执行</span>
              </label>
            </div>
          </div>

          <div className="mt-2">
            <label className="mb-1 block text-sm font-medium">描述</label>
            <textarea
              value={actionChain.description}
              onChange={(e) =>
                !readonly && onChange?.({ ...actionChain, description: e.target.value })
              }
              className="w-full rounded border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              rows={2}
              disabled={readonly}
            />
          </div>
        </div>
      )}

      {/* 动作列表 */}
      <div className="action-list space-y-2">
        {actionChain?.actions.map((action, index) => (
          <ActionItem
            key={action.id}
            action={action}
            index={index}
            expanded={expandedActions.has(action.id)}
            selected={selectedAction === action.id}
            readonly={readonly}
            onToggleExpand={() => handleToggleExpand(action.id)}
            onSelect={() => setSelectedAction(action.id)}
            onUpdate={(updates) => handleUpdateAction(action.id, updates)}
            onDelete={() => handleDeleteAction(action.id)}
            onCopy={() => handleCopyAction(action)}
            onMove={(direction) => handleMoveAction(action.id, direction)}
            onTest={() => handleTestAction(action)}
            canMoveUp={index > 0}
            canMoveDown={index < (actionChain?.actions.length || 0) - 1}
          />
        ))}
      </div>

      {/* 空状态 */}
      {(!actionChain?.actions || actionChain.actions.length === 0) && (
        <div className="empty-state py-8 text-center text-gray-500">
          <p className="mb-4">暂无动作</p>
          {!readonly && (
            <button
              onClick={() => setShowActionSelector(true)}
              className="rounded bg-blue-500 px-4 py-2 text-white hover:bg-blue-600"
            >
              添加第一个动作
            </button>
          )}
        </div>
      )}

      {/* 动作选择器 */}
      {showActionSelector && (
        <ActionSelector
          availableActions={availableActions}
          categories={actionCategories}
          onSelect={handleAddAction}
          onClose={() => setShowActionSelector(false)}
        />
      )}
    </div>
  );
};

/**
 * 动作项组件
 */
interface ActionItemProps {
  action: Action;
  index: number;
  expanded: boolean;
  selected: boolean;
  readonly: boolean;
  canMoveUp: boolean;
  canMoveDown: boolean;
  onToggleExpand: () => void;
  onSelect: () => void;
  onUpdate: (updates: Partial<Action>) => void;
  onDelete: () => void;
  onCopy: () => void;
  onMove: (direction: 'up' | 'down') => void;
  onTest: () => void;
}

const ActionItem: React.FC<ActionItemProps> = ({
  action,
  index,
  expanded,
  selected,
  readonly,
  canMoveUp,
  canMoveDown,
  onToggleExpand,
  onSelect,
  onUpdate,
  onDelete,
  onCopy,
  onMove,
  onTest,
}) => {
  const actionType = action.type ?? (action.params as { type?: ActionType }).type;
  const actionConfig = actionType ? globalActionRegistry.get(actionType) : undefined;

  return (
    <div
      className={`action-item rounded-lg border ${selected ? 'border-blue-500 bg-blue-50' : 'border-gray-200'}`}
    >
      {/* 动作头部 */}
      <div
        className="action-header flex cursor-pointer items-center justify-between p-3"
        onClick={onSelect}
      >
        <div className="flex items-center gap-2">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onToggleExpand();
            }}
            className="rounded p-1 hover:bg-gray-100"
          >
            {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          </button>

          <span className="font-medium">
            {index + 1}. {action.name}
          </span>

          {!action.enabled && (
            <span className="rounded bg-gray-200 px-2 py-1 text-xs text-gray-600">已禁用</span>
          )}
        </div>

        {!readonly && (
          <div className="action-controls flex items-center gap-1">
            <button
              onClick={(e) => {
                e.stopPropagation();
                onTest();
              }}
              className="rounded p-1 hover:bg-gray-100"
              title="测试动作"
            >
              <Play size={14} />
            </button>

            <button
              onClick={(e) => {
                e.stopPropagation();
                onCopy();
              }}
              className="rounded p-1 hover:bg-gray-100"
              title="复制动作"
            >
              <Copy size={14} />
            </button>

            {canMoveUp && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onMove('up');
                }}
                className="rounded p-1 hover:bg-gray-100"
                title="上移"
              >
                ↑
              </button>
            )}

            {canMoveDown && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onMove('down');
                }}
                className="rounded p-1 hover:bg-gray-100"
                title="下移"
              >
                ↓
              </button>
            )}

            <button
              onClick={(e) => {
                e.stopPropagation();
                onDelete();
              }}
              className="rounded p-1 text-red-600 hover:bg-red-100"
              title="删除动作"
            >
              <Trash2 size={14} />
            </button>
          </div>
        )}
      </div>

      {/* 动作详情 */}
      {expanded && (
        <div className="action-details border-t p-3">
          <ActionEditor
            action={action}
            actionConfig={actionConfig}
            readonly={readonly}
            onChange={onUpdate}
          />
        </div>
      )}
    </div>
  );
};

/**
 * 动作编辑器组件
 */
interface ActionEditorProps {
  action: Action;
  actionConfig?: ActionRegistryEntry;
  readonly: boolean;
  onChange: (updates: Partial<Action>) => void;
}

const ActionEditor: React.FC<ActionEditorProps> = ({
  action,
  actionConfig,
  readonly,
  onChange,
}) => {
  return (
    <div className="action-editor space-y-4">
      {/* 基本信息 */}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="mb-1 block text-sm font-medium">动作名称</label>
          <input
            type="text"
            value={action.name}
            onChange={(e) => onChange({ name: e.target.value })}
            className="w-full rounded border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            disabled={readonly}
          />
        </div>

        <div>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={action.enabled}
              onChange={(e) => onChange({ enabled: e.target.checked })}
              disabled={readonly}
            />
            <span className="text-sm">启用此动作</span>
          </label>
        </div>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium">描述</label>
        <textarea
          value={action.description || ''}
          onChange={(e) => onChange({ description: e.target.value })}
          className="w-full rounded border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          rows={2}
          disabled={readonly}
        />
      </div>

      {/* 参数配置 */}
      {actionConfig && (
        <div>
          <label className="mb-2 block text-sm font-medium">参数配置</label>
          <ActionParameterEditor
            params={action.params}
            schema={actionConfig.parameterSchema}
            readonly={readonly}
            onChange={(params) => onChange({ params })}
          />
        </div>
      )}
    </div>
  );
};

/**
 * 动作参数编辑器组件
 */
interface ActionParameterEditorProps {
  params: any;
  schema: any;
  readonly: boolean;
  onChange: (params: any) => void;
}

const ActionParameterEditor: React.FC<ActionParameterEditorProps> = ({
  params,
  schema,
  readonly,
  onChange,
}) => {
  // 这里应该根据 schema 动态渲染参数编辑器
  // 暂时使用简单的 JSON 编辑器
  return (
    <div className="parameter-editor">
      <textarea
        value={JSON.stringify(params, null, 2)}
        onChange={(e) => {
          try {
            const newParams = JSON.parse(e.target.value);
            onChange(newParams);
          } catch (error) {
            // 忽略 JSON 解析错误
          }
        }}
        className="w-full rounded border px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        rows={6}
        disabled={readonly}
        placeholder="请输入 JSON 格式的参数"
      />
    </div>
  );
};

/**
 * 动作选择器组件
 */
interface ActionSelectorProps {
  availableActions: ActionRegistryEntry[];
  categories: string[];
  onSelect: (type: ActionType) => void;
  onClose: () => void;
}

const ActionSelector: React.FC<ActionSelectorProps> = ({
  availableActions,
  categories,
  onSelect,
  onClose,
}) => {
  const [selectedCategory, setSelectedCategory] = useState<string>('all');
  const [searchQuery, setSearchQuery] = useState('');

  const filteredActions = useMemo(() => {
    let actions = availableActions;

    if (selectedCategory !== 'all') {
      actions = actions.filter((action) => action.category === selectedCategory);
    }

    if (searchQuery) {
      actions = globalActionRegistry.search(searchQuery);
    }

    return actions;
  }, [availableActions, selectedCategory, searchQuery]);

  return (
    <div className="action-selector bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
      <div className="flex max-h-96 w-96 flex-col rounded-lg bg-white shadow-lg">
        {/* 头部 */}
        <div className="border-b p-4">
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-lg font-semibold">选择动作</h3>
            <button onClick={onClose} className="rounded p-1 hover:bg-gray-100">
              ×
            </button>
          </div>

          {/* 搜索 */}
          <input
            type="text"
            placeholder="搜索动作..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />

          {/* 分类筛选 */}
          <div className="mt-2 flex gap-2 overflow-x-auto">
            <button
              onClick={() => setSelectedCategory('all')}
              className={`rounded px-3 py-1 text-sm whitespace-nowrap ${
                selectedCategory === 'all' ? 'bg-blue-500 text-white' : 'bg-gray-100'
              }`}
            >
              全部
            </button>
            {categories.map((category) => (
              <button
                key={category}
                onClick={() => setSelectedCategory(category)}
                className={`rounded px-3 py-1 text-sm whitespace-nowrap ${
                  selectedCategory === category ? 'bg-blue-500 text-white' : 'bg-gray-100'
                }`}
              >
                {category}
              </button>
            ))}
          </div>
        </div>

        {/* 动作列表 */}
        <div className="flex-1 overflow-y-auto p-2">
          {filteredActions.map((action) => (
            <button
              key={action.type}
              onClick={() => onSelect(action.type)}
              className="w-full rounded border-b p-3 text-left last:border-b-0 hover:bg-gray-50"
            >
              <div className="font-medium">{action.name}</div>
              <div className="text-sm text-gray-600">{action.description}</div>
            </button>
          ))}

          {filteredActions.length === 0 && (
            <div className="py-8 text-center text-gray-500">没有找到匹配的动作</div>
          )}
        </div>
      </div>
    </div>
  );
};
