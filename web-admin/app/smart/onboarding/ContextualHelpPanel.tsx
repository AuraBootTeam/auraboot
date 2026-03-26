import React, { useState } from 'react';
import { cn } from '~/utils/cn';
import { useI18n } from '~/contexts/I18nContext';
import { ONBOARDING_KEYS } from './i18nKeys';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface HelpEntry {
  /** The DSL field or config key */
  field: string;
  /** Short description (i18n key) */
  descriptionKey: string;
  /** Example value */
  example?: string;
  /** Link to documentation */
  docUrl?: string;
}

export interface ContextualHelpPanelProps {
  /** List of help entries for the current context */
  entries: HelpEntry[];
  /** Optional title override (i18n key) */
  titleKey?: string;
  /** Optional className */
  className?: string;
}

// ---------------------------------------------------------------------------
// Default help entries for Command DSL fields
// ---------------------------------------------------------------------------

export const COMMAND_HELP_ENTRIES: HelpEntry[] = [
  {
    field: 'code',
    descriptionKey: 'help.command.code',
    example: 'create_order',
  },
  {
    field: 'name',
    descriptionKey: 'help.command.name',
    example: 'Create Order',
  },
  {
    field: 'type',
    descriptionKey: 'help.command.type',
    example: 'CREATE | UPDATE | DELETE | STATE_CHANGE',
  },
  {
    field: 'preconditions',
    descriptionKey: 'help.command.preconditions',
    example: '{ "status": ["draft"] }',
  },
  {
    field: 'sideEffects',
    descriptionKey: 'help.command.sideEffects',
    example: '[{ "type": "create", "targetModel": "audit_log" }]',
  },
  {
    field: 'executionConfig',
    descriptionKey: 'help.command.executionConfig',
    example: '{ "stages": { ... } }',
  },
  {
    field: 'bpmTrigger',
    descriptionKey: 'help.command.bpmTrigger',
    example: '{ "processKey": "approval_flow" }',
  },
];

export const COMMAND_HELP_EN: Record<string, string> = {
  'help.command.code': 'Unique identifier for the command. Use snake_case.',
  'help.command.name': 'Display name shown in the UI button or menu.',
  'help.command.type': 'CREATE, UPDATE, DELETE, or STATE_CHANGE.',
  'help.command.preconditions':
    'Conditions that must be true before execution (e.g. record status).',
  'help.command.sideEffects':
    'Additional actions triggered after the main command (e.g. create audit log, update parent).',
  'help.command.executionConfig':
    'Advanced: 20-stage pipeline configuration. Only modify if you need custom behavior.',
  'help.command.bpmTrigger':
    'Link this command to a BPM process for human-task approval workflows.',
};

export const COMMAND_HELP_ZH: Record<string, string> = {
  'help.command.code':
    '\u547d\u4ee4\u7684\u552f\u4e00\u6807\u8bc6\u7b26\uff0c\u4f7f\u7528\u4e0b\u5212\u7ebf\u547d\u540d\u3002',
  'help.command.name':
    '\u663e\u793a\u5728 UI \u6309\u94ae\u6216\u83dc\u5355\u4e2d\u7684\u540d\u79f0\u3002',
  'help.command.type': 'CREATE\u3001UPDATE\u3001DELETE \u6216 STATE_CHANGE\u3002',
  'help.command.preconditions':
    '\u6267\u884c\u524d\u5fc5\u987b\u6ee1\u8db3\u7684\u6761\u4ef6\uff08\u5982\u8bb0\u5f55\u72b6\u6001\uff09\u3002',
  'help.command.sideEffects':
    '\u4e3b\u547d\u4ee4\u6267\u884c\u540e\u89e6\u53d1\u7684\u9644\u52a0\u64cd\u4f5c\uff08\u5982\u521b\u5efa\u5ba1\u8ba1\u65e5\u5fd7\u3001\u66f4\u65b0\u7236\u8bb0\u5f55\uff09\u3002',
  'help.command.executionConfig':
    '\u9ad8\u7ea7\uff1a20 \u9636\u6bb5\u7ba1\u9053\u914d\u7f6e\u3002\u4ec5\u5728\u9700\u8981\u81ea\u5b9a\u4e49\u884c\u4e3a\u65f6\u4fee\u6539\u3002',
  'help.command.bpmTrigger':
    '\u5c06\u6b64\u547d\u4ee4\u94fe\u63a5\u5230 BPM \u6d41\u7a0b\u4ee5\u5b9e\u73b0\u4eba\u5de5\u5ba1\u6279\u3002',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function ContextualHelpPanel({
  entries,
  titleKey = ONBOARDING_KEYS.helpPanelTitle,
  className,
}: ContextualHelpPanelProps) {
  const { t } = useI18n();
  const [expandedField, setExpandedField] = useState<string | null>(null);

  return (
    <div
      className={cn('overflow-hidden rounded-lg border border-gray-200 bg-gray-50', className)}
      data-testid="contextual-help-panel"
    >
      {/* Header */}
      <div className="flex items-center gap-2 border-b border-gray-200 bg-white px-4 py-3">
        <svg
          className="h-4 w-4 text-blue-500"
          fill="none"
          viewBox="0 0 24 24"
          strokeWidth={2}
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M9.879 7.519c1.171-1.025 3.071-1.025 4.242 0 1.172 1.025 1.172 2.687 0 3.712-.203.179-.43.326-.67.442-.745.361-1.45.999-1.45 1.827v.75M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-9 5.25h.008v.008H12v-.008z"
          />
        </svg>
        <span className="text-sm font-semibold text-gray-700">{t(titleKey)}</span>
      </div>

      {/* Entries */}
      <div className="divide-y divide-gray-200">
        {entries.map((entry) => {
          const isExpanded = expandedField === entry.field;
          return (
            <div key={entry.field} className="px-4 py-2.5">
              <button
                type="button"
                onClick={() => setExpandedField(isExpanded ? null : entry.field)}
                className="flex w-full items-center justify-between text-left"
              >
                <span className="font-mono text-sm text-gray-800">{entry.field}</span>
                <svg
                  className={cn(
                    'h-3.5 w-3.5 text-gray-400 transition-transform',
                    isExpanded && 'rotate-180',
                  )}
                  fill="none"
                  viewBox="0 0 24 24"
                  strokeWidth={2}
                  stroke="currentColor"
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                </svg>
              </button>

              {isExpanded && (
                <div className="mt-2 space-y-1.5">
                  <p className="text-sm text-gray-600">{t(entry.descriptionKey)}</p>
                  {entry.example && (
                    <div className="flex items-start gap-1.5">
                      <span className="mt-0.5 shrink-0 text-xs text-gray-400">Example:</span>
                      <code className="rounded border border-gray-200 bg-white px-1.5 py-0.5 text-xs break-all text-gray-700">
                        {entry.example}
                      </code>
                    </div>
                  )}
                  {entry.docUrl && (
                    <a
                      href={entry.docUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800"
                    >
                      {t(ONBOARDING_KEYS.helpLearnMore)}
                      <svg
                        className="h-3 w-3"
                        fill="none"
                        viewBox="0 0 24 24"
                        strokeWidth={2}
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          d="M13.5 6H5.25A2.25 2.25 0 003 8.25v10.5A2.25 2.25 0 005.25 21h10.5A2.25 2.25 0 0018 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25"
                        />
                      </svg>
                    </a>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default ContextualHelpPanel;
