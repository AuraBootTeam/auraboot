/**
 * Status Badge Component
 *
 * Displays version status with consistent styling
 */

import React from 'react';
import { type VersionStatus, STATUS_BADGE_CONFIG } from '~/types/status';

interface StatusBadgeProps {
  status: VersionStatus;
  showDescription?: boolean;
  size?: 'small' | 'medium' | 'large';
  className?: string;
}

const COLOR_CLASSES = {
  gray: 'bg-gray-100 text-gray-800 border-gray-200',
  green: 'bg-green-100 text-green-800 border-green-200',
  orange: 'bg-orange-100 text-orange-800 border-orange-200',
  blue: 'bg-blue-100 text-blue-800 border-blue-200',
  red: 'bg-red-100 text-red-800 border-red-200',
};

const SIZE_CLASSES = {
  small: 'text-xs px-2 py-0.5',
  medium: 'text-sm px-2.5 py-1',
  large: 'text-base px-3 py-1.5',
};

export function StatusBadge({
  status,
  showDescription = false,
  size = 'medium',
  className = '',
}: StatusBadgeProps) {
  const config = STATUS_BADGE_CONFIG[status];

  if (!config) {
    return null;
  }

  const colorClass = COLOR_CLASSES[config.color];
  const sizeClass = SIZE_CLASSES[size];

  return (
    <span
      className={`inline-flex items-center rounded-md border font-medium ${colorClass} ${sizeClass} ${className}`}
      title={showDescription ? undefined : config.description}
    >
      {config.label}
      {showDescription && <span className="ml-1 text-xs opacity-75">({config.description})</span>}
    </span>
  );
}

/**
 * Status Badge with Icon
 */
interface StatusBadgeWithIconProps extends StatusBadgeProps {
  icon?: React.ReactNode;
}

export function StatusBadgeWithIcon({
  status,
  icon,
  showDescription = false,
  size = 'medium',
  className = '',
}: StatusBadgeWithIconProps) {
  const config = STATUS_BADGE_CONFIG[status];

  if (!config) {
    return null;
  }

  const colorClass = COLOR_CLASSES[config.color];
  const sizeClass = SIZE_CLASSES[size];

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-md border font-medium ${colorClass} ${sizeClass} ${className}`}
      title={showDescription ? undefined : config.description}
    >
      {icon}
      {config.label}
      {showDescription && <span className="ml-1 text-xs opacity-75">({config.description})</span>}
    </span>
  );
}

/**
 * Status Dot (compact version)
 */
interface StatusDotProps {
  status: VersionStatus;
  showLabel?: boolean;
  className?: string;
}

const DOT_COLOR_CLASSES = {
  gray: 'bg-gray-500',
  green: 'bg-green-500',
  orange: 'bg-orange-500',
  blue: 'bg-blue-500',
  red: 'bg-red-500',
};

export function StatusDot({ status, showLabel = true, className = '' }: StatusDotProps) {
  const config = STATUS_BADGE_CONFIG[status];

  if (!config) {
    return null;
  }

  const dotColorClass = DOT_COLOR_CLASSES[config.color];

  return (
    <span className={`inline-flex items-center gap-1.5 ${className}`} title={config.description}>
      <span className={`h-2 w-2 rounded-full ${dotColorClass}`} />
      {showLabel && <span className="text-sm text-gray-700">{config.label}</span>}
    </span>
  );
}
