import { ExclamationTriangleIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useState } from 'react';
import type { ConsistencyViolation } from '../../shared/services/consistencyRuleService';

interface ConsistencyViolationAlertProps {
  violations: ConsistencyViolation[];
  onDismiss?: () => void;
}

/**
 * Alert component that displays consistency rule violations.
 * Shows when a command/form submission fails due to cross-document constraints.
 */
export default function ConsistencyViolationAlert({
  violations,
  onDismiss,
}: ConsistencyViolationAlertProps) {
  const [dismissed, setDismissed] = useState(false);

  if (dismissed || !violations || violations.length === 0) {
    return null;
  }

  const handleDismiss = () => {
    setDismissed(true);
    onDismiss?.();
  };

  const getSeverityStyles = (severity: string) => {
    switch (severity) {
      case 'error':
        return {
          bg: 'bg-red-50 dark:bg-red-900/20',
          border: 'border-red-400 dark:border-red-600',
          icon: 'text-red-400 dark:text-red-500',
          title: 'text-red-800 dark:text-red-300',
          text: 'text-red-700 dark:text-red-400',
        };
      case 'warning':
        return {
          bg: 'bg-yellow-50 dark:bg-yellow-900/20',
          border: 'border-yellow-400 dark:border-yellow-600',
          icon: 'text-yellow-400 dark:text-yellow-500',
          title: 'text-yellow-800 dark:text-yellow-300',
          text: 'text-yellow-700 dark:text-yellow-400',
        };
      default:
        return {
          bg: 'bg-blue-50 dark:bg-blue-900/20',
          border: 'border-blue-400 dark:border-blue-600',
          icon: 'text-blue-400 dark:text-blue-500',
          title: 'text-blue-800 dark:text-blue-300',
          text: 'text-blue-700 dark:text-blue-400',
        };
    }
  };

  // Group by severity
  const errorViolations = violations.filter((v) => v.severity === 'error');
  const warningViolations = violations.filter((v) => v.severity === 'warning');
  const infoViolations = violations.filter(
    (v) => v.severity !== 'error' && v.severity !== 'warning',
  );

  const renderViolationGroup = (items: ConsistencyViolation[]) => {
    if (items.length === 0) return null;
    const styles = getSeverityStyles(items[0].severity);

    return (
      <div className={`rounded-md ${styles.bg} border ${styles.border} mb-3 p-4`}>
        <div className="flex">
          <div className="flex-shrink-0">
            <ExclamationTriangleIcon className={`h-5 w-5 ${styles.icon}`} aria-hidden="true" />
          </div>
          <div className="ml-3 flex-1">
            <h3 className={`text-sm font-medium ${styles.title}`}>
              {items[0].severity === 'error'
                ? 'Consistency Errors'
                : items[0].severity === 'warning'
                  ? 'Consistency Warnings'
                  : 'Consistency Info'}
            </h3>
            <div className={`mt-2 text-sm ${styles.text}`}>
              <ul className="list-disc space-y-1 pl-5">
                {items.map((violation, idx) => (
                  <li key={idx}>
                    <span className="font-medium">{violation.ruleName || violation.ruleCode}:</span>{' '}
                    {violation.message}
                    {violation.sourceAggregatedValue !== undefined &&
                      violation.targetValue !== undefined && (
                        <span className="ml-1 text-xs opacity-75">
                          (current: {violation.sourceAggregatedValue}, limit:{' '}
                          {violation.targetValue})
                        </span>
                      )}
                  </li>
                ))}
              </ul>
            </div>
          </div>
          {onDismiss && (
            <div className="ml-auto pl-3">
              <button
                type="button"
                onClick={handleDismiss}
                className={`inline-flex rounded-md p-1.5 ${styles.text} hover:opacity-75 focus:outline-none`}
              >
                <XMarkIcon className="h-5 w-5" aria-hidden="true" />
              </button>
            </div>
          )}
        </div>
      </div>
    );
  };

  return (
    <div className="consistency-violation-alert">
      {renderViolationGroup(errorViolations)}
      {renderViolationGroup(warningViolations)}
      {renderViolationGroup(infoViolations)}
    </div>
  );
}
