import React from 'react';
import {
  ACTION_TYPE_INFO,
  createLinkageAction,
  type LinkageAction,
  type LinkageActionType,
} from './types';
import { FieldMultiSelect } from './FieldMultiSelect';

interface LinkageActionConfigProps {
  actions: LinkageAction[];
  onAdd: (action: LinkageAction) => void;
  onRemove: (index: number) => void;
  onUpdate: (index: number, updates: Partial<LinkageAction>) => void;
  fieldOptions: { code: string; label: string }[];
  readonly?: boolean;
}

/**
 * LinkageActionConfig - configure the list of actions for a linkage rule.
 *
 * @since 3.5.0
 */
export const LinkageActionConfig: React.FC<LinkageActionConfigProps> = ({
  actions,
  onAdd,
  onRemove,
  onUpdate,
  fieldOptions,
  readonly = false,
}) => {
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-gray-600">动作列表</span>
        {!readonly && <ActionTypeDropdown onSelect={(type) => onAdd(createLinkageAction(type))} />}
      </div>

      {actions.length === 0 && (
        <div className="py-2 text-center text-xs text-gray-400">暂无动作，点击上方添加</div>
      )}

      {actions.map((action, idx) => (
        <div key={idx} className="space-y-1.5 rounded border border-gray-200 p-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-gray-700">
              {ACTION_TYPE_INFO[action.type].label}
            </span>
            {!readonly && (
              <button
                onClick={() => onRemove(idx)}
                className="p-0.5 text-gray-400 hover:text-red-500"
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

          <ActionBody
            action={action}
            index={idx}
            onUpdate={onUpdate}
            fieldOptions={fieldOptions}
            readonly={readonly}
          />
        </div>
      ))}
    </div>
  );
};

const ActionTypeDropdown: React.FC<{ onSelect: (type: LinkageActionType) => void }> = ({
  onSelect,
}) => {
  const [open, setOpen] = React.useState(false);

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="rounded px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50"
      >
        + 添加
      </button>
      {open && (
        <div className="absolute top-full right-0 z-10 mt-1 w-28 rounded border border-gray-200 bg-white py-1 shadow-lg">
          {Object.entries(ACTION_TYPE_INFO).map(([type, info]) => (
            <button
              key={type}
              onClick={() => {
                onSelect(type as LinkageActionType);
                setOpen(false);
              }}
              className="w-full px-3 py-1 text-left text-xs text-gray-700 hover:bg-gray-50"
              title={info.description}
            >
              {info.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

const ActionBody: React.FC<{
  action: LinkageAction;
  index: number;
  onUpdate: (index: number, updates: Partial<LinkageAction>) => void;
  fieldOptions: { code: string; label: string }[];
  readonly: boolean;
}> = ({ action, index, onUpdate, fieldOptions, readonly }) => {
  switch (action.type) {
    case 'show':
    case 'hide':
    case 'enable':
    case 'disable':
      return (
        <FieldMultiSelect
          value={action.targets}
          onChange={(targets) => onUpdate(index, { targets } as Partial<LinkageAction>)}
          fieldOptions={fieldOptions}
          placeholder="选择目标字段"
          disabled={readonly}
        />
      );

    case 'setRequired':
      return (
        <div className="space-y-1.5">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={action.required}
              onChange={(e) =>
                onUpdate(index, { required: e.target.checked } as Partial<LinkageAction>)
              }
              className="rounded"
              disabled={readonly}
            />
            <span className="text-xs text-gray-600">设为必填</span>
          </label>
          <FieldMultiSelect
            value={action.targets}
            onChange={(targets) => onUpdate(index, { targets } as Partial<LinkageAction>)}
            fieldOptions={fieldOptions}
            placeholder="选择目标字段"
            disabled={readonly}
          />
        </div>
      );

    case 'setValue':
      return (
        <div className="space-y-1.5">
          <select
            value={action.target}
            onChange={(e) => onUpdate(index, { target: e.target.value } as Partial<LinkageAction>)}
            className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
            disabled={readonly}
          >
            <option value="">选择目标字段</option>
            {fieldOptions.map((f) => (
              <option key={f.code} value={f.code}>
                {f.label}
              </option>
            ))}
          </select>
          <input
            type="text"
            value={action.value}
            onChange={(e) => onUpdate(index, { value: e.target.value } as Partial<LinkageAction>)}
            placeholder="值或表达式，如: #province + '-' + #city"
            className="w-full rounded border border-gray-200 px-2 py-1 font-mono text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
            disabled={readonly}
          />
        </div>
      );

    case 'setOptions':
      return (
        <div className="space-y-1.5">
          <select
            value={action.target}
            onChange={(e) => onUpdate(index, { target: e.target.value } as Partial<LinkageAction>)}
            className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
            disabled={readonly}
          >
            <option value="">选择目标字段</option>
            {fieldOptions.map((f) => (
              <option key={f.code} value={f.code}>
                {f.label}
              </option>
            ))}
          </select>
          <select
            value={action.dataSource.type}
            onChange={(e) =>
              onUpdate(index, {
                dataSource: {
                  ...action.dataSource,
                  type: e.target.value as 'dict' | 'api' | 'parent',
                },
              } as Partial<LinkageAction>)
            }
            className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
            disabled={readonly}
          >
            <option value="dict">字典</option>
            <option value="api">API</option>
            <option value="parent">父级联动</option>
          </select>
          {action.dataSource.type === 'dict' && (
            <input
              type="text"
              value={action.dataSource.dictCode ?? ''}
              onChange={(e) =>
                onUpdate(index, {
                  dataSource: { ...action.dataSource, dictCode: e.target.value },
                } as Partial<LinkageAction>)
              }
              placeholder="字典编码"
              className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
              disabled={readonly}
            />
          )}
          {action.dataSource.type === 'api' && (
            <input
              type="text"
              value={action.dataSource.apiUrl ?? ''}
              onChange={(e) =>
                onUpdate(index, {
                  dataSource: { ...action.dataSource, apiUrl: e.target.value },
                } as Partial<LinkageAction>)
              }
              placeholder="API URL"
              className="w-full rounded border border-gray-200 px-2 py-1 font-mono text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
              disabled={readonly}
            />
          )}
          {action.dataSource.type === 'parent' && (
            <select
              value={action.dataSource.parentFieldCode ?? ''}
              onChange={(e) =>
                onUpdate(index, {
                  dataSource: { ...action.dataSource, parentFieldCode: e.target.value },
                } as Partial<LinkageAction>)
              }
              className="w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
              disabled={readonly}
            >
              <option value="">选择父级字段</option>
              {fieldOptions.map((f) => (
                <option key={f.code} value={f.code}>
                  {f.label}
                </option>
              ))}
            </select>
          )}
        </div>
      );

    case 'validate':
      return (
        <FieldMultiSelect
          value={action.targets}
          onChange={(targets) => onUpdate(index, { targets } as Partial<LinkageAction>)}
          fieldOptions={fieldOptions}
          placeholder="选择需要校验的字段"
          disabled={readonly}
        />
      );

    default:
      return null;
  }
};
