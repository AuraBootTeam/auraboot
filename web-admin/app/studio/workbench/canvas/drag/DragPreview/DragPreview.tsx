import React from 'react';
import type { DragPreviewProps } from '~/studio/workbench/canvas/drag/DragPreview/types';

/** Component type display names */
const COMPONENT_TYPE_LABELS: Record<string, string> = {
  input: '文本输入',
  textarea: '多行文本',
  number: '数字输入',
  select: '下拉选择',
  checkbox: '复选框',
  radio: '单选按钮',
  switch: '开关',
  datepicker: '日期选择',
  timepicker: '时间选择',
  upload: '文件上传',
  image: '图片',
  button: '按钮',
  text: '文本',
  divider: '分割线',
  card: '卡片',
  table: '表格',
  form: '表单',
  grid: '网格',
};

/**
 * 拖拽预览组件
 * 在拖拽过程中显示的预览效果
 */
export const DragPreview: React.FC<DragPreviewProps> = ({
  type,
  name,
  icon,
  isField,
  fieldCode,
}) => {
  const typeLabel = COMPONENT_TYPE_LABELS[type] || type;

  return (
    <div
      className={`rotate-2 transform rounded-lg bg-white shadow-2xl ${
        isField
          ? 'border-2 border-green-400 ring-2 ring-green-100'
          : 'border-2 border-blue-400 ring-2 ring-blue-100'
      } `}
      style={{ minWidth: '160px', maxWidth: '220px' }}
    >
      {/* Header */}
      <div
        className={`rounded-t-md px-3 py-1.5 text-xs font-medium ${isField ? 'bg-green-50 text-green-700' : 'bg-blue-50 text-blue-700'} `}
      >
        {isField ? '📋 字段' : '🧩 组件'}
      </div>

      {/* Content */}
      <div className="p-3">
        <div className="flex items-center gap-2">
          <span className="flex-shrink-0 text-xl">{icon}</span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium text-gray-900">{name}</div>
            {isField && fieldCode && (
              <div className="truncate text-[10px] text-gray-400">{fieldCode}</div>
            )}
          </div>
        </div>

        {/* Component type badge */}
        <div className="mt-2 flex items-center gap-1.5">
          <span className="text-[10px] text-gray-400">生成组件:</span>
          <span
            className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${isField ? 'bg-green-100 text-green-700' : 'bg-blue-100 text-blue-700'} `}
          >
            {typeLabel}
          </span>
        </div>
      </div>

      {/* Footer */}
      <div className="rounded-b-md border-t border-gray-100 bg-gray-50 px-3 py-1.5">
        <div className="flex items-center gap-1 text-[10px] text-gray-500">
          <span className="animate-pulse">●</span>
          <span>放置到画布</span>
        </div>
      </div>
    </div>
  );
};
