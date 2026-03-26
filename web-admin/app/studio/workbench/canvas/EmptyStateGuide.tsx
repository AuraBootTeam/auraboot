/**
 * EmptyStateGuide Component
 *
 * Displays a helpful guide when the canvas is empty,
 * helping users understand how to start designing.
 *
 * @since 3.2.0
 */

import React from 'react';
import type { PageMode } from '../../services/page-manager';
import { PAGE_MODE_INFO } from '../../services/page-manager';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';

/**
 * EmptyStateGuide props
 */
export interface EmptyStateGuideProps {
  /** Current page mode */
  mode?: PageMode;
  /** Whether to show compact version */
  compact?: boolean;
  /** Callback when user clicks to add component */
  onAddComponent?: () => void;
  /** Callback when user wants to use a template */
  onUseTemplate?: () => void;
  /** Callback when user wants to import */
  onImport?: () => void;
}

/**
 * Quick start item
 */
interface QuickStartItem {
  id: string;
  icon: React.ReactNode;
  title: string;
  description: string;
  action: () => void;
}

/**
 * EmptyStateGuide component
 */
export const EmptyStateGuide: React.FC<EmptyStateGuideProps> = ({
  mode = 'grid',
  compact = false,
  onAddComponent,
  onUseTemplate,
  onImport,
}) => {
  const { locale } = useI18n();
  const modeInfo = PAGE_MODE_INFO[mode];

  const quickStartItems: QuickStartItem[] = [
    {
      id: 'drag',
      icon: (
        <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M7 11.5V14m0-2.5v-6a1.5 1.5 0 113 0m-3 6a1.5 1.5 0 00-3 0v2a7.5 7.5 0 0015 0v-5a1.5 1.5 0 00-3 0m-6-3V11m0-5.5v-1a1.5 1.5 0 013 0v1m0 0V11m0-5.5a1.5 1.5 0 013 0v3m0 0V11"
          />
        </svg>
      ),
      title: resolveDesignerText(DESIGNER_I18N.studio.dragComponent, locale),
      description: resolveDesignerText(DESIGNER_I18N.studio.dragComponentDesc, locale),
      action: () => onAddComponent?.(),
    },
    {
      id: 'template',
      icon: (
        <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z"
          />
        </svg>
      ),
      title: resolveDesignerText(DESIGNER_I18N.studio.useTemplate, locale),
      description: resolveDesignerText(DESIGNER_I18N.studio.useTemplateDesc, locale),
      action: () => onUseTemplate?.(),
    },
    {
      id: 'import',
      icon: (
        <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
          />
        </svg>
      ),
      title: resolveDesignerText(DESIGNER_I18N.studio.importPage, locale),
      description: resolveDesignerText(DESIGNER_I18N.studio.importPageDesc, locale),
      action: () => onImport?.(),
    },
  ];

  const tips = [
    { key: 'shortcut', text: resolveDesignerText(DESIGNER_I18N.studio.tipSave, locale) },
    { key: 'preview', text: resolveDesignerText(DESIGNER_I18N.studio.tipPreview, locale) },
    { key: 'multiselect', text: resolveDesignerText(DESIGNER_I18N.studio.tipMultiselect, locale) },
  ];

  if (compact) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-center">
        <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-blue-50">
          <svg
            className="h-6 w-6 text-blue-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d={modeInfo.icon}
            />
          </svg>
        </div>
        <p className="mb-4 text-sm text-gray-500">{resolveDesignerText(DESIGNER_I18N.emptyState.startDesign, locale)}</p>
        <div className="flex items-center gap-2 text-xs text-gray-400">
          <span>Ctrl+S {resolveDesignerText(DESIGNER_I18N.studio.save, locale)}</span>
          <span>·</span>
          <span>Ctrl+Z {resolveDesignerText(DESIGNER_I18N.studio.undo, locale)}</span>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-[400px] flex-col items-center justify-center py-12">
      {/* Icon and title */}
      <div className="mb-8 text-center">
        <div className="mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-50 to-indigo-50 shadow-sm">
          <svg
            className="h-10 w-10 text-blue-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d={modeInfo.icon}
            />
          </svg>
        </div>
        <h2 className="mb-2 text-xl font-semibold text-gray-900">
          {resolveDesignerText(DESIGNER_I18N.studio.startDesignTitle, locale, { mode: modeInfo.label })}
        </h2>
        <p className="max-w-md text-gray-500">
          {resolveDesignerText(DESIGNER_I18N.studio.startDesignDesc, locale, { description: modeInfo.description })}
        </p>
      </div>

      {/* Quick start options */}
      <div className="mb-8 grid w-full max-w-2xl grid-cols-3 gap-4">
        {quickStartItems.map((item) => (
          <button
            key={item.id}
            onClick={item.action}
            className="group flex flex-col items-center rounded-xl border-2 border-dashed border-gray-200 bg-white p-6 transition-all hover:border-blue-300 hover:bg-blue-50/50"
          >
            <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-gray-100 text-gray-500 transition-colors group-hover:bg-blue-100 group-hover:text-blue-600">
              {item.icon}
            </div>
            <h3 className="mb-1 font-medium text-gray-900">{item.title}</h3>
            <p className="text-center text-xs text-gray-500">{item.description}</p>
          </button>
        ))}
      </div>

      {/* Tips */}
      <div className="w-full max-w-md rounded-xl bg-gray-50 p-4">
        <h4 className="mb-3 text-xs font-medium tracking-wider text-gray-600 uppercase">
          {resolveDesignerText(DESIGNER_I18N.studio.quickTips, locale)}
        </h4>
        <div className="space-y-2">
          {tips.map((tip) => (
            <div key={tip.key} className="flex items-center gap-2 text-sm text-gray-600">
              <svg
                className="h-4 w-4 flex-shrink-0 text-blue-500"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              <span>{tip.text}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Keyboard shortcut hint */}
      <div className="mt-6 flex items-center gap-4 text-xs text-gray-400">
        <span className="flex items-center gap-1">
          <kbd className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-500">?</kbd>
          {resolveDesignerText(DESIGNER_I18N.studio.viewShortcuts, locale)}
        </span>
        <span>·</span>
        <span className="flex items-center gap-1">
          <kbd className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-500">F11</kbd>
          {resolveDesignerText(DESIGNER_I18N.studio.fullscreenMode, locale)}
        </span>
      </div>
    </div>
  );
};

export default EmptyStateGuide;
