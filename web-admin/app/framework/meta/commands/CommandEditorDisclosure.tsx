import React, { useState, useCallback } from 'react';
import { cn } from '~/utils/cn';
import { useI18n } from '~/contexts/I18nContext';
import { ONBOARDING_KEYS } from '~/framework/smart/onboarding/i18nKeys';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type DisclosureLevel = 'basic' | 'advanced' | 'expert';

interface DisclosureSection {
  id: DisclosureLevel;
  labelKey: string;
  descriptionKey: string;
  icon: React.ReactNode;
  badgeColor: string;
}

export interface CommandEditorDisclosureProps {
  /** Content to render inside the Basic section */
  basicContent: React.ReactNode;
  /** Content to render inside the Advanced section */
  advancedContent: React.ReactNode;
  /** Content to render inside the Expert section */
  expertContent: React.ReactNode;
  /** Optionally control which sections start expanded */
  defaultExpanded?: DisclosureLevel[];
  /** Optional className */
  className?: string;
}

// ---------------------------------------------------------------------------
// Section definitions
// ---------------------------------------------------------------------------

const sections: DisclosureSection[] = [
  {
    id: 'basic',
    labelKey: ONBOARDING_KEYS.disclosureBasic,
    descriptionKey: ONBOARDING_KEYS.disclosureBasicDesc,
    icon: (
      <svg
        className="h-4 w-4"
        fill="none"
        viewBox="0 0 24 24"
        strokeWidth={2}
        stroke="currentColor"
      >
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
      </svg>
    ),
    badgeColor: 'bg-green-100 text-green-700',
  },
  {
    id: 'advanced',
    labelKey: ONBOARDING_KEYS.disclosureAdvanced,
    descriptionKey: ONBOARDING_KEYS.disclosureAdvancedDesc,
    icon: (
      <svg
        className="h-4 w-4"
        fill="none"
        viewBox="0 0 24 24"
        strokeWidth={2}
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
        />
        <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
      </svg>
    ),
    badgeColor: 'bg-amber-100 text-amber-700',
  },
  {
    id: 'expert',
    labelKey: ONBOARDING_KEYS.disclosureExpert,
    descriptionKey: ONBOARDING_KEYS.disclosureExpertDesc,
    icon: (
      <svg
        className="h-4 w-4"
        fill="none"
        viewBox="0 0 24 24"
        strokeWidth={2}
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"
        />
      </svg>
    ),
    badgeColor: 'bg-red-100 text-red-700',
  },
];

// ---------------------------------------------------------------------------
// Tooltip
// ---------------------------------------------------------------------------

function HelpTooltip({ text }: { text: string }) {
  const [show, setShow] = useState(false);

  return (
    <span className="relative ml-1.5 inline-block">
      <button
        type="button"
        className="flex h-4 w-4 items-center justify-center rounded-full bg-gray-200 text-xs text-gray-500 transition-colors hover:bg-gray-300"
        onMouseEnter={() => setShow(true)}
        onMouseLeave={() => setShow(false)}
        onFocus={() => setShow(true)}
        onBlur={() => setShow(false)}
        aria-label="Help"
      >
        ?
      </button>
      {show && (
        <div className="pointer-events-none absolute bottom-full left-1/2 z-20 mb-2 w-56 -translate-x-1/2 rounded-lg bg-gray-900 p-2 text-xs text-white shadow-lg">
          {text}
          <div className="absolute top-full left-1/2 -mt-1 -translate-x-1/2 border-4 border-transparent border-t-gray-900" />
        </div>
      )}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export function CommandEditorDisclosure({
  basicContent,
  advancedContent,
  expertContent,
  defaultExpanded = ['basic'],
  className,
}: CommandEditorDisclosureProps) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState<Set<DisclosureLevel>>(() => new Set(defaultExpanded));

  const toggle = useCallback((level: DisclosureLevel) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(level)) {
        // Basic is always visible
        if (level !== 'basic') {
          next.delete(level);
        }
      } else {
        next.add(level);
      }
      return next;
    });
  }, []);

  const contentMap: Record<DisclosureLevel, React.ReactNode> = {
    basic: basicContent,
    advanced: advancedContent,
    expert: expertContent,
  };

  return (
    <div className={cn('space-y-3', className)} data-testid="command-editor-disclosure">
      {sections.map((section) => {
        const isOpen = expanded.has(section.id);

        return (
          <div
            key={section.id}
            className="overflow-hidden rounded-lg border border-gray-200"
            data-testid={`disclosure-section-${section.id}`}
          >
            {/* Header */}
            <button
              type="button"
              onClick={() => toggle(section.id)}
              className={cn(
                'flex w-full items-center justify-between px-4 py-3 text-left transition-colors',
                isOpen ? 'bg-gray-50' : 'bg-white hover:bg-gray-50',
              )}
              aria-expanded={isOpen}
            >
              <div className="flex items-center gap-2">
                <span
                  className={cn(
                    'flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold',
                    section.badgeColor,
                  )}
                >
                  {section.icon}
                  {t(section.labelKey)}
                </span>
                <HelpTooltip text={t(section.descriptionKey)} />
              </div>

              {/* Chevron */}
              <svg
                className={cn(
                  'h-4 w-4 text-gray-400 transition-transform duration-200',
                  isOpen && 'rotate-180',
                )}
                fill="none"
                viewBox="0 0 24 24"
                strokeWidth={2}
                stroke="currentColor"
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
              </svg>
            </button>

            {/* Content */}
            {isOpen && (
              <div className="border-t border-gray-100 px-4 py-4">{contentMap[section.id]}</div>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default CommandEditorDisclosure;
