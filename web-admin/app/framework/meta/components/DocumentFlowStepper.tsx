import React, { useMemo, useCallback } from 'react';
import type { DocumentFlowStep, DocumentFlowStepperProps } from './DocumentFlowConfig';

/**
 * DocumentFlowStepper - Visual indicator showing a document's position
 * in a workflow chain (e.g., Quotation -> Order -> Shipment -> Collection).
 *
 * Features:
 * - Horizontal stepper with connected circles and lines
 * - Completed steps: green circle with checkmark
 * - Current step: blue circle with pulsing ring
 * - Upcoming steps: gray circle with dashed connector
 * - Skipped steps: gray circle with strikethrough
 * - Clickable steps (when recordId exists) for navigation
 * - Status badge under the current step
 * - Responsive: collapses to show only current + adjacent on small screens
 *
 * @since 3.8.0
 */
export const DocumentFlowStepper: React.FC<DocumentFlowStepperProps> = ({
  steps,
  currentModelCode,
  onStepClick,
  className = '',
}) => {
  const currentIndex = useMemo(
    () => steps.findIndex((s) => s.modelCode === currentModelCode),
    [steps, currentModelCode],
  );

  const handleStepClick = useCallback(
    (step: DocumentFlowStep) => {
      if (step.recordId && onStepClick) {
        onStepClick(step);
      }
    },
    [onStepClick],
  );

  if (steps.length === 0) return null;

  return (
    <div className={`document-flow-stepper ${className}`} data-testid="document-flow-stepper">
      {/* Full stepper — hidden on small screens, visible on md+ */}
      <div className="hidden items-start justify-center md:flex">
        <StepperRow steps={steps} currentIndex={currentIndex} onStepClick={handleStepClick} />
      </div>

      {/* Compact stepper — visible on small screens only */}
      <div className="flex items-start justify-center md:hidden">
        <CompactStepperRow
          steps={steps}
          currentIndex={currentIndex}
          onStepClick={handleStepClick}
        />
      </div>
    </div>
  );
};

// -- Internal components --

/**
 * Full stepper row rendering all steps with connectors.
 */
function StepperRow({
  steps,
  currentIndex,
  onStepClick,
}: {
  steps: DocumentFlowStep[];
  currentIndex: number;
  onStepClick: (step: DocumentFlowStep) => void;
}) {
  return (
    <div className="flex items-start">
      {steps.map((step, index) => (
        <React.Fragment key={step.modelCode}>
          <StepNode
            step={step}
            index={index}
            currentIndex={currentIndex}
            onStepClick={onStepClick}
          />
          {index < steps.length - 1 && (
            <StepConnector fromStatus={step.status} toStatus={steps[index + 1].status} />
          )}
        </React.Fragment>
      ))}
    </div>
  );
}

/**
 * Compact stepper showing only current step + immediate neighbors.
 */
function CompactStepperRow({
  steps,
  currentIndex,
  onStepClick,
}: {
  steps: DocumentFlowStep[];
  currentIndex: number;
  onStepClick: (step: DocumentFlowStep) => void;
}) {
  // Show at most: prev, current, next
  const visibleIndices: number[] = [];
  if (currentIndex > 0) visibleIndices.push(currentIndex - 1);
  visibleIndices.push(currentIndex);
  if (currentIndex < steps.length - 1) visibleIndices.push(currentIndex + 1);

  const visibleSteps = visibleIndices.map((i) => ({ step: steps[i], index: i }));

  // Show ellipsis indicators for hidden steps
  const hasHiddenBefore = currentIndex > 1;
  const hasHiddenAfter = currentIndex < steps.length - 2;

  return (
    <div className="flex items-start">
      {hasHiddenBefore && (
        <div className="mr-1 flex items-center self-center">
          <span className="text-xs text-gray-400">...</span>
        </div>
      )}
      {visibleSteps.map(({ step, index }, vIdx) => (
        <React.Fragment key={step.modelCode}>
          <StepNode
            step={step}
            index={index}
            currentIndex={currentIndex}
            onStepClick={onStepClick}
            compact
          />
          {vIdx < visibleSteps.length - 1 && (
            <StepConnector
              fromStatus={step.status}
              toStatus={visibleSteps[vIdx + 1].step.status}
              compact
            />
          )}
        </React.Fragment>
      ))}
      {hasHiddenAfter && (
        <div className="ml-1 flex items-center self-center">
          <span className="text-xs text-gray-400">...</span>
        </div>
      )}
    </div>
  );
}

/**
 * Individual step node: circle + label + optional status badge.
 */
function StepNode({
  step,
  index,
  currentIndex: _currentIndex,
  onStepClick,
  compact = false,
}: {
  step: DocumentFlowStep;
  index: number;
  currentIndex: number;
  onStepClick: (step: DocumentFlowStep) => void;
  compact?: boolean;
}) {
  const isClickable = !!step.recordId && step.status !== 'current';
  const isCurrent = step.status === 'current';
  const isCompleted = step.status === 'completed';
  const isSkipped = step.status === 'skipped';

  return (
    <div
      className={`flex flex-col items-center ${compact ? 'min-w-[60px]' : 'min-w-[72px]'}`}
      data-testid={`flow-step-${step.modelCode}`}
    >
      {/* Circle */}
      <button
        type="button"
        onClick={() => handleClick(step, isClickable, onStepClick)}
        disabled={!isClickable}
        className={`relative flex items-center justify-center rounded-full transition-all duration-200 ${
          isCurrent
            ? 'h-9 w-9 bg-blue-600 text-white shadow-md'
            : isCompleted
              ? 'h-7 w-7 bg-emerald-500 text-white'
              : isSkipped
                ? 'h-7 w-7 bg-gray-200 text-gray-400'
                : 'h-7 w-7 border-2 border-dashed border-gray-300 bg-gray-100 text-gray-400'
        } ${
          isClickable
            ? 'cursor-pointer hover:scale-110 hover:shadow-lg'
            : isCurrent
              ? 'cursor-default'
              : 'cursor-default'
        } focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-400 focus-visible:ring-offset-2`}
        aria-label={`${step.label}${isCurrent ? ' (current)' : ''}${isClickable ? ' - click to navigate' : ''}`}
        title={isClickable ? `Go to ${step.label}` : step.label}
      >
        {/* Pulsing ring for current step */}
        {isCurrent && (
          <span className="absolute inset-0 animate-ping rounded-full bg-blue-400 opacity-20" />
        )}

        {/* Icon content */}
        {isCompleted ? (
          <CheckIcon />
        ) : isCurrent ? (
          <CurrentDotIcon />
        ) : isSkipped ? (
          <SkipIcon />
        ) : (
          <span className="text-[10px] font-medium">{index + 1}</span>
        )}
      </button>

      {/* Label */}
      <span
        className={`mt-1.5 text-center text-xs leading-tight font-medium ${
          isCurrent
            ? 'text-blue-700'
            : isCompleted
              ? 'text-emerald-600'
              : isSkipped
                ? 'text-gray-400 line-through'
                : 'text-gray-500'
        } ${isClickable ? 'cursor-pointer hover:underline' : ''} `}
        onClick={() => handleClick(step, isClickable, onStepClick)}
      >
        {step.label}
      </span>

      {/* Status badge for current step */}
      {isCurrent && step.statusValue && (
        <span className="mt-1 inline-flex items-center rounded border border-blue-200 bg-blue-50 px-1.5 py-0.5 text-[10px] font-medium text-blue-700">
          {step.statusValue}
        </span>
      )}

      {/* Navigation hint for clickable completed/upcoming steps */}
      {isClickable && !isCurrent && (
        <span className="mt-0.5 text-[9px] text-gray-400">click to view</span>
      )}
    </div>
  );
}

function handleClick(
  step: DocumentFlowStep,
  isClickable: boolean,
  onStepClick: (step: DocumentFlowStep) => void,
) {
  if (isClickable) {
    onStepClick(step);
  }
}

/**
 * Connector line between two steps.
 */
function StepConnector({
  fromStatus,
  toStatus,
  compact = false,
}: {
  fromStatus: DocumentFlowStep['status'];
  toStatus: DocumentFlowStep['status'];
  compact?: boolean;
}) {
  // Line is solid green if both sides are completed or the left is completed and right is current
  const isCompleted =
    fromStatus === 'completed' && (toStatus === 'completed' || toStatus === 'current');

  const isHalfCompleted = fromStatus === 'current' && toStatus === 'upcoming';

  return (
    <div
      className={`flex items-center self-center ${compact ? 'mt-0.5' : 'mt-0.5'}`}
      style={{ marginTop: fromStatus === 'current' ? '18px' : '14px' }}
    >
      <div
        className={` ${compact ? 'w-6' : 'w-12'} h-0.5 ${
          isCompleted
            ? 'bg-emerald-400'
            : isHalfCompleted
              ? 'bg-gradient-to-r from-blue-400 to-gray-300'
              : 'border-t-2 border-dashed border-gray-300 bg-transparent'
        } `}
      />
    </div>
  );
}

// -- SVG Icons (inline to avoid external dependencies) --

function CheckIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
      strokeWidth={3}
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
    </svg>
  );
}

function CurrentDotIcon() {
  return (
    <svg className="h-3 w-3" fill="currentColor" viewBox="0 0 24 24">
      <circle cx="12" cy="12" r="5" />
    </svg>
  );
}

function SkipIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
      strokeWidth={2.5}
    >
      <path strokeLinecap="round" d="M6 12h12" />
    </svg>
  );
}
