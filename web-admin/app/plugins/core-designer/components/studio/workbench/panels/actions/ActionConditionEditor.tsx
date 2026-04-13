import React from 'react';

interface ActionConditionEditorProps {
  condition?: string;
  onChange: (condition: string) => void;
  readonly?: boolean;
}

/**
 * Action Condition Editor - expression editor for action visibility conditions.
 * Uses SpEL-style expressions evaluated at runtime.
 */
export const ActionConditionEditor: React.FC<ActionConditionEditorProps> = ({
  condition,
  onChange,
  readonly = false,
}) => {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-gray-500">
        显示条件
        <span className="ml-1 font-normal text-gray-400">(SpEL)</span>
      </label>
      <div className="relative">
        <input
          type="text"
          value={condition || ''}
          onChange={(e) => onChange(e.target.value)}
          placeholder="例: #state == 'draft'"
          className="w-full rounded-md border border-gray-300 px-3 py-1.5 font-mono text-sm focus:border-transparent focus:ring-1 focus:ring-blue-400 focus:outline-none"
          disabled={readonly}
        />
        {condition && (
          <button
            onClick={() => onChange('')}
            className="absolute top-1/2 right-2 -translate-y-1/2 p-0.5 text-gray-300 hover:text-gray-500"
            title="清除条件"
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
      <p className="mt-0.5 text-[10px] text-gray-400">
        可用变量: #state, #record, #user, #formData
      </p>
    </div>
  );
};
