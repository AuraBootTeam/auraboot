/**
 * ApprovalOpinionSection — Fixed UI block for approval decision + comment.
 *
 * Rendered outside the DSL form, below the business data section.
 * Contains a decision dropdown (approve/reject/return) and a comment textarea.
 */

import React from 'react';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ApprovalOpinionSectionProps {
  decision: string;
  comment: string;
  onDecisionChange: (v: string) => void;
  onCommentChange: (v: string) => void;
  disabled?: boolean;
}

// ---------------------------------------------------------------------------
// Decision options
// ---------------------------------------------------------------------------

const DECISION_OPTIONS = [
  { value: 'approve', label: 'Approve' },   // TODO: i18n
  { value: 'reject', label: 'Reject' },     // TODO: i18n
  { value: 'return', label: 'Return' },     // TODO: i18n
] as const;

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function ApprovalOpinionSection({
  decision,
  comment,
  onDecisionChange,
  onCommentChange,
  disabled = false,
}: ApprovalOpinionSectionProps) {
  return (
    <div
      className="space-y-4 rounded-lg border border-gray-200 bg-gray-50 p-4"
      data-testid="approval-opinion-section"
    >
      {/* Section title */}
      <h4 className="text-sm font-medium text-gray-700">
        {/* TODO: i18n */}
        Approval Opinion
      </h4>

      {/* Decision dropdown */}
      <div>
        <label
          htmlFor="approval-decision"
          className="mb-1 block text-sm font-medium text-gray-600"
        >
          {/* TODO: i18n */}
          Decision
        </label>
        <select
          id="approval-decision"
          value={decision}
          onChange={(e) => onDecisionChange(e.target.value)}
          disabled={disabled}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm
            focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none
            disabled:cursor-not-allowed disabled:bg-gray-100 disabled:text-gray-400"
          data-testid="approval-decision-select"
        >
          {DECISION_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>

      {/* Comment textarea */}
      <div>
        <label
          htmlFor="approval-comment"
          className="mb-1 block text-sm font-medium text-gray-600"
        >
          {/* TODO: i18n */}
          Comment
        </label>
        <textarea
          id="approval-comment"
          value={comment}
          onChange={(e) => onCommentChange(e.target.value)}
          disabled={disabled}
          placeholder="Enter your comment..." // TODO: i18n
          rows={3}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm
            focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none
            disabled:cursor-not-allowed disabled:bg-gray-100 disabled:text-gray-400"
          data-testid="approval-comment-textarea"
        />
      </div>
    </div>
  );
}

export default ApprovalOpinionSection;
