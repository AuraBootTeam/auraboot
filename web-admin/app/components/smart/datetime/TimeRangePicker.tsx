import React, { useState } from 'react';
import { Clock, X } from 'lucide-react';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import { FieldControl } from '~/components/ui/field-control';
import { FieldActionGroup } from '~/components/ui/field-action-group';
import { FieldActionButton } from '~/components/ui/field-action-button';

interface TimeRange {
  start: string;
  end: string;
}

interface TimeRangePickerProps {
  name: string;
  label?: string;
  placeholder?: string;
  value?: TimeRange;
  onChange?: (value: TimeRange | undefined) => void;
  disabled?: boolean;
  required?: boolean;
  allowClear?: boolean;
  format?: '12h' | '24h';
  minuteStep?: number;
  className?: string;
}

export const TimeRangePicker: React.FC<TimeRangePickerProps> = ({
  name,
  label,
  placeholder = '请选择时间范围',
  value,
  onChange,
  disabled = false,
  required = false,
  allowClear = true,
  format = '24h',
  minuteStep = 15,
  className = '',
}) => {
  const st = useSmartText();
  const {
    labelText,
    placeholderText,
    required: requiredValue,
    disabled: disabledValue,
  } = useSmartFieldContract({
    label,
    placeholder,
    required,
    disabled,
  });
  const meta = useSmartFieldMeta({ externalError: undefined });
  const [isOpen, setIsOpen] = useState(false);
  const [activeField, setActiveField] = useState<'start' | 'end'>('start');

  // Generate time options
  const generateTimeOptions = () => {
    const options: string[] = [];
    for (let hour = 0; hour < 24; hour++) {
      for (let minute = 0; minute < 60; minute += minuteStep) {
        const timeStr =
          format === '24h'
            ? `${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}`
            : formatTo12Hour(hour, minute);
        options.push(timeStr);
      }
    }
    return options;
  };

  const formatTo12Hour = (hour: number, minute: number) => {
    const period = hour >= 12 ? 'PM' : 'AM';
    const displayHour = hour === 0 ? 12 : hour > 12 ? hour - 12 : hour;
    return `${displayHour}:${minute.toString().padStart(2, '0')} ${period}`;
  };

  const convertTo24Hour = (time12h: string) => {
    if (format === '24h') return time12h;

    const [time, period] = time12h.split(' ');
    const [hour, minute] = time.split(':').map(Number);
    let hour24 = hour;

    if (period === 'AM' && hour === 12) hour24 = 0;
    if (period === 'PM' && hour !== 12) hour24 = hour + 12;

    return `${hour24.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}`;
  };

  const timeOptions = generateTimeOptions();

  // Preset time ranges
  const presetRanges = [
    { label: '全天', value: { start: '00:00', end: '23:59' } },
    { label: '上午', value: { start: '09:00', end: '12:00' } },
    { label: '下午', value: { start: '13:00', end: '18:00' } },
    { label: '晚上', value: { start: '18:00', end: '22:00' } },
    { label: '工作时间', value: { start: '09:00', end: '18:00' } },
    { label: '午休时间', value: { start: '12:00', end: '14:00' } },
  ];

  const handleTimeSelect = (time: string) => {
    const time24h = convertTo24Hour(time);

    if (activeField === 'start') {
      const newValue = { start: time24h, end: value?.end || time24h };
      onChange?.(newValue);
      setActiveField('end');
    } else {
      const newValue = { start: value?.start || time24h, end: time24h };
      onChange?.(newValue);
      setIsOpen(false);
    }
    meta.markTouched();
  };

  const handlePresetSelect = (preset: TimeRange) => {
    onChange?.(preset);
    setIsOpen(false);
    meta.markTouched();
  };

  const handleClear = () => {
    onChange?.(undefined);
    meta.markTouched();
  };

  const formatDisplayTime = (time: string) => {
    if (!time) return '';
    if (format === '24h') return time;

    const [hour, minute] = time.split(':').map(Number);
    return formatTo12Hour(hour, minute);
  };

  const displayText = () => {
    if (!value || !value.start || !value.end) return placeholderText;
    return `${formatDisplayTime(value.start)} - ${formatDisplayTime(value.end)}`;
  };

  const isValidRange = (range: TimeRange) => {
    if (!range.start || !range.end) return false;
    return range.start <= range.end;
  };

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      error={meta.showError ? st(meta.meta.error) : undefined}
      className={`relative space-y-2 ${className}`}
    >
      <FieldControl
        rightSlot={
          <FieldActionGroup>
            {value && value.start && value.end && allowClear && !disabledValue && (
              <FieldActionButton
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  handleClear();
                }}
                iconOnly
              >
                <X className="h-4 w-4" />
              </FieldActionButton>
            )}
          </FieldActionGroup>
        }
      >
        <div
          className={`w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm ${disabledValue ? 'cursor-not-allowed bg-gray-50' : 'cursor-pointer bg-white hover:border-gray-400'} ${value && value.start && value.end ? 'text-gray-900' : 'text-gray-500'} ${value && !isValidRange(value) ? 'border-red-300' : ''} focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none`}
          onClick={() => !disabledValue && setIsOpen(!isOpen)}
        >
          <div className="flex items-center justify-between">
            <div className="flex min-w-0 flex-1 items-center space-x-2">
              <Clock className="h-4 w-4 flex-shrink-0 text-gray-400" />
              <span className="truncate">{displayText()}</span>
            </div>
          </div>
        </div>

        {/* Validation Error */}
        {value && !isValidRange(value) && (
          <div className="mt-1 text-sm text-red-600">{st('结束时间不能早于开始时间')}</div>
        )}

        {/* Dropdown */}
        {isOpen && !disabledValue && (
          <div className="absolute z-50 mt-1 w-full rounded-md border border-gray-300 bg-white shadow-lg">
            <div className="flex">
              {/* Preset Ranges */}
              <div className="w-1/3 border-r border-gray-200">
                <div className="border-b border-gray-200 p-3">
                  <h4 className="text-sm font-medium text-gray-700">{st('预设范围')}</h4>
                </div>
                <div className="max-h-60 overflow-y-auto">
                  {presetRanges.map((preset, index) => (
                    <div
                      key={index}
                      className="cursor-pointer p-2 text-sm hover:bg-gray-50"
                      onClick={() => handlePresetSelect(preset.value)}
                    >
                      <div className="font-medium">{st(preset.label)}</div>
                      <div className="text-xs text-gray-500">
                        {formatDisplayTime(preset.value.start)} -{' '}
                        {formatDisplayTime(preset.value.end)}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Time Selection */}
              <div className="flex-1">
                <div className="border-b border-gray-200 p-3">
                  <div className="flex space-x-2">
                    <button
                      className={`rounded px-3 py-1 text-sm ${activeField === 'start' ? 'bg-blue-100 text-blue-700' : 'text-gray-600 hover:bg-gray-100'} `}
                      onClick={() => setActiveField('start')}
                    >
                      {st('开始时间')}
                    </button>
                    <button
                      className={`rounded px-3 py-1 text-sm ${activeField === 'end' ? 'bg-blue-100 text-blue-700' : 'text-gray-600 hover:bg-gray-100'} `}
                      onClick={() => setActiveField('end')}
                    >
                      {st('结束时间')}
                    </button>
                  </div>
                  {value && (
                    <div className="mt-2 text-sm text-gray-600">
                      {st('当前选择')}:{' '}
                      {value.start && value.end ? displayText() : st('请选择时间')}
                    </div>
                  )}
                </div>

                <div className="max-h-60 overflow-y-auto">
                  <div className="grid grid-cols-4 gap-1 p-2">
                    {timeOptions.map((time, index) => {
                      const time24h = convertTo24Hour(time);
                      const isSelected =
                        activeField === 'start' ? value?.start === time24h : value?.end === time24h;

                      return (
                        <button
                          key={index}
                          className={`rounded p-2 text-xs hover:bg-gray-100 ${isSelected ? 'bg-blue-100 text-blue-700' : 'text-gray-700'} `}
                          onClick={() => handleTimeSelect(time)}
                        >
                          {time}
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>

            {/* Footer */}
            <div className="flex items-center justify-between border-t border-gray-200 p-3">
              <div className="text-sm text-gray-500">
                {activeField === 'start' ? st('选择开始时间') : st('选择结束时间')}
              </div>
              <div className="space-x-2">
                <button
                  className="px-3 py-1 text-sm text-gray-600 hover:text-gray-800"
                  onClick={() => setIsOpen(false)}
                >
                  {st('取消')}
                </button>
                <button
                  className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700"
                  onClick={() => setIsOpen(false)}
                  disabled={!value || !value.start || !value.end || !isValidRange(value)}
                >
                  {st('确定')}
                </button>
              </div>
            </div>
          </div>
        )}
      </FieldControl>

      <input type="hidden" name={`${name}.start`} value={value?.start || ''} />
      <input type="hidden" name={`${name}.end`} value={value?.end || ''} />
    </FieldBase>
  );
};

export default TimeRangePicker;
