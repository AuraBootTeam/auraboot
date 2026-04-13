/**
 * EmptyStatePrompt — Generic empty state component for pages with no data.
 *
 * Centered layout with optional icon, title, description, CTA button,
 * and "Take a tour" link that triggers the Product Tour.
 */

import { InboxIcon } from '@heroicons/react/24/outline';

interface EmptyStatePromptProps {
  /** Optional hero icon component or emoji string */
  icon?: React.ReactNode;
  /** Primary heading */
  title: string;
  /** Descriptive text */
  description: string;
  /** Optional CTA button label */
  actionLabel?: string;
  /** CTA button click handler */
  onAction?: () => void;
  /** Show "Take a tour" link (requires OnboardingProvider in tree) */
  showTour?: boolean;
  /** Tour start handler — injected by parent to avoid hard dep on OnboardingProvider */
  onStartTour?: () => void;
}

export function EmptyStatePrompt({
  icon,
  title,
  description,
  actionLabel,
  onAction,
  showTour,
  onStartTour,
}: EmptyStatePromptProps) {
  return (
    <div
      className="flex flex-col items-center justify-center px-6 py-16 text-center"
      data-testid="empty-state-prompt"
    >
      {/* Icon */}
      <div className="mb-4 text-gray-300 dark:text-gray-600">
        {icon ?? <InboxIcon className="h-16 w-16" />}
      </div>

      {/* Title */}
      <h3 className="mb-2 text-lg font-semibold text-gray-700 dark:text-gray-300">{title}</h3>

      {/* Description */}
      <p className="mb-6 max-w-md text-sm leading-relaxed text-gray-500 dark:text-gray-400">
        {description}
      </p>

      {/* Actions */}
      <div className="flex items-center gap-4">
        {actionLabel && onAction && (
          <button
            onClick={onAction}
            className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            data-testid="empty-state-action"
          >
            {actionLabel}
          </button>
        )}
        {showTour && onStartTour && (
          <button
            onClick={onStartTour}
            className="px-4 py-2 text-sm text-blue-600 transition-colors hover:text-blue-700 hover:underline dark:text-blue-400 dark:hover:text-blue-300"
            data-testid="empty-state-tour"
          >
            Take a tour
          </button>
        )}
      </div>
    </div>
  );
}
