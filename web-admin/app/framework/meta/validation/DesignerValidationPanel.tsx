/**
 * DesignerValidationPanel — Validation status panel for the Page Designer.
 *
 * Shows real-time validation errors/warnings as the designer edits DSL schemas.
 * Can be placed in the designer's bottom bar or side panel.
 */

import React, { useState, useMemo } from 'react';
import type { DslValidationResult, ValidationMessage, ValidationSeverity } from './types';

interface DesignerValidationPanelProps {
  result: DslValidationResult | null;
  isValidating?: boolean;
  /** Callback when user clicks a validation message (to navigate to the issue) */
  onMessageClick?: (message: ValidationMessage) => void;
  className?: string;
  /** Collapsed mode: only show summary badge */
  collapsed?: boolean;
}

const SEVERITY_COLORS: Record<ValidationSeverity, { bg: string; text: string; border: string }> = {
  error: { bg: 'bg-red-50', text: 'text-red-700', border: 'border-red-200' },
  warning: { bg: 'bg-amber-50', text: 'text-amber-700', border: 'border-amber-200' },
  info: { bg: 'bg-blue-50', text: 'text-blue-700', border: 'border-blue-200' },
};

function SeverityIcon({
  severity,
  className = 'h-4 w-4',
}: {
  severity: ValidationSeverity;
  className?: string;
}) {
  if (severity === 'error') {
    return (
      <svg className={className} viewBox="0 0 20 20" fill="currentColor">
        <path
          fillRule="evenodd"
          d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.28 7.22a.75.75 0 00-1.06 1.06L8.94 10l-1.72 1.72a.75.75 0 101.06 1.06L10 11.06l1.72 1.72a.75.75 0 101.06-1.06L11.06 10l1.72-1.72a.75.75 0 00-1.06-1.06L10 8.94 8.28 7.22z"
          clipRule="evenodd"
        />
      </svg>
    );
  }
  if (severity === 'warning') {
    return (
      <svg className={className} viewBox="0 0 20 20" fill="currentColor">
        <path
          fillRule="evenodd"
          d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z"
          clipRule="evenodd"
        />
      </svg>
    );
  }
  return (
    <svg className={className} viewBox="0 0 20 20" fill="currentColor">
      <path
        fillRule="evenodd"
        d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a.75.75 0 000 1.5h.253a.25.25 0 01.244.304l-.459 2.066A1.75 1.75 0 0010.747 15H11a.75.75 0 000-1.5h-.253a.25.25 0 01-.244-.304l.459-2.066A1.75 1.75 0 009.253 9H9z"
        clipRule="evenodd"
      />
    </svg>
  );
}

/**
 * Compact status badge for collapsed mode.
 */
function ValidationBadge({
  result,
  isValidating,
  onClick,
}: {
  result: DslValidationResult | null;
  isValidating?: boolean;
  onClick?: () => void;
}) {
  if (isValidating) {
    return (
      <button
        onClick={onClick}
        className="flex items-center gap-1.5 rounded px-2 py-1 text-xs text-gray-500 hover:bg-gray-100"
      >
        <span className="h-3 w-3 animate-spin rounded-full border-2 border-gray-300 border-t-gray-600" />
        Validating...
      </button>
    );
  }

  if (!result || result.summary.errors + result.summary.warnings === 0) {
    return (
      <button
        onClick={onClick}
        className="flex items-center gap-1.5 rounded px-2 py-1 text-xs text-green-600 hover:bg-green-50"
      >
        <svg className="h-3.5 w-3.5" viewBox="0 0 20 20" fill="currentColor">
          <path
            fillRule="evenodd"
            d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.857-9.809a.75.75 0 00-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 10-1.06 1.061l2.5 2.5a.75.75 0 001.137-.089l4-5.5z"
            clipRule="evenodd"
          />
        </svg>
        Valid
      </button>
    );
  }

  return (
    <button
      onClick={onClick}
      className="flex items-center gap-2 rounded px-2 py-1 text-xs hover:bg-gray-100"
    >
      {result.summary.errors > 0 && (
        <span className="flex items-center gap-0.5 text-red-600">
          <SeverityIcon severity="error" className="h-3.5 w-3.5" />
          {result.summary.errors}
        </span>
      )}
      {result.summary.warnings > 0 && (
        <span className="flex items-center gap-0.5 text-amber-600">
          <SeverityIcon severity="warning" className="h-3.5 w-3.5" />
          {result.summary.warnings}
        </span>
      )}
    </button>
  );
}

export const DesignerValidationPanel: React.FC<DesignerValidationPanelProps> = ({
  result,
  isValidating,
  onMessageClick,
  className = '',
  collapsed: initialCollapsed = true,
}) => {
  const [collapsed, setCollapsed] = useState(initialCollapsed);

  const groupedMessages = useMemo(() => {
    if (!result) return { error: [], warning: [], info: [] };
    return {
      error: result.messages.filter((m) => m.severity === 'error'),
      warning: result.messages.filter((m) => m.severity === 'warning'),
      info: result.messages.filter((m) => m.severity === 'info'),
    };
  }, [result]);

  if (collapsed) {
    return (
      <ValidationBadge
        result={result}
        isValidating={isValidating}
        onClick={() => setCollapsed(false)}
      />
    );
  }

  return (
    <div className={`rounded-lg border bg-white shadow-sm ${className}`}>
      {/* Header */}
      <div className="flex items-center justify-between rounded-t-lg border-b bg-gray-50 px-3 py-2">
        <div className="flex items-center gap-3">
          <span className="text-xs font-medium text-gray-700">DSL Validation</span>
          <ValidationBadge result={result} isValidating={isValidating} />
        </div>
        <button
          onClick={() => setCollapsed(true)}
          className="p-0.5 text-gray-400 hover:text-gray-600"
          title="Collapse"
        >
          <svg className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
            <path
              fillRule="evenodd"
              d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z"
              clipRule="evenodd"
            />
          </svg>
        </button>
      </div>

      {/* Message list */}
      <div className="max-h-48 overflow-y-auto">
        {result && result.messages.length === 0 && (
          <div className="px-3 py-4 text-center text-xs text-gray-400">No issues found</div>
        )}

        {(['error', 'warning', 'info'] as const).map((severity) => {
          const messages = groupedMessages[severity];
          if (messages.length === 0) return null;

          const colors = SEVERITY_COLORS[severity];
          return messages.map((msg, idx) => (
            <button
              key={`${severity}-${idx}`}
              onClick={() => onMessageClick?.(msg)}
              className={`w-full border-b px-3 py-1.5 text-left text-xs last:border-b-0 hover:${colors.bg} flex items-start gap-2 ${colors.text}`}
            >
              <SeverityIcon severity={severity} className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" />
              <div className="min-w-0">
                <span className="mr-1.5 font-mono text-[10px] text-gray-400">{msg.path}</span>
                <span>{msg.message}</span>
                {msg.suggestion && (
                  <span className="mt-0.5 block text-gray-400">{msg.suggestion}</span>
                )}
              </div>
            </button>
          ));
        })}
      </div>
    </div>
  );
};

export default DesignerValidationPanel;
