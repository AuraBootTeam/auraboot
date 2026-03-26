/**
 * QuickStartWizard — 3-step modal wizard for choosing and installing
 * an application template.
 *
 * Step 1: Choose business type (template grid)
 * Step 2: Preview selected template details
 * Step 3: Install + navigate to the template's main page
 */

import { useState, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router';
import {
  XMarkIcon,
  CheckCircleIcon,
  ArrowLeftIcon,
  ArrowRightIcon,
} from '@heroicons/react/24/outline';
import { useOnboarding } from './OnboardingProvider';
import { post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

// ---------------------------------------------------------------------------
// Template definitions (subset of templateCatalog for wizard)
// ---------------------------------------------------------------------------

interface WizardTemplate {
  id: string;
  name: string;
  description: string;
  icon: string;
  features: string[];
  modelCount: number;
  pluginPath: string;
  color: string;
}

const WIZARD_TEMPLATES: WizardTemplate[] = [
  {
    id: 'crm-quick-start',
    name: 'crm',
    description: 'Manage leads, accounts, contacts, and sales opportunities.',
    icon: '\uD83E\uDD1D',
    features: ['Lead Management', 'Sales Pipeline', 'Contact Tracking', 'Opportunity Lifecycle'],
    modelCount: 4,
    pluginPath: 'plugins/templates/crm-quick-start',
    color: 'blue',
  },
  {
    id: 'project-management',
    name: 'Project Management',
    description: 'Track projects, tasks, and milestones for your team.',
    icon: '\uD83D\uDCCB',
    features: ['Project Tracking', 'Task Management', 'Milestones', 'Team Collaboration'],
    modelCount: 3,
    pluginPath: 'plugins/templates/project-management',
    color: 'indigo',
  },
  {
    id: 'asset-management',
    name: 'Asset Management',
    description: 'Track IT and office assets from purchase through retirement.',
    icon: '\uD83D\uDCE6',
    features: ['Asset Registry', 'Category Management', 'Maintenance Tracking'],
    modelCount: 3,
    pluginPath: 'plugins/templates/asset-management',
    color: 'amber',
  },
  {
    id: 'simple-inventory',
    name: 'Inventory',
    description: 'Simple buy/sell/stock management for small business.',
    icon: '\uD83D\uDCE6',
    features: ['Product Catalog', 'Warehouse Management', 'Stock Movements'],
    modelCount: 4,
    pluginPath: 'plugins/templates/simple-inventory',
    color: 'emerald',
  },
  {
    id: 'hr-essentials',
    name: 'HR Essentials',
    description: 'Employee records, attendance, and leave request workflows.',
    icon: '\uD83D\uDC65',
    features: ['Employee Records', 'Attendance Tracking', 'Leave Requests', 'Approval Workflow'],
    modelCount: 3,
    pluginPath: 'plugins/templates/hr-essentials',
    color: 'violet',
  },
];

// ---------------------------------------------------------------------------
// Color utilities
// ---------------------------------------------------------------------------

const BG_COLORS: Record<string, string> = {
  blue: 'bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800',
  indigo: 'bg-indigo-50 dark:bg-indigo-900/20 border-indigo-200 dark:border-indigo-800',
  amber: 'bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800',
  emerald: 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-200 dark:border-emerald-800',
  violet: 'bg-violet-50 dark:bg-violet-900/20 border-violet-200 dark:border-violet-800',
};

const RING_COLORS: Record<string, string> = {
  blue: 'ring-blue-500',
  indigo: 'ring-indigo-500',
  amber: 'ring-amber-500',
  emerald: 'ring-emerald-500',
  violet: 'ring-violet-500',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function QuickStartWizard() {
  const { state, closeWizard, setWizardStep, markComplete } = useOnboarding();
  const navigate = useNavigate();
  const [selectedTemplate, setSelectedTemplate] = useState<WizardTemplate | null>(null);
  const [installing, setInstalling] = useState(false);
  const [installSuccess, setInstallSuccess] = useState(false);
  const [installError, setInstallError] = useState<string | null>(null);

  const { wizardOpen, wizardStep } = state;

  // Reset state when wizard opens
  useEffect(() => {
    if (wizardOpen) {
      setSelectedTemplate(null);
      setInstalling(false);
      setInstallSuccess(false);
      setInstallError(null);
    }
  }, [wizardOpen]);

  // Escape key to close
  useEffect(() => {
    if (!wizardOpen) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeWizard();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [wizardOpen, closeWizard]);

  const handleInstall = useCallback(async () => {
    if (!selectedTemplate) return;
    setInstalling(true);
    setInstallError(null);
    try {
      const res = await post<{ success: boolean }>('/api/plugins/import/import-directory-sync', {
        path: selectedTemplate.pluginPath,
        conflictStrategy: 'overwrite',
        autoPublishModels: true,
        autoPublishPages: true,
      });
      if (ResultHelper.isSuccess(res)) {
        setInstallSuccess(true);
        markComplete('first_template_installed');
      } else {
        setInstallError('Installation failed. Please try again.');
      }
    } catch (err) {
      setInstallError(err instanceof Error ? err.message : 'Installation failed.');
    } finally {
      setInstalling(false);
    }
  }, [selectedTemplate, markComplete]);

  const handleGoToApp = useCallback(() => {
    closeWizard();
    // Navigate to dashboards as the common entry point after install
    navigate('/dashboards');
  }, [closeWizard, navigate]);

  if (!wizardOpen) return null;

  const modal = (
    <div
      className="fixed inset-0 z-[10000] flex items-center justify-center"
      data-testid="quick-start-wizard"
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={closeWizard} />

      {/* Modal */}
      <div className="relative mx-4 flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Quick Start</h2>
          <button
            onClick={closeWizard}
            className="rounded-lg p-1.5 transition-colors hover:bg-gray-100 dark:hover:bg-gray-700"
            aria-label="Close wizard"
          >
            <XMarkIcon className="h-5 w-5 text-gray-500" />
          </button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center justify-center gap-2 bg-gray-50 px-6 py-3 dark:bg-gray-900/50">
          {['Choose Template', 'Preview', 'Install'].map((label, i) => (
            <div key={label} className="flex items-center gap-2">
              <div
                className={`flex h-6 w-6 items-center justify-center rounded-full text-xs font-medium transition-colors ${
                  i < wizardStep
                    ? 'bg-blue-600 text-white'
                    : i === wizardStep
                      ? 'bg-blue-600 text-white'
                      : 'bg-gray-200 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
                }`}
              >
                {i < wizardStep ? <CheckCircleIcon className="h-4 w-4" /> : i + 1}
              </div>
              <span
                className={`text-xs font-medium ${
                  i <= wizardStep
                    ? 'text-gray-900 dark:text-white'
                    : 'text-gray-400 dark:text-gray-500'
                }`}
              >
                {label}
              </span>
              {i < 2 && (
                <div
                  className={`h-px w-8 ${i < wizardStep ? 'bg-blue-400' : 'bg-gray-200 dark:bg-gray-700'}`}
                />
              )}
            </div>
          ))}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-auto px-6 py-5">
          {wizardStep === 0 && (
            <Step1ChooseTemplate selected={selectedTemplate} onSelect={setSelectedTemplate} />
          )}
          {wizardStep === 1 && selectedTemplate && <Step2Preview template={selectedTemplate} />}
          {wizardStep === 2 && selectedTemplate && (
            <Step3Install
              template={selectedTemplate}
              installing={installing}
              success={installSuccess}
              error={installError}
            />
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-gray-200 bg-gray-50 px-6 py-4 dark:border-gray-700 dark:bg-gray-900/50">
          <button
            onClick={() => {
              if (wizardStep === 0) closeWizard();
              else setWizardStep(wizardStep - 1);
            }}
            className="flex items-center gap-1.5 px-4 py-2 text-sm text-gray-600 transition-colors hover:text-gray-900 dark:text-gray-400 dark:hover:text-white"
            disabled={installing}
          >
            <ArrowLeftIcon className="h-4 w-4" />
            {wizardStep === 0 ? 'Cancel' : 'Back'}
          </button>

          {wizardStep === 0 && (
            <button
              onClick={() => selectedTemplate && setWizardStep(1)}
              disabled={!selectedTemplate}
              className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
            >
              Next
              <ArrowRightIcon className="h-4 w-4" />
            </button>
          )}
          {wizardStep === 1 && (
            <button
              onClick={() => {
                setWizardStep(2);
                handleInstall();
              }}
              className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            >
              Install Template
              <ArrowRightIcon className="h-4 w-4" />
            </button>
          )}
          {wizardStep === 2 && installSuccess && (
            <button
              onClick={handleGoToApp}
              className="flex items-center gap-1.5 rounded-lg bg-green-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-green-700"
              data-testid="wizard-go-to-app"
            >
              Go to App
              <ArrowRightIcon className="h-4 w-4" />
            </button>
          )}
          {wizardStep === 2 && !installSuccess && !installing && installError && (
            <button
              onClick={handleInstall}
              className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            >
              Retry
            </button>
          )}
        </div>
      </div>
    </div>
  );

  return createPortal(modal, document.body);
}

// ---------------------------------------------------------------------------
// Step 1: Choose Template
// ---------------------------------------------------------------------------

function Step1ChooseTemplate({
  selected,
  onSelect,
}: {
  selected: WizardTemplate | null;
  onSelect: (t: WizardTemplate) => void;
}) {
  return (
    <div>
      <p className="mb-4 text-sm text-gray-600 dark:text-gray-400">
        Choose a business template to get started. You can always install more templates later.
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {WIZARD_TEMPLATES.map((tpl) => {
          const isSelected = selected?.id === tpl.id;
          return (
            <button
              key={tpl.id}
              onClick={() => onSelect(tpl)}
              className={`rounded-xl border-2 p-4 text-left transition-all ${
                isSelected
                  ? `${BG_COLORS[tpl.color] || BG_COLORS.blue} ring-2 ${RING_COLORS[tpl.color] || RING_COLORS.blue}`
                  : 'border-gray-200 hover:border-gray-300 hover:shadow-sm dark:border-gray-700 dark:hover:border-gray-600'
              }`}
              data-testid={`wizard-template-${tpl.id}`}
            >
              <div className="mb-2 flex items-center gap-3">
                <span className="text-2xl">{tpl.icon}</span>
                <div>
                  <h4 className="text-sm font-semibold text-gray-900 dark:text-white">
                    {tpl.name}
                  </h4>
                  <span className="text-xs text-gray-500 dark:text-gray-400">
                    {tpl.modelCount} models
                  </span>
                </div>
                {isSelected && (
                  <CheckCircleIcon className="ml-auto h-5 w-5 text-blue-600 dark:text-blue-400" />
                )}
              </div>
              <p className="text-xs leading-relaxed text-gray-600 dark:text-gray-400">
                {tpl.description}
              </p>
            </button>
          );
        })}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 2: Preview
// ---------------------------------------------------------------------------

function Step2Preview({ template }: { template: WizardTemplate }) {
  return (
    <div>
      <div className="mb-5 flex items-center gap-4">
        <span className="text-4xl">{template.icon}</span>
        <div>
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">{template.name}</h3>
          <p className="text-sm text-gray-600 dark:text-gray-400">{template.description}</p>
        </div>
      </div>

      <div className="mb-4 rounded-xl bg-gray-50 p-4 dark:bg-gray-900/50">
        <h4 className="mb-3 text-xs font-semibold tracking-wider text-gray-500 uppercase dark:text-gray-400">
          Included Features
        </h4>
        <div className="grid grid-cols-2 gap-2">
          {template.features.map((feat) => (
            <div
              key={feat}
              className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300"
            >
              <CheckCircleIcon className="h-4 w-4 shrink-0 text-green-500" />
              {feat}
            </div>
          ))}
        </div>
      </div>

      <div className="flex items-center gap-4 text-sm text-gray-500 dark:text-gray-400">
        <span>{template.modelCount} data models</span>
        <span>Auto-configured pages</span>
        <span>Full CRUD operations</span>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 3: Install
// ---------------------------------------------------------------------------

function Step3Install({
  template,
  installing,
  success,
  error,
}: {
  template: WizardTemplate;
  installing: boolean;
  success: boolean;
  error: string | null;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-8">
      {installing && (
        <>
          <div className="mb-4 h-12 w-12 animate-spin rounded-full border-b-2 border-blue-600" />
          <h3 className="mb-2 text-base font-semibold text-gray-900 dark:text-white">
            Installing {template.name}...
          </h3>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Setting up models, pages, and workflows. This may take a moment.
          </p>
        </>
      )}
      {success && (
        <>
          <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30">
            <CheckCircleIcon className="h-8 w-8 text-green-600 dark:text-green-400" />
          </div>
          <h3 className="mb-2 text-base font-semibold text-gray-900 dark:text-white">
            {template.name} Installed
          </h3>
          <p className="max-w-sm text-center text-sm text-gray-500 dark:text-gray-400">
            Your application is ready. Click "Go to App" to start using it.
          </p>
        </>
      )}
      {error && !installing && (
        <>
          <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-red-100 dark:bg-red-900/30">
            <XMarkIcon className="h-8 w-8 text-red-600 dark:text-red-400" />
          </div>
          <h3 className="mb-2 text-base font-semibold text-gray-900 dark:text-white">
            Installation Failed
          </h3>
          <p className="max-w-sm text-center text-sm text-red-600 dark:text-red-400">{error}</p>
        </>
      )}
    </div>
  );
}
