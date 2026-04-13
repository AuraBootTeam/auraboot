import React, { forwardRef, useState, useRef, useEffect } from 'react';
import { useActionData } from 'react-router';
import clsx from 'clsx';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { FieldControl } from '~/components/ui/field-control';
import { FieldActionGroup } from '~/components/ui/field-action-group';
import { FieldActionButton } from '~/components/ui/field-action-button';

interface SelectOption {
  key: string;
  value: string;
  label: string;
  disabled?: boolean;
}

interface MultiSelectProps {
  label?: string;
  name: string;
  options: SelectOption[];
  value?: string[];
  defaultValue?: string[];
  placeholder?: string;
  maxSelection?: number;
  searchable?: boolean;
  error?: string;
  inline?: boolean;
  required?: boolean;
  readOnly?: boolean; // 新增只读属性
  onChange?: (values: string[]) => void;
}

export const MultiSelect = forwardRef<HTMLDivElement, MultiSelectProps>(
  (
    {
      label,
      name,
      options,
      value,
      defaultValue = [],
      placeholder = '请选择',
      maxSelection,
      searchable = false,
      error: propError,
      inline = false,
      required = false,
      readOnly = false, // 新增只读属性，默认为false
      onChange,
    },
    ref,
  ) => {
    const st = useSmartText();
    const {
      labelText,
      placeholderText,
      required: requiredValue,
    } = useSmartFieldContract({
      label,
      placeholder,
      required,
    });
    const [isOpen, setIsOpen] = useState(false);
    const [searchTerm, setSearchTerm] = useState('');
    const [internalValue, setInternalValue] = useState<string[]>(defaultValue);
    const dropdownRef = useRef<HTMLDivElement>(null);

    const actionData = useActionData();
    const actionError =
      actionData?.error?.data?.name === name ? actionData?.error?.data?.desc : undefined;

    const error = propError || actionError;
    const meta = useSmartFieldMeta({ externalError: error });

    // 判断是否为受控组件
    const isControlled = value !== undefined;
    const currentValue = isControlled ? value : internalValue;

    const safeOptions = options ?? [];
    const filteredOptions = searchable
      ? safeOptions.filter((option) =>
          option.label.toLowerCase().includes(searchTerm.toLowerCase()),
        )
      : safeOptions;

    const safeCurrentValue = Array.isArray(currentValue) ? currentValue : [];
    const selectedOptions = safeOptions.filter((option) => safeCurrentValue.includes(option.value));

    useEffect(() => {
      const handleClickOutside = (event: MouseEvent) => {
        if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
          setIsOpen(false);
        }
      };

      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    const handleOptionToggle = (optionValue: string) => {
      if (readOnly) return; // 只读模式下不允许操作

      let newValue;
      if (currentValue.includes(optionValue)) {
        newValue = currentValue.filter((v) => v !== optionValue);
      } else {
        if (maxSelection && currentValue.length >= maxSelection) {
          return;
        }
        newValue = [...currentValue, optionValue];
      }

      // 如果是非受控组件，更新内部状态
      if (!isControlled) {
        setInternalValue(newValue);
      }

      // 通知父组件
      onChange?.(newValue);
      meta.markTouched();
    };

    const handleRemoveOption = (optionValue: string) => {
      if (readOnly) return; // 只读模式下不允许操作

      const newValue = currentValue.filter((v) => v !== optionValue);

      // 如果是非受控组件，更新内部状态
      if (!isControlled) {
        setInternalValue(newValue);
      }

      // 通知父组件
      onChange?.(newValue);
      meta.markTouched();
    };

    const selectElement = (
      <FieldControl
        inline={inline}
        ref={dropdownRef}
        rightSlot={
          <FieldActionGroup>
            {!readOnly && currentValue.length > 0 && (
              <FieldActionButton
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  const clearedValue: string[] = [];
                  if (!isControlled) {
                    setInternalValue(clearedValue);
                  }
                  onChange?.(clearedValue);
                  meta.markTouched();
                }}
                iconOnly
              >
                ×
              </FieldActionButton>
            )}
          </FieldActionGroup>
        }
      >
        <div
          className={clsx(
            'min-h-[2.5rem] rounded-lg border px-3 py-2 transition-all',
            readOnly
              ? 'cursor-default bg-gray-50 dark:bg-gray-800'
              : 'cursor-pointer focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none',
            error ? 'border-red-300' : 'border-gray-300',
            'dark:border-gray-600 dark:bg-gray-700 dark:text-white',
          )}
          onClick={() => !readOnly && setIsOpen(!isOpen)} // 只读模式下不允许打开下拉菜单
        >
          {selectedOptions.length === 0 ? (
            <span className="text-gray-500">{placeholderText}</span>
          ) : (
            <div className="flex flex-wrap gap-1">
              {selectedOptions.map((option) => (
                <span
                  key={option.key}
                  className="inline-flex items-center rounded-md bg-blue-100 px-2 py-1 text-xs text-blue-800"
                >
                  {option.label}
                  {!readOnly && ( // 只读模式下不显示删除按钮
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleRemoveOption(option.value);
                      }}
                      className="ml-1 text-blue-600 hover:text-blue-800"
                    >
                      ×
                    </button>
                  )}
                </span>
              ))}
            </div>
          )}
        </div>

        {isOpen &&
          !readOnly && ( // 只读模式下不显示下拉菜单
            <div className="absolute z-10 mt-1 w-full rounded-lg border border-gray-300 bg-white shadow-lg dark:border-gray-600 dark:bg-gray-700">
              {searchable && (
                <div className="border-b border-gray-200 p-2 dark:border-gray-600">
                  <input
                    type="text"
                    placeholder={st('搜索选项...')}
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                    className="w-full rounded border border-gray-300 px-2 py-1 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-500 dark:bg-gray-600 dark:text-white"
                  />
                </div>
              )}

              <div className="max-h-60 overflow-y-auto">
                {filteredOptions.map((option) => (
                  <div
                    key={option.key}
                    className={clsx(
                      'cursor-pointer px-3 py-2 hover:bg-gray-100 dark:hover:bg-gray-600',
                      currentValue.includes(option.value) && 'bg-blue-50 dark:bg-blue-900',
                      option.disabled && 'cursor-not-allowed opacity-50',
                    )}
                    onClick={() => !option.disabled && handleOptionToggle(option.value)}
                  >
                    <div className="flex items-center space-x-2">
                      <input
                        type="checkbox"
                        checked={currentValue.includes(option.value)}
                        disabled={option.disabled}
                        readOnly
                        className="h-4 w-4 rounded border-gray-300 text-blue-600"
                      />
                      <span className="text-sm text-gray-700 dark:text-gray-300">
                        {option.label}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
      </FieldControl>
    );

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        inline={inline}
        error={meta.showError ? st(meta.meta.error) : undefined}
      >
        {/* 使用JSON字符串存储数组数据 */}
        <input type="hidden" name={name} value={currentValue} />

        {selectElement}
      </FieldBase>
    );
  },
);

MultiSelect.displayName = 'MultiSelect';

export default MultiSelect;
