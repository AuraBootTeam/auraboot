/**
 * Unified empty state component for all designers.
 * Provides consistent visual style across Report, Dashboard, BPMN, and Page designers.
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N } from './designerI18n';

export interface DesignerEmptyStateProps {
  /** Optional custom icon (React node). Defaults to a layout grid icon. */
  icon?: React.ReactNode;
  /** Primary message key from DESIGNER_I18N or a LocalizedText record */
  title: Record<string, string>;
  /** Secondary message */
  subtitle?: Record<string, string>;
  /** Visual variant */
  variant?: 'dashed' | 'subtle';
  /** Test ID for E2E */
  testId?: string;
}

const DefaultIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth={1.5}
      d="M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z"
    />
  </svg>
);

function resolveText(text: Record<string, string>, locale: string): string {
  return text[locale] || text['en-US'] || text['zh-CN'] || Object.values(text)[0] || '';
}

export const DesignerEmptyState: React.FC<DesignerEmptyStateProps> = ({
  icon,
  title,
  subtitle,
  variant = 'dashed',
  testId = 'designer-empty-state',
}) => {
  const { locale } = useI18n();

  const titleText = resolveText(title, locale);
  const subtitleText = subtitle ? resolveText(subtitle, locale) : undefined;

  if (variant === 'subtle') {
    return (
      <div
        data-testid={testId}
        className="flex h-full flex-col items-center justify-center text-gray-400"
      >
        <div className="mb-4 text-gray-300">
          {icon || <DefaultIcon className="h-16 w-16" />}
        </div>
        <p className="text-lg font-medium">{titleText}</p>
        {subtitleText && <p className="mt-1 text-sm">{subtitleText}</p>}
      </div>
    );
  }

  // variant === 'dashed'
  return (
    <div
      data-testid={testId}
      className="flex h-48 flex-col items-center justify-center rounded-lg border-2 border-dashed border-gray-300 text-gray-400"
    >
      <div className="mb-3 text-gray-300">
        {icon || <DefaultIcon className="h-12 w-12" />}
      </div>
      <p className="text-sm font-medium">{titleText}</p>
      {subtitleText && <p className="mt-1 text-xs">{subtitleText}</p>}
    </div>
  );
};
