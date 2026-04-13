/**
 * Binding Toggle Component
 *
 * Toggle button to switch between static value and expression mode.
 *
 * @since 3.2.0
 */

import React, { useCallback } from 'react';

export type BindingMode = 'static' | 'expression';

export interface BindingToggleProps {
  /** Current binding mode */
  mode: BindingMode;
  /** Mode change handler */
  onModeChange: (mode: BindingMode) => void;
  /** Disabled state */
  disabled?: boolean;
  /** Size variant */
  size?: 'sm' | 'md';
  /** Show label */
  showLabel?: boolean;
  /** Custom class name */
  className?: string;
}

/**
 * Binding Toggle Component
 */
export const BindingToggle: React.FC<BindingToggleProps> = ({
  mode,
  onModeChange,
  disabled = false,
  size = 'sm',
  showLabel = false,
  className = '',
}) => {
  const isExpression = mode === 'expression';

  const handleToggle = useCallback(() => {
    if (!disabled) {
      onModeChange(isExpression ? 'static' : 'expression');
    }
  }, [isExpression, onModeChange, disabled]);

  const sizeClasses = size === 'sm' ? 'px-1.5 py-0.5 text-[10px]' : 'px-2 py-1 text-xs';

  return (
    <button
      type="button"
      onClick={handleToggle}
      disabled={disabled}
      className={`inline-flex items-center gap-1 rounded font-mono transition-colors ${sizeClasses} ${
        isExpression
          ? 'bg-blue-100 text-blue-700 hover:bg-blue-200'
          : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
      } ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} ${className} `}
      title={isExpression ? '切换为静态值' : '切换为表达式'}
    >
      <span className="font-bold">fx</span>
      {showLabel && <span className="font-sans">{isExpression ? '表达式' : '静态'}</span>}
    </button>
  );
};

/**
 * Binding Indicator Component
 *
 * Small indicator showing current binding state.
 */
export interface BindingIndicatorProps {
  mode: BindingMode;
  className?: string;
}

export const BindingIndicator: React.FC<BindingIndicatorProps> = ({ mode, className = '' }) => {
  if (mode !== 'expression') return null;

  return (
    <span
      className={`inline-flex h-4 w-4 items-center justify-center rounded bg-blue-100 font-mono text-[9px] font-bold text-blue-600 ${className}`}
      title="表达式绑定"
    >
      fx
    </span>
  );
};

export default BindingToggle;
