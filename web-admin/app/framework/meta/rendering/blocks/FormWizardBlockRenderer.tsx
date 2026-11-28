/**
 * FormWizardBlockRenderer — multi-step form wizard
 *
 * Supports:
 * - Step navigation (next/previous)
 * - Per-step validation before advancing
 * - Final submit on last step
 * - Step progress indicator
 *
 * DSL config:
 * { "blockType": "form-wizard", "steps": [...] }
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import type { BlockConfig, LocalizedText } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useNativeFormWizard } from '../NativeFormWizardContext';

interface WizardStep {
  key: string;
  label: LocalizedText;
  blocks: BlockConfig[];
  description?: LocalizedText;
}

interface FormWizardBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const FormWizardBlockRenderer: React.FC<FormWizardBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const steps: WizardStep[] = (block as any).steps || [];
  const host = useNativeFormWizard();
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const text = (zh: string, en: string) => getLocalizedText({ 'zh-CN': zh, en }, locale, context.t);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
  const advancing = useRef(false);
  const currentStep = steps[currentStepIndex];
  const isFirst = currentStepIndex === 0;
  const isLast = currentStepIndex === steps.length - 1;
  const navigationDisabled = !host || !host.ready || host.submitting;
  const reportStep = host?.reportStep;

  useEffect(() => {
    advancing.current = false;
    reportStep?.(block.id, isLast && steps.length > 0);
  }, [block.id, currentStepIndex, isLast, reportStep, steps.length]);

  useEffect(() => () => reportStep?.(block.id, false), [block.id, reportStep]);

  const handleNext = useCallback(() => {
    if (isLast || navigationDisabled || advancing.current || !host || !currentStep) return;
    advancing.current = true;
    if (!host.validateStep(currentStep.blocks)) {
      advancing.current = false;
      return;
    }
    setCurrentStepIndex(currentStepIndex + 1);
  }, [isLast, navigationDisabled, host, currentStep, currentStepIndex]);

  const handlePrevious = useCallback(() => {
    if (!isFirst) {
      setCurrentStepIndex((prev) => prev - 1);
    }
  }, [isFirst]);

  const handleStepClick = useCallback(
    (index: number) => {
      // Allow clicking on completed or current steps
      if (index <= currentStepIndex) {
        setCurrentStepIndex(index);
      }
    },
    [currentStepIndex],
  );

  if (steps.length === 0) {
    return (
      <div className="bg-status-amber-bg border-status-amber rounded border p-4">
        <p role="alert" className="text-status-amber">
          {text('尚未配置表单步骤。', 'No form steps have been configured.')}
        </p>
      </div>
    );
  }

  if (!host || !block.id) {
    return (
      <p role="alert" className="text-status-amber">
        {text('此分步表单需要在表单页面中使用。', 'This wizard requires a form page.')}
      </p>
    );
  }

  return (
    <div className="space-y-6">
      {/* Step progress indicator */}
      <nav className="flex items-center justify-center" aria-label={text('表单步骤', 'Form steps')}>
        <ol className="flex flex-wrap items-center gap-2">
          {steps.map((step, index) => {
            const isCompleted = index < currentStepIndex;
            const isCurrent = index === currentStepIndex;

            return (
              <li key={step.key} className="flex items-center">
                {index > 0 && (
                  <div className={`mx-2 h-0.5 w-12 ${isCompleted ? 'bg-accent' : 'bg-border'}`} />
                )}
                <button
                  type="button"
                  onClick={() => handleStepClick(index)}
                  disabled={navigationDisabled || index > currentStepIndex}
                  aria-current={isCurrent ? 'step' : undefined}
                  className={`flex items-center gap-2 rounded-full px-3 py-2 text-sm font-medium transition-colors ${
                    isCurrent
                      ? 'bg-accent text-white'
                      : isCompleted
                        ? 'bg-accent-weak text-accent hover:bg-hover cursor-pointer'
                        : 'bg-subtle text-text-3 cursor-not-allowed'
                  }`}
                >
                  <span
                    className={`flex h-6 w-6 items-center justify-center rounded-full text-xs ${
                      isCurrent
                        ? 'bg-panel text-accent'
                        : isCompleted
                          ? 'bg-accent text-white'
                          : 'bg-hover text-text-3'
                    }`}
                  >
                    {isCompleted ? (
                      <svg className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                        <path
                          fillRule="evenodd"
                          d="M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z"
                          clipRule="evenodd"
                        />
                      </svg>
                    ) : (
                      index + 1
                    )}
                  </span>
                  <span>{getLocalizedText(step.label, locale, context.t)}</span>
                </button>
              </li>
            );
          })}
        </ol>
      </nav>

      {/* Step content */}
      {currentStep && (
        <div className="min-h-[200px]">
          {currentStep.description && (
            <p className="text-text-2 mb-4 text-sm">
              {getLocalizedText(currentStep.description, locale, context.t)}
            </p>
          )}
          <div className="space-y-4">{host.renderStep(currentStep.blocks)}</div>
        </div>
      )}

      {/* Navigation buttons */}
      <div className="border-border flex items-center justify-between border-t pt-4">
        <button
          type="button"
          onClick={handlePrevious}
          disabled={isFirst || navigationDisabled}
          className={`rounded-control px-4 py-2 text-sm font-medium transition-colors ${
            isFirst ? 'text-text-3 cursor-not-allowed' : 'bg-hover text-text-2 hover:bg-hover'
          }`}
        >
          {text('上一步', 'Previous')}
        </button>

        <span className="text-text-2 text-sm">
          {text(
            `第 ${currentStepIndex + 1} 步，共 ${steps.length} 步`,
            `Step ${currentStepIndex + 1} of ${steps.length}`,
          )}
        </span>

        {!isLast ? (
          <button
            type="button"
            onClick={handleNext}
            disabled={navigationDisabled}
            className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-sm font-medium text-white transition-colors"
          >
            {text('校验并继续', 'Next')}
          </button>
        ) : null}
      </div>
    </div>
  );
};
