/**
 * ProgressField Component
 *
 * A percentage progress bar field.
 * Color changes based on progress value: red < 30%, yellow 30-70%, green > 70%.
 */

import React, { useCallback } from 'react';
import { cn } from '~/utils/cn';

export interface ProgressFieldProps {
  name?: string;
  label?: string;
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
  name,
  label,
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
      <div className={cn('space-y-2', className)}>
        {label && (
          <label htmlFor={name} className="text-text-2 block text-sm font-medium">
            {label}
          </label>
        )}
        <div className="flex items-center gap-2">
          <div className="rounded-pill h-2 flex-1 overflow-hidden bg-gray-200">
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
      </div>
    );
  }

  return (
    <div className={cn('space-y-2', className)}>
      {label && (
        <label htmlFor={name} className="text-text-2 block text-sm font-medium">
          {label}
        </label>
      )}
      <div className="flex items-center gap-2">
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
          id={name}
          name={name}
          aria-label={label}
          value={clampedValue}
          onChange={handleChange}
          min={0}
          max={100}
          className="border-border-strong focus-visible:shadow-focus w-14 rounded border px-1.5 py-0.5 text-right text-xs focus:outline-none"
        />
        <span className="text-text-2 text-xs">%</span>
      </div>
    </div>
  );
};

export default ProgressField;
