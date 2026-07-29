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
  gray: 'bg-subtle text-text border-border',
  green: 'bg-status-green-bg text-status-green border-status-green',
  orange: 'bg-status-amber-bg text-status-amber border-status-amber',
  blue: 'bg-accent-weak text-accent border-accent',
  red: 'bg-status-red-bg text-status-red border-status-red',
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
  gray: 'bg-subtle0',
  green: 'bg-status-green-bg0',
  orange: 'bg-status-amber-bg0',
  blue: 'bg-accent',
  red: 'bg-status-red-bg0',
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
      {showLabel && <span className="text-text-2 text-sm">{config.label}</span>}
    </span>
  );
}
