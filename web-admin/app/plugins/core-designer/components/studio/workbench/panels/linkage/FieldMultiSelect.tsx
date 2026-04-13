import React from 'react';

interface FieldMultiSelectProps {
  value: string[];
  onChange: (value: string[]) => void;
  fieldOptions: { code: string; label: string }[];
  placeholder?: string;
  disabled?: boolean;
}

/**
 * FieldMultiSelect - multi-select dropdown for field codes.
 *
 * @since 3.5.0
 */
export const FieldMultiSelect: React.FC<FieldMultiSelectProps> = ({
  value,
  onChange,
  fieldOptions,
  placeholder = '选择字段',
  disabled = false,
}) => {
  const toggleField = (code: string) => {
    if (disabled) return;
    if (value.includes(code)) {
      onChange(value.filter((v) => v !== code));
    } else {
      onChange([...value, code]);
    }
  };

  const selectedLabels = value
    .map((code) => fieldOptions.find((f) => f.code === code)?.label ?? code)
    .join(', ');

  return (
    <div className="space-y-1">
      {/* Selected display */}
      <div className="flex min-h-[28px] flex-wrap items-center gap-1 rounded border border-gray-200 bg-gray-50 px-2 py-1 text-xs">
        {value.length === 0 && <span className="text-gray-400">{placeholder}</span>}
        {value.map((code) => {
          const label = fieldOptions.find((f) => f.code === code)?.label ?? code;
          return (
            <span
              key={code}
              className="inline-flex items-center gap-0.5 rounded bg-blue-100 px-1.5 py-0.5 text-[10px] text-blue-700"
            >
              {label}
              {!disabled && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    toggleField(code);
                  }}
                  className="text-blue-400 hover:text-blue-600"
                >
                  &times;
                </button>
              )}
            </span>
          );
        })}
      </div>

      {/* Options list */}
      {!disabled && (
        <div className="max-h-32 overflow-y-auto rounded border border-gray-200">
          {fieldOptions.length === 0 && (
            <div className="px-2 py-1.5 text-xs text-gray-400">暂无可选字段</div>
          )}
          {fieldOptions.map((field) => (
            <label
              key={field.code}
              className="flex cursor-pointer items-center gap-2 px-2 py-1 hover:bg-gray-50"
            >
              <input
                type="checkbox"
                checked={value.includes(field.code)}
                onChange={() => toggleField(field.code)}
                className="rounded text-blue-500"
              />
              <span className="truncate text-xs text-gray-700">{field.label}</span>
              <span className="ml-auto text-[10px] text-gray-400">{field.code}</span>
            </label>
          ))}
        </div>
      )}
    </div>
  );
};
