import React, { useState, useCallback, useMemo } from 'react';
import { cn } from '~/utils/cn';
import { useI18n } from '~/contexts/I18nContext';
import { ONBOARDING_KEYS } from './i18nKeys';
import { fieldPresetGroups, type FieldPreset } from '~/framework/meta/fields/fieldPresets';
import { commandTemplates, type CommandTemplate } from '~/framework/meta/commands/commandTemplates';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type ModelCategory = 'document' | 'master' | 'lookup';

interface WizardState {
  modelName: string;
  modelCategory: ModelCategory;
  selectedFields: FieldPreset[];
  selectedTemplate: CommandTemplate | null;
}

export interface OnboardingWizardProps {
  /** Called when the wizard completes or is skipped */
  onComplete: () => void;
}

const STORAGE_KEY = 'auraboot_onboarding_complete';
const TOTAL_STEPS = 6;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function isOnboardingComplete(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

function markOnboardingComplete() {
  try {
    localStorage.setItem(STORAGE_KEY, 'true');
  } catch {
    // noop – storage unavailable
  }
}

// ---------------------------------------------------------------------------
// Step components
// ---------------------------------------------------------------------------

function StepWelcome({ t }: { t: (k: string, p?: Record<string, any>) => string }) {
  const concepts = [
    {
      key: ONBOARDING_KEYS.conceptModel,
      descKey: ONBOARDING_KEYS.conceptModelDesc,
      color: 'bg-blue-100 text-blue-700',
    },
    {
      key: ONBOARDING_KEYS.conceptField,
      descKey: ONBOARDING_KEYS.conceptFieldDesc,
      color: 'bg-green-100 text-green-700',
    },
    {
      key: ONBOARDING_KEYS.conceptCommand,
      descKey: ONBOARDING_KEYS.conceptCommandDesc,
      color: 'bg-purple-100 text-purple-700',
    },
    {
      key: ONBOARDING_KEYS.conceptPage,
      descKey: ONBOARDING_KEYS.conceptPageDesc,
      color: 'bg-amber-100 text-amber-700',
    },
  ];

  return (
    <div className="space-y-6 text-center">
      <div>
        <h2 className="text-2xl font-bold text-gray-900" data-testid="onboarding-welcome-title">
          {t(ONBOARDING_KEYS.welcomeTitle)}
        </h2>
        <p className="mt-1 text-lg text-gray-500">{t(ONBOARDING_KEYS.welcomeSubtitle)}</p>
      </div>
      <p className="mx-auto max-w-lg text-gray-600">{t(ONBOARDING_KEYS.welcomeDesc)}</p>

      <div className="mx-auto grid max-w-xl grid-cols-1 gap-4 sm:grid-cols-2">
        {concepts.map((c) => (
          <div key={c.key} className="rounded-lg border border-gray-200 p-4 text-left">
            <span
              className={cn(
                'mb-2 inline-block rounded-full px-2 py-0.5 text-xs font-semibold',
                c.color,
              )}
            >
              {t(c.key)}
            </span>
            <p className="text-sm text-gray-600">{t(c.descKey)}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function StepCreateModel({
  t,
  state,
  onChange,
}: {
  t: (k: string, p?: Record<string, any>) => string;
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
}) {
  const categories: { value: ModelCategory; nameKey: string; descKey: string }[] = [
    {
      value: 'document',
      nameKey: ONBOARDING_KEYS.categoryDocument,
      descKey: ONBOARDING_KEYS.categoryDocumentDesc,
    },
    {
      value: 'master',
      nameKey: ONBOARDING_KEYS.categoryMaster,
      descKey: ONBOARDING_KEYS.categoryMasterDesc,
    },
    {
      value: 'lookup',
      nameKey: ONBOARDING_KEYS.categoryLookup,
      descKey: ONBOARDING_KEYS.categoryLookupDesc,
    },
  ];

  return (
    <div className="mx-auto max-w-lg space-y-6">
      <div className="text-center">
        <h2 className="text-xl font-bold text-gray-900">{t(ONBOARDING_KEYS.createModelTitle)}</h2>
        <p className="mt-1 text-gray-500">{t(ONBOARDING_KEYS.createModelDesc)}</p>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {t(ONBOARDING_KEYS.modelNameLabel)}
        </label>
        <input
          type="text"
          value={state.modelName}
          onChange={(e) => onChange({ modelName: e.target.value })}
          placeholder={t(ONBOARDING_KEYS.modelNamePlaceholder)}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          data-testid="onboarding-model-name"
        />
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">
          {t(ONBOARDING_KEYS.modelCategoryLabel)}
        </label>
        <div className="space-y-2">
          {categories.map((cat) => (
            <label
              key={cat.value}
              className={cn(
                'flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors',
                state.modelCategory === cat.value
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-200 hover:border-gray-300',
              )}
            >
              <input
                type="radio"
                name="modelCategory"
                value={cat.value}
                checked={state.modelCategory === cat.value}
                onChange={() => onChange({ modelCategory: cat.value })}
                className="mt-1"
              />
              <div>
                <span className="font-medium text-gray-900">{t(cat.nameKey)}</span>
                <p className="text-sm text-gray-500">{t(cat.descKey)}</p>
              </div>
            </label>
          ))}
        </div>
      </div>
    </div>
  );
}

function StepAddFields({
  t,
  state,
  onChange,
}: {
  t: (k: string, p?: Record<string, any>) => string;
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
}) {
  const addGroup = useCallback(
    (groupId: string) => {
      const group = fieldPresetGroups.find((g) => g.id === groupId);
      if (!group) return;
      const existingCodes = new Set(state.selectedFields.map((f) => f.code));
      const newFields = group.fields.filter((f) => !existingCodes.has(f.code));
      onChange({ selectedFields: [...state.selectedFields, ...newFields] });
    },
    [state.selectedFields, onChange],
  );

  const removeField = useCallback(
    (code: string) => {
      onChange({ selectedFields: state.selectedFields.filter((f) => f.code !== code) });
    },
    [state.selectedFields, onChange],
  );

  return (
    <div className="space-y-6">
      <div className="text-center">
        <h2 className="text-xl font-bold text-gray-900">{t(ONBOARDING_KEYS.addFieldsTitle)}</h2>
        <p className="mt-1 text-gray-500">{t(ONBOARDING_KEYS.addFieldsDesc)}</p>
      </div>

      {/* Preset groups */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {fieldPresetGroups.map((group) => (
          <button
            key={group.id}
            onClick={() => addGroup(group.id)}
            className="flex flex-col items-center gap-1 rounded-lg border border-gray-200 p-3 transition-colors hover:border-blue-300 hover:bg-blue-50"
            data-testid={`field-group-${group.id}`}
          >
            <span className="text-lg">{group.icon}</span>
            <span className="text-sm font-medium text-gray-700">{t(group.nameKey)}</span>
            <span className="text-xs text-gray-400">{group.fields.length} fields</span>
            <span className="mt-1 text-xs font-medium text-blue-600">
              {t(ONBOARDING_KEYS.addGroup)}
            </span>
          </button>
        ))}
      </div>

      {/* Selected fields */}
      {state.selectedFields.length > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-medium text-gray-700">
            {t(ONBOARDING_KEYS.selectedFields)} ({state.selectedFields.length})
          </h3>
          <div className="flex flex-wrap gap-2">
            {state.selectedFields.map((field) => (
              <span
                key={field.code}
                className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2.5 py-1 text-sm text-gray-700"
              >
                <span className="font-mono text-xs">{field.code}</span>
                <span className="text-gray-400">({field.type})</span>
                <button
                  onClick={() => removeField(field.code)}
                  className="ml-1 text-gray-400 hover:text-red-500"
                  aria-label={t(ONBOARDING_KEYS.removeField)}
                >
                  &times;
                </button>
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function StepConfigureCommands({
  t,
  state,
  onChange,
}: {
  t: (k: string, p?: Record<string, any>) => string;
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
}) {
  const applicableTemplates = useMemo(
    () => commandTemplates.filter((tpl) => tpl.applicableTo.includes(state.modelCategory)),
    [state.modelCategory],
  );

  return (
    <div className="space-y-6">
      <div className="text-center">
        <h2 className="text-xl font-bold text-gray-900">{t(ONBOARDING_KEYS.configCommandTitle)}</h2>
        <p className="mt-1 text-gray-500">{t(ONBOARDING_KEYS.configCommandDesc)}</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {applicableTemplates.map((tpl) => (
          <button
            key={tpl.id}
            onClick={() => onChange({ selectedTemplate: tpl })}
            className={cn(
              'rounded-lg border p-4 text-left transition-colors',
              state.selectedTemplate?.id === tpl.id
                ? 'border-blue-500 bg-blue-50 ring-2 ring-blue-200'
                : 'border-gray-200 hover:border-gray-300',
            )}
            data-testid={`command-template-${tpl.id}`}
          >
            <h3 className="font-medium text-gray-900">{t(tpl.nameKey)}</h3>
            <p className="mt-1 text-sm text-gray-500">{t(tpl.descriptionKey)}</p>
            <div className="mt-2 flex flex-wrap gap-1">
              {tpl.commands.map((cmd) => (
                <span
                  key={cmd.code}
                  className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600"
                >
                  {cmd.name}
                </span>
              ))}
            </div>
            {tpl.statuses && (
              <div className="mt-2 flex items-center gap-1">
                {tpl.statuses.map((s, i) => (
                  <React.Fragment key={s}>
                    <span className="rounded bg-purple-50 px-1.5 py-0.5 text-xs text-purple-600">
                      {s}
                    </span>
                    {i < tpl.statuses!.length - 1 && (
                      <svg
                        className="h-3 w-3 text-gray-400"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                        strokeWidth={2}
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                      </svg>
                    )}
                  </React.Fragment>
                ))}
              </div>
            )}
          </button>
        ))}
      </div>
    </div>
  );
}

function StepPreview({
  t,
  state,
}: {
  t: (k: string, p?: Record<string, any>) => string;
  state: WizardState;
}) {
  return (
    <div className="mx-auto max-w-lg space-y-6">
      <div className="text-center">
        <h2 className="text-xl font-bold text-gray-900">{t(ONBOARDING_KEYS.previewTitle)}</h2>
        <p className="mt-1 text-gray-500">{t(ONBOARDING_KEYS.previewDesc)}</p>
      </div>

      {/* Model */}
      <div className="rounded-lg border border-gray-200 p-4">
        <h3 className="mb-2 text-sm font-semibold tracking-wide text-gray-500 uppercase">
          {t(ONBOARDING_KEYS.previewModel)}
        </h3>
        <p className="text-lg font-medium text-gray-900">{state.modelName || '—'}</p>
        <span className="mt-1 inline-block rounded-full bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
          {state.modelCategory}
        </span>
      </div>

      {/* Fields */}
      <div className="rounded-lg border border-gray-200 p-4">
        <h3 className="mb-2 text-sm font-semibold tracking-wide text-gray-500 uppercase">
          {t(ONBOARDING_KEYS.previewFields)} ({state.selectedFields.length})
        </h3>
        {state.selectedFields.length > 0 ? (
          <div className="space-y-1">
            {state.selectedFields.map((f) => (
              <div key={f.code} className="flex items-center justify-between py-1">
                <span className="font-mono text-sm text-gray-700">{f.code}</span>
                <span className="text-xs text-gray-400">{f.type}</span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-gray-400">—</p>
        )}
      </div>

      {/* Commands */}
      <div className="rounded-lg border border-gray-200 p-4">
        <h3 className="mb-2 text-sm font-semibold tracking-wide text-gray-500 uppercase">
          {t(ONBOARDING_KEYS.previewCommands)}
        </h3>
        {state.selectedTemplate ? (
          <div className="space-y-1">
            {state.selectedTemplate.commands.map((cmd) => (
              <div key={cmd.code} className="flex items-center justify-between py-1">
                <span className="text-sm text-gray-700">{cmd.name}</span>
                <span className="text-xs text-gray-400">{cmd.type}</span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-gray-400">—</p>
        )}
      </div>
    </div>
  );
}

function StepComplete({
  t,
  onComplete,
}: {
  t: (k: string, p?: Record<string, any>) => string;
  onComplete: () => void;
}) {
  return (
    <div className="space-y-6 text-center">
      <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-green-100">
        <svg
          className="h-8 w-8 text-green-600"
          fill="none"
          viewBox="0 0 24 24"
          strokeWidth={2}
          stroke="currentColor"
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
        </svg>
      </div>
      <div>
        <h2 className="text-2xl font-bold text-gray-900">{t(ONBOARDING_KEYS.completeTitle)}</h2>
        <p className="mx-auto mt-2 max-w-md text-gray-500">{t(ONBOARDING_KEYS.completeDesc)}</p>
      </div>
      <div className="flex flex-col justify-center gap-3 sm:flex-row">
        <button
          onClick={onComplete}
          className="rounded-lg bg-blue-600 px-6 py-2.5 font-medium text-white transition-colors hover:bg-blue-700"
          data-testid="onboarding-go-designer"
        >
          {t(ONBOARDING_KEYS.completeGoDesigner)}
        </button>
        <button
          onClick={onComplete}
          className="rounded-lg border border-gray-300 px-6 py-2.5 font-medium text-gray-700 transition-colors hover:bg-gray-50"
        >
          {t(ONBOARDING_KEYS.completeGoTemplates)}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Wizard Component
// ---------------------------------------------------------------------------

export function OnboardingWizard({ onComplete }: OnboardingWizardProps) {
  const { t } = useI18n();
  const [currentStep, setCurrentStep] = useState(1);
  const [state, setState] = useState<WizardState>({
    modelName: '',
    modelCategory: 'document',
    selectedFields: [],
    selectedTemplate: null,
  });

  const updateState = useCallback((patch: Partial<WizardState>) => {
    setState((prev) => ({ ...prev, ...patch }));
  }, []);

  const handleComplete = useCallback(() => {
    markOnboardingComplete();
    onComplete();
  }, [onComplete]);

  const handleSkip = useCallback(() => {
    markOnboardingComplete();
    onComplete();
  }, [onComplete]);

  const canProceed = useMemo(() => {
    switch (currentStep) {
      case 2:
        return state.modelName.trim().length > 0;
      default:
        return true;
    }
  }, [currentStep, state.modelName]);

  // Step renderer
  const renderStep = () => {
    switch (currentStep) {
      case 1:
        return <StepWelcome t={t} />;
      case 2:
        return <StepCreateModel t={t} state={state} onChange={updateState} />;
      case 3:
        return <StepAddFields t={t} state={state} onChange={updateState} />;
      case 4:
        return <StepConfigureCommands t={t} state={state} onChange={updateState} />;
      case 5:
        return <StepPreview t={t} state={state} />;
      case 6:
        return <StepComplete t={t} onComplete={handleComplete} />;
      default:
        return null;
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      data-testid="onboarding-wizard"
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" />

      {/* Wizard panel */}
      <div className="relative z-10 mx-4 flex max-h-[90vh] w-full max-w-2xl flex-col rounded-2xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600">
              <svg
                className="h-5 w-5 text-white"
                fill="none"
                viewBox="0 0 24 24"
                strokeWidth={2}
                stroke="currentColor"
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            </div>
            <span className="text-sm text-gray-500">
              {t(ONBOARDING_KEYS.stepOf, { current: currentStep, total: TOTAL_STEPS })}
            </span>
          </div>
          <button
            onClick={handleSkip}
            className="text-sm text-gray-400 transition-colors hover:text-gray-600"
            data-testid="onboarding-skip"
          >
            {t(ONBOARDING_KEYS.wizardSkip)}
          </button>
        </div>

        {/* Progress bar */}
        <div className="px-6 pt-3">
          <div className="h-1.5 overflow-hidden rounded-full bg-gray-100">
            <div
              className="h-full rounded-full bg-blue-600 transition-all duration-300"
              style={{ width: `${(currentStep / TOTAL_STEPS) * 100}%` }}
            />
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-6 py-6">{renderStep()}</div>

        {/* Footer */}
        {currentStep < TOTAL_STEPS && (
          <div className="flex items-center justify-between border-t border-gray-100 px-6 py-4">
            <button
              onClick={() => setCurrentStep((s) => Math.max(1, s - 1))}
              disabled={currentStep === 1}
              className={cn(
                'rounded-lg px-4 py-2 text-sm font-medium transition-colors',
                currentStep === 1
                  ? 'cursor-not-allowed text-gray-300'
                  : 'text-gray-700 hover:bg-gray-100',
              )}
            >
              {t(ONBOARDING_KEYS.wizardPrev)}
            </button>
            <button
              onClick={() => setCurrentStep((s) => Math.min(TOTAL_STEPS, s + 1))}
              disabled={!canProceed}
              className={cn(
                'rounded-lg px-6 py-2 text-sm font-medium transition-colors',
                canProceed
                  ? 'bg-blue-600 text-white hover:bg-blue-700'
                  : 'cursor-not-allowed bg-gray-200 text-gray-400',
              )}
              data-testid="onboarding-next"
            >
              {currentStep === TOTAL_STEPS - 1
                ? t(ONBOARDING_KEYS.wizardFinish)
                : t(ONBOARDING_KEYS.wizardNext)}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Hook to check whether onboarding should be shown.
 */
export function useOnboardingRequired(): boolean {
  return !isOnboardingComplete();
}

export default OnboardingWizard;
