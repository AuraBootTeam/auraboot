import React, { useState } from 'react';
import dayjs from 'dayjs';
import { Calendar, Clock } from 'lucide-react';

interface DatetimeProps {
  name: string;
  label?: string;
  placeholder?: string;
  value?: string;
  onChange?: (value: string | undefined) => void;
  disabled?: boolean;
  required?: boolean;
  readOnly?: boolean;
  visible?: boolean;
  format?: string;
  showTime?: boolean;
  allowClear?: boolean;
  className?: string;
}

export const Datetime: React.FC<DatetimeProps> = ({
  name,
  label,
  placeholder = '请选择日期时间',
  value,
  onChange,
  disabled = false,
  required = false,
  readOnly = false,
  visible = true,
  format = 'YYYY-MM-DD HH:mm:ss',
  showTime = true,
  allowClear = true,
  className = '',
}) => {
  const [isOpen, setIsOpen] = useState(false);

  const handleDateTimeChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = event.target.value;
    onChange?.(newValue || undefined);
  };

  const handleClear = (event: React.MouseEvent) => {
    event.stopPropagation();
    onChange?.(undefined);
  };

  const formatDisplayValue = (dateValue?: string) => {
    if (!dateValue) return '';

    const d = dayjs(dateValue);
    if (!d.isValid()) return dateValue;

    return showTime ? d.format('YYYY-MM-DD HH:mm:ss') : d.format('YYYY-MM-DD');
  };

  const getInputType = () => {
    return showTime ? 'datetime-local' : 'date';
  };

  const convertValueForInput = (dateValue?: string) => {
    if (!dateValue) return '';

    try {
      const date = new Date(dateValue);
      if (isNaN(date.getTime())) return '';

      if (showTime) {
        // Convert to YYYY-MM-DDTHH:mm format for datetime-local input
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        return `${year}-${month}-${day}T${hours}:${minutes}`;
      } else {
        // Convert to YYYY-MM-DD format for date input
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
      }
    } catch {
      return '';
    }
  };

  if (!visible) {
    return null;
  }

  return (
    <div className={`space-y-2 ${className}`}>
      {label && (
        <label className="block text-sm font-medium text-gray-700">
          {label}
          {required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}

      <div className="relative">
        {readOnly ? (
          <div className="w-full rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-gray-900">
            <div className="flex items-center space-x-2">
              {showTime ? (
                <Clock className="h-4 w-4 text-gray-400" />
              ) : (
                <Calendar className="h-4 w-4 text-gray-400" />
              )}
              <span>{formatDisplayValue(value) || placeholder}</span>
            </div>
          </div>
        ) : (
          <div className="relative">
            <input
              type={getInputType()}
              name={name}
              value={convertValueForInput(value)}
              onChange={handleDateTimeChange}
              disabled={disabled}
              required={required}
              placeholder={placeholder}
              className={`w-full rounded-md border border-gray-300 px-3 py-2 pl-10 shadow-sm ${disabled ? 'cursor-not-allowed bg-gray-50' : 'bg-white'} focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none ${value ? 'text-gray-900' : 'text-gray-500'} `}
            />

            {/* Icon */}
            <div className="absolute top-1/2 left-3 -translate-y-1/2 transform">
              {showTime ? (
                <Clock className="h-4 w-4 text-gray-400" />
              ) : (
                <Calendar className="h-4 w-4 text-gray-400" />
              )}
            </div>

            {/* Clear button */}
            {value && allowClear && !disabled && (
              <button
                type="button"
                onClick={handleClear}
                className="absolute top-1/2 right-3 -translate-y-1/2 transform text-gray-400 hover:text-gray-600"
              >
                ×
              </button>
            )}
          </div>
        )}
      </div>

      {/* Format hint */}
      {!readOnly && (
        <div className="text-xs text-gray-500">
          格式: {showTime ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'}
        </div>
      )}
    </div>
  );
};

export default Datetime;
