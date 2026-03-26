/**
 * Binding Status Component
 *
 * Displays binding status with icon and color.
 *
 * @since 3.2.0
 */

import React from 'react';

export type BindingStatusType = 'valid' | 'warning' | 'error' | 'orphan' | 'unbound';

interface BindingStatusProps {
  status: BindingStatusType;
  showLabel?: boolean;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const STATUS_CONFIG: Record<
  BindingStatusType,
  { icon: string; color: string; bgColor: string; label: string }
> = {
  valid: {
    icon: '✓',
    color: 'text-green-600',
    bgColor: 'bg-green-100',
    label: '正常',
  },
  warning: {
    icon: '⚠',
    color: 'text-yellow-600',
    bgColor: 'bg-yellow-100',
    label: '警告',
  },
  error: {
    icon: '✕',
    color: 'text-red-600',
    bgColor: 'bg-red-100',
    label: '错误',
  },
  orphan: {
    icon: '?',
    color: 'text-orange-600',
    bgColor: 'bg-orange-100',
    label: '孤立',
  },
  unbound: {
    icon: '○',
    color: 'text-gray-400',
    bgColor: 'bg-gray-100',
    label: '未绑定',
  },
};

const SIZE_CONFIG = {
  sm: 'w-4 h-4 text-[10px]',
  md: 'w-5 h-5 text-xs',
  lg: 'w-6 h-6 text-sm',
};

/**
 * Binding Status Indicator
 */
export const BindingStatus: React.FC<BindingStatusProps> = ({
  status,
  showLabel = false,
  size = 'md',
  className = '',
}) => {
  const config = STATUS_CONFIG[status];
  const sizeClass = SIZE_CONFIG[size];

  return (
    <span className={`inline-flex items-center gap-1 ${className}`}>
      <span
        className={`inline-flex items-center justify-center rounded-full ${config.bgColor} ${config.color} ${sizeClass} `}
        title={config.label}
      >
        {config.icon}
      </span>
      {showLabel && <span className={`text-xs ${config.color}`}>{config.label}</span>}
    </span>
  );
};

export default BindingStatus;
