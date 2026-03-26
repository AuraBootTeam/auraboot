import React, { useState, useCallback } from 'react';
import { useViewModelSelector } from '~/studio/hooks/viewmodel/useViewModelSelector';
import { useViewModel } from '~/studio/hooks/viewmodel/useViewModel';
import { FORM_LAYOUT_PRESETS, getDefaultPreset } from '~/studio/domain/schema/layout-presets';
import { generateInitialHierarchy } from '~/studio/services/layout/preset-applicator';
import type { LayoutPreset } from '~/studio/domain/schema/layout-presets';
import type { TabContainerConfig } from '~/studio/domain/schema/layout-hierarchy';
import type { ViewModelMode } from '~/studio/domain/viewmodel/types';

interface NewPageWizardProps {
  onComplete: (result: NewPageWizardResult) => void;
  onCancel: () => void;
}

export interface NewPageWizardResult {
  viewModelCode?: string;
  preset: LayoutPreset;
  hierarchy: TabContainerConfig;
}

type WizardStep = 'viewmodel' | 'layout' | 'preview';

const MODE_LABELS: Record<ViewModelMode, string> = {
  inherit: 'Inherit',
  compose: 'Compose',
  free: 'Free',
};

/**
 * Three-step wizard for creating a new page:
 * 1. Select ViewModel (optional)
 * 2. Select layout preset
 * 3. Preview and confirm
 *
 * @since 3.2.0
 */
export const NewPageWizard: React.FC<NewPageWizardProps> = ({ onComplete, onCancel }) => {
  const [step, setStep] = useState<WizardStep>('viewmodel');
  const [selectedViewModelCode, setSelectedViewModelCode] = useState<string | null>(null);
  const [selectedPreset, setSelectedPreset] = useState<LayoutPreset>(getDefaultPreset());

  const { viewModels, loading: vmLoading } = useViewModelSelector();
  const { fields, loading: fieldsLoading } = useViewModel({
    viewModelCode: selectedViewModelCode ?? undefined,
  });

  const handleNext = useCallback(() => {
    if (step === 'viewmodel') setStep('layout');
    else if (step === 'layout') setStep('preview');
  }, [step]);

  const handleBack = useCallback(() => {
    if (step === 'layout') setStep('viewmodel');
    else if (step === 'preview') setStep('layout');
  }, [step]);

  const handleComplete = useCallback(() => {
    const hierarchy =
      fields.length > 0
        ? generateInitialHierarchy(fields, selectedPreset)
        : generateEmptyHierarchy(selectedPreset);

    onComplete({
      viewModelCode: selectedViewModelCode ?? undefined,
      preset: selectedPreset,
      hierarchy,
    });
  }, [fields, selectedPreset, selectedViewModelCode, onComplete]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="flex max-h-[80vh] w-[600px] flex-col rounded-lg bg-white shadow-xl">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">New Page</h2>
          <StepIndicator current={step} />
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-6 py-4">
          {step === 'viewmodel' && (
            <ViewModelStep
              viewModels={viewModels}
              loading={vmLoading}
              selected={selectedViewModelCode}
              onSelect={setSelectedViewModelCode}
            />
          )}
          {step === 'layout' && (
            <LayoutStep
              presets={FORM_LAYOUT_PRESETS}
              selected={selectedPreset}
              onSelect={setSelectedPreset}
            />
          )}
          {step === 'preview' && (
            <PreviewStep
              viewModelCode={selectedViewModelCode}
              preset={selectedPreset}
              fieldCount={fields.length}
              loading={fieldsLoading}
            />
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-between border-t border-gray-200 px-6 py-4">
          <button
            onClick={step === 'viewmodel' ? onCancel : handleBack}
            className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800"
          >
            {step === 'viewmodel' ? 'Cancel' : 'Back'}
          </button>
          <button
            onClick={step === 'preview' ? handleComplete : handleNext}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700"
          >
            {step === 'preview' ? 'Create' : 'Next'}
          </button>
        </div>
      </div>
    </div>
  );
};

const StepIndicator: React.FC<{ current: WizardStep }> = ({ current }) => {
  const steps: { key: WizardStep; label: string }[] = [
    { key: 'viewmodel', label: '1. ViewModel' },
    { key: 'layout', label: '2. Layout' },
    { key: 'preview', label: '3. Preview' },
  ];

  return (
    <div className="mt-2 flex items-center gap-4">
      {steps.map((s, i) => (
        <div key={s.key} className="flex items-center gap-2">
          <span
            className={`text-xs font-medium ${s.key === current ? 'text-blue-600' : 'text-gray-400'}`}
          >
            {s.label}
          </span>
          {i < steps.length - 1 && <span className="text-gray-300">&rarr;</span>}
        </div>
      ))}
    </div>
  );
};

const ViewModelStep: React.FC<{
  viewModels: { code: string; displayName?: string; mode: ViewModelMode }[];
  loading: boolean;
  selected: string | null;
  onSelect: (code: string | null) => void;
}> = ({ viewModels, loading, selected, onSelect }) => {
  if (loading) {
    return <div className="py-8 text-center text-gray-500">Loading...</div>;
  }

  return (
    <div>
      <p className="mb-4 text-sm text-gray-600">
        Select a ViewModel to populate the page with fields, or skip to create an empty page.
      </p>

      <div className="space-y-2">
        <button
          onClick={() => onSelect(null)}
          className={`w-full rounded-lg border p-3 text-left ${
            selected === null
              ? 'border-blue-500 bg-blue-50'
              : 'border-gray-200 hover:border-gray-300'
          }`}
        >
          <div className="text-sm font-medium">No ViewModel</div>
          <div className="text-xs text-gray-500">Start with an empty page</div>
        </button>

        {viewModels.map((vm) => (
          <button
            key={vm.code}
            onClick={() => onSelect(vm.code)}
            className={`w-full rounded-lg border p-3 text-left ${
              selected === vm.code
                ? 'border-blue-500 bg-blue-50'
                : 'border-gray-200 hover:border-gray-300'
            }`}
          >
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">{vm.displayName || vm.code}</span>
              <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
                {MODE_LABELS[vm.mode] || vm.mode}
              </span>
            </div>
            <div className="mt-0.5 text-xs text-gray-500">{vm.code}</div>
          </button>
        ))}
      </div>
    </div>
  );
};

const LayoutStep: React.FC<{
  presets: LayoutPreset[];
  selected: LayoutPreset;
  onSelect: (preset: LayoutPreset) => void;
}> = ({ presets, selected, onSelect }) => {
  return (
    <div>
      <p className="mb-4 text-sm text-gray-600">Choose a layout preset for the page form.</p>
      <div className="grid grid-cols-2 gap-3">
        {presets.map((preset) => (
          <button
            key={preset.code}
            onClick={() => onSelect(preset)}
            className={`rounded-lg border p-4 text-left ${
              selected.code === preset.code
                ? 'border-blue-500 bg-blue-50'
                : 'border-gray-200 hover:border-gray-300'
            }`}
          >
            <div className="mb-2 flex items-center gap-2">
              <ColumnPreview columns={preset.formLayout.columns} />
              <span className="text-sm font-medium">{preset.name}</span>
            </div>
            <div className="text-xs text-gray-500">{preset.description}</div>
          </button>
        ))}
      </div>
    </div>
  );
};

const PreviewStep: React.FC<{
  viewModelCode: string | null;
  preset: LayoutPreset;
  fieldCount: number;
  loading: boolean;
}> = ({ viewModelCode, preset, fieldCount, loading }) => {
  return (
    <div>
      <p className="mb-4 text-sm text-gray-600">Review your page configuration.</p>
      <div className="space-y-3">
        <div className="rounded-lg bg-gray-50 p-3">
          <div className="text-xs text-gray-500">ViewModel</div>
          <div className="text-sm font-medium">{viewModelCode || 'None'}</div>
        </div>
        <div className="rounded-lg bg-gray-50 p-3">
          <div className="text-xs text-gray-500">Layout</div>
          <div className="text-sm font-medium">{preset.name}</div>
        </div>
        <div className="rounded-lg bg-gray-50 p-3">
          <div className="text-xs text-gray-500">Fields</div>
          <div className="text-sm font-medium">
            {loading ? 'Loading...' : `${fieldCount} fields`}
          </div>
        </div>
      </div>

      {fieldCount > 0 && (
        <div className="mt-4 rounded-lg border border-gray-200 p-4">
          <div className="mb-2 text-xs text-gray-500">Preview</div>
          <div
            className="grid gap-2"
            style={{ gridTemplateColumns: `repeat(${preset.formLayout.columns}, 1fr)` }}
          >
            {Array.from({ length: Math.min(fieldCount, 8) }).map((_, i) => (
              <div key={i} className="h-8 rounded border border-gray-200 bg-gray-100" />
            ))}
          </div>
          {fieldCount > 8 && (
            <div className="mt-2 text-center text-xs text-gray-400">
              +{fieldCount - 8} more fields
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const ColumnPreview: React.FC<{ columns: number }> = ({ columns }) => {
  return (
    <div className="flex gap-0.5">
      {Array.from({ length: columns }).map((_, i) => (
        <div key={i} className="h-5 w-3 rounded-sm bg-blue-300" />
      ))}
    </div>
  );
};

function generateEmptyHierarchy(preset: LayoutPreset): TabContainerConfig {
  return {
    type: 'tab-container',
    tabs: [
      {
        id: 'tab-main',
        code: 'main',
        label: 'Main',
        floors: [
          {
            id: 'floor-main',
            code: 'main',
            title: 'Fields',
            collapsible: false,
            blocks: [
              {
                id: 'block-main',
                code: 'main',
                layout: {
                  type: 'grid',
                  columns: preset.formLayout.columns,
                  gap: preset.formLayout.fieldSpacing,
                },
                fields: [],
              },
            ],
          },
        ],
      },
    ],
  };
}
