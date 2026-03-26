import { useState } from 'react';
import { CalendarIcon } from '@heroicons/react/24/outline';
import type { CustomDateRange } from '~/routes/reports/overview/types';

interface TimeRangeSelectorProps {
  value: 'today' | 'week' | 'month' | 'quarter' | 'year' | 'custom';
  customDateRange?: CustomDateRange;
  onChange: (
    timeRange: 'today' | 'week' | 'month' | 'quarter' | 'year' | 'custom',
    customRange?: CustomDateRange,
  ) => void;
}

export function TimeRangeSelector({ value, customDateRange, onChange }: TimeRangeSelectorProps) {
  const [showCustomPicker, setShowCustomPicker] = useState(false);
  const [tempDateRange, setTempDateRange] = useState<CustomDateRange>({
    start: customDateRange?.start || customDateRange?.startDate || '',
    end: customDateRange?.end || customDateRange?.endDate || '',
  });

  const timeRangeOptions = [
    { value: 'today', label: '今天' },
    { value: 'week', label: '本周' },
    { value: 'month', label: '本月' },
    { value: 'quarter', label: '本季度' },
    { value: 'year', label: '本年' },
    { value: 'custom', label: '自定义' },
  ] as const;

  const handleTimeRangeChange = (newValue: typeof value) => {
    if (newValue === 'custom') {
      setShowCustomPicker(true);
    } else {
      setShowCustomPicker(false);
      onChange(newValue);
    }
  };

  const handleCustomDateConfirm = () => {
    if (tempDateRange.start && tempDateRange.end) {
      onChange('custom', tempDateRange);
      setShowCustomPicker(false);
    }
  };

  const handleCustomDateCancel = () => {
    setShowCustomPicker(false);
    setTempDateRange({
      start: customDateRange?.start || customDateRange?.startDate || '',
      end: customDateRange?.end || customDateRange?.endDate || '',
    });
  };

  return (
    <div className="relative">
      <div className="flex items-center space-x-2">
        <CalendarIcon className="h-5 w-5 text-gray-400" />
        <select
          value={value}
          onChange={(e) => handleTimeRangeChange(e.target.value as typeof value)}
          className="rounded-md border border-gray-300 bg-white px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          {timeRangeOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {showCustomPicker && (
        <div className="absolute top-full left-0 z-10 mt-2 min-w-80 rounded-md border border-gray-300 bg-white p-4 shadow-lg">
          <div className="space-y-4">
            <h4 className="text-sm font-medium text-gray-900">选择日期范围</h4>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-700">开始日期</label>
                <input
                  type="date"
                  value={tempDateRange.start}
                  onChange={(e) => setTempDateRange({ ...tempDateRange, start: e.target.value })}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-700">结束日期</label>
                <input
                  type="date"
                  value={tempDateRange.end}
                  onChange={(e) => setTempDateRange({ ...tempDateRange, end: e.target.value })}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
            </div>

            <div className="flex justify-end space-x-2">
              <button
                onClick={handleCustomDateCancel}
                className="rounded border border-gray-300 bg-gray-100 px-3 py-1 text-sm text-gray-600 hover:bg-gray-200"
              >
                取消
              </button>
              <button
                onClick={handleCustomDateConfirm}
                disabled={!tempDateRange.start || !tempDateRange.end}
                className="rounded border border-transparent bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                确认
              </button>
            </div>
          </div>
        </div>
      )}

      {value === 'custom' &&
        (customDateRange?.start || customDateRange?.startDate) &&
        (customDateRange?.end || customDateRange?.endDate) && (
          <div className="mt-2 text-sm text-gray-600">
            {customDateRange.start || customDateRange.startDate} 至{' '}
            {customDateRange.end || customDateRange.endDate}
          </div>
        )}
    </div>
  );
}
