import React, { useCallback } from 'react';

export interface ValidationError {
  field: string;
  message: string;
}

interface ValidationSummaryProps {
  errors: ValidationError[];
  className?: string;
}

export const ValidationSummary: React.FC<ValidationSummaryProps> = ({ errors, className = '' }) => {
  const scrollToField = useCallback((fieldName: string) => {
    // Try data-testid first, then name attribute, then id
    const selectors = [
      `[data-testid="field-${fieldName}"]`,
      `[name="${fieldName}"]`,
      `#field-${fieldName}`,
    ];

    for (const selector of selectors) {
      const element = document.querySelector(selector);
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'center' });
        // Focus the input if possible
        const input = element.querySelector('input, textarea, select') as HTMLElement;
        if (input) {
          setTimeout(() => input.focus(), 300);
        }
        break;
      }
    }
  }, []);

  if (errors.length === 0) return null;

  return (
    <div className={`rounded-lg border border-red-200 bg-red-50 p-4 ${className}`}>
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex-shrink-0 text-red-500">
          <svg className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
            <path
              fillRule="evenodd"
              d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a.75.75 0 000 1.5h.253a.25.25 0 01.244.304l-.459 2.066A1.75 1.75 0 0010.747 15H11a.75.75 0 000-1.5h-.253a.25.25 0 01-.244-.304l.459-2.066A1.75 1.75 0 009.253 9H9z"
              clipRule="evenodd"
            />
          </svg>
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-medium text-red-800">
            {errors.length} validation {errors.length === 1 ? 'error' : 'errors'}
          </h3>
          <ul className="mt-2 space-y-1">
            {errors.map((err, idx) => (
              <li key={idx}>
                <button
                  type="button"
                  onClick={() => scrollToField(err.field)}
                  className="text-left text-sm text-red-700 hover:text-red-900 hover:underline"
                >
                  <span className="font-medium">{err.field}</span>
                  <span className="mx-1">—</span>
                  <span>{err.message}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
};
