/**
 * Toolbar Preview
 *
 * Preview component for toolbar-buttons and form-buttons blocks.
 */

import React from 'react';
import type { DslBlock, DslButton, StandardAction } from '~/studio/domain/dsl/types';

export interface ToolbarPreviewProps {
  block: DslBlock;
}

/**
 * Action labels
 */
const ACTION_LABELS: Record<StandardAction, string> = {
  create: '新建',
  view: '查看',
  edit: '编辑',
  delete: '删除',
  batchDelete: '批量删除',
  search: '查询',
  reset: '重置',
  export: '导出',
  import: '导入',
  submit: '提交',
  cancel: '取消',
};

/**
 * Action icons
 */
const ACTION_ICONS: Record<StandardAction, string> = {
  create: '+',
  view: '👁',
  edit: '✏',
  delete: '🗑',
  batchDelete: '🗑',
  search: '🔍',
  reset: '↺',
  export: '⬇',
  import: '⬆',
  submit: '✓',
  cancel: '✕',
};

export const ToolbarPreview: React.FC<ToolbarPreviewProps> = ({ block }) => {
  const buttons = block.buttons || [];
  const actions = block.actions || [];
  const isFormButtons = block.blockType === 'form-buttons';

  // Convert actions shorthand to buttons
  const allButtons: DslButton[] = [...buttons, ...actions.map((action) => ({ action }))];

  return (
    <div className={`p-3 ${isFormButtons ? 'border-t border-gray-100 bg-gray-50' : ''}`}>
      <div
        className={`flex items-center gap-2 ${isFormButtons ? 'justify-end' : 'justify-between'}`}
      >
        {allButtons.length === 0 ? (
          <span className="text-sm text-gray-400">点击右侧面板添加按钮</span>
        ) : (
          <>
            {!isFormButtons && <div className="flex-1" />}
            {allButtons.map((btn, index) => (
              <ButtonPreview key={index} button={btn} />
            ))}
          </>
        )}
      </div>
    </div>
  );
};

/**
 * Single button preview
 */
const ButtonPreview: React.FC<{ button: DslButton }> = ({ button }) => {
  const action = button.action as StandardAction;
  const label = ACTION_LABELS[action] || button.action;
  const isPrimary = button.type === 'primary' || action === 'submit' || action === 'create';
  const isDanger = button.danger || action === 'delete' || action === 'batchDelete';

  return (
    <button
      className={`rounded px-3 py-1.5 text-xs font-medium transition-colors ${
        isDanger
          ? 'border border-red-200 bg-red-50 text-red-600'
          : isPrimary
            ? 'bg-blue-500 text-white'
            : 'border border-gray-200 bg-white text-gray-700'
      }`}
    >
      {button.icon && <span className="mr-1">{ACTION_ICONS[action] || button.icon}</span>}
      {label}
    </button>
  );
};

export default ToolbarPreview;
