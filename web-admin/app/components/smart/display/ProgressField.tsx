/**
 * ProgressField Component
 *
 * A percentage progress bar field.
 * Color changes based on progress value: red < 30%, yellow 30-70%, green > 70%.
 */

import React, { useCallback } from 'react';
import { cn } from '~/utils/cn';

export interface ProgressFieldProps {
  /** Current progress value (0-100) */
  value?: number;
  /** Callback when progress changes */
  onChange?: (value: number) => void;
  /** Whether the field is read-only */
  readOnly?: boolean;
  /** Whether to show the percentage label */
  showLabel?: boolean;
  /** Custom CSS class */
  className?: string;
}

function getProgressColor(value: number): string {
  if (value >= 70) return 'bg-green-500';
  if (value >= 30) return 'bg-yellow-500';
  return 'bg-red-500';
}

function getProgressTextColor(value: number): string {
  if (value >= 70) return 'text-green-600';
  if (value >= 30) return 'text-yellow-600';
  return 'text-red-600';
}

/**
 * ProgressField - Percentage progress bar
 */
export const ProgressField: React.FC<ProgressFieldProps> = ({
  value = 0,
  onChange,
  readOnly = false,
  showLabel = true,
  className,
}) => {
  const clampedValue = Math.max(0, Math.min(100, value));

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const newValue = Math.max(0, Math.min(100, Number(e.target.value) || 0));
      onChange?.(newValue);
    },
    [onChange],
  );

  if (readOnly) {
    return (
      <div className={cn('flex items-center gap-2', className)}>
        <div className="h-2 flex-1 overflow-hidden rounded-full bg-gray-200">
          <div
            className={cn(
              'h-full rounded-full transition-all duration-300',
              getProgressColor(clampedValue),
            )}
            style={{ width: `${clampedValue}%` }}
          />
        </div>
        {showLabel && (
          <span
            className={cn(
              'min-w-[36px] text-right text-xs font-medium',
              getProgressTextColor(clampedValue),
            )}
          >
            {clampedValue}%
          </span>
        )}
      </div>
    );
  }

  return (
    <div className={cn('flex items-center gap-2', className)}>
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-gray-200">
        <div
          className={cn(
            'h-full rounded-full transition-all duration-300',
            getProgressColor(clampedValue),
          )}
          style={{ width: `${clampedValue}%` }}
        />
      </div>
      <input
        type="number"
        value={clampedValue}
        onChange={handleChange}
        min={0}
        max={100}
        className="w-14 rounded border border-gray-300 px-1.5 py-0.5 text-right text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
      />
      <span className="text-xs text-gray-500">%</span>
    </div>
  );
};

export default ProgressField;
