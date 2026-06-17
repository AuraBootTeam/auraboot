import React from 'react';
import { Calendar, Clock } from 'lucide-react';
import { useTimezone } from '~/contexts/TimezoneContext';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';

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
  format: _format = 'YYYY-MM-DD HH:mm:ss',
  showTime = true,
  allowClear = true,
  className = '',
}) => {
  const { timezone } = useTimezone();

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
    // Backend emits UTC; convert to the effective display timezone for the
    // read-only view via the canonical formatter.
    const fmt = showTime ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD';
    return formatInTimezone(dateValue, fmt, timezone) || dateValue;
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
        <label className="text-text-2 block text-sm font-medium">
          {label}
          {required && <span className="text-status-red ml-1">*</span>}
        </label>
      )}

      <div className="relative">
        {readOnly ? (
          <div className="rounded-control border-border-strong bg-subtle text-text w-full border px-3 py-2">
            <div className="flex items-center space-x-2">
              {showTime ? (
                <Clock className="text-text-3 h-4 w-4" />
              ) : (
                <Calendar className="text-text-3 h-4 w-4" />
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
              className={`rounded-control border-border-strong w-full border px-3 py-2 pl-10 shadow-sm ${disabled ? 'bg-subtle cursor-not-allowed' : 'bg-panel'} focus-visible:shadow-focus focus:outline-none ${value ? 'text-text' : 'text-text-2'} `}
            />

            {/* Icon */}
            <div className="absolute top-1/2 left-3 -translate-y-1/2 transform">
              {showTime ? (
                <Clock className="text-text-3 h-4 w-4" />
              ) : (
                <Calendar className="text-text-3 h-4 w-4" />
              )}
            </div>

            {/* Clear button */}
            {value && allowClear && !disabled && (
              <button
                type="button"
                onClick={handleClear}
                className="text-text-3 hover:text-text-2 absolute top-1/2 right-3 -translate-y-1/2 transform"
              >
                ×
              </button>
            )}
          </div>
        )}
      </div>

      {/* Format hint */}
      {!readOnly && (
        <div className="text-text-2 text-xs">
          格式: {showTime ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'}
        </div>
      )}
    </div>
  );
};

export default Datetime;
