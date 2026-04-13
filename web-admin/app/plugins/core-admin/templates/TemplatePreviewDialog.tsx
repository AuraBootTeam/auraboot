/**
 * TemplatePreviewDialog — Modal that shows template details before installation.
 *
 * Displays template metadata (name, description, models, features) and
 * provides an install flow with step-by-step progress tracking via SSE.
 *
 * GAP-028: Plugin Install Observability — shows installation steps in real time.
 */

import { useState, useEffect, useRef } from 'react';
import {
  XMarkIcon,
  CheckCircleIcon,
  ArrowPathIcon,
  CubeIcon,
  CommandLineIcon,
  CheckIcon,
  ArrowTopRightOnSquareIcon,
  StarIcon,
  ExclamationCircleIcon,
  ClockIcon,
} from '@heroicons/react/24/outline';
import { post } from '~/shared/services/http-client';
import type { AppTemplate } from './templateCatalog';

type DialogState = 'preview' | 'installing' | 'success' | 'error';

interface ProgressState {
  taskCode: string | null;
  status: string;
  progress: number;
  progressMessage: string;
  errorMessage: string;
}

interface TemplatePreviewDialogProps {
  template: AppTemplate;
  onClose: () => void;
  onInstalled: (template: AppTemplate) => void;
}

// ─── Install Steps definition ────────────────────────────────────────────────
// Maps progress ranges to meaningful step labels for display.
const INSTALL_STEPS = [
  { id: 'validate', label: 'Validating plugin', minPct: 0, maxPct: 20 },
  { id: 'models', label: 'Saving models & fields', minPct: 20, maxPct: 40 },
  { id: 'commands', label: 'Saving commands', minPct: 40, maxPct: 55 },
  { id: 'pages', label: 'Saving pages & menus', minPct: 55, maxPct: 70 },
  { id: 'permissions', label: 'Creating permissions', minPct: 70, maxPct: 85 },
  { id: 'publish', label: 'Publishing resources', minPct: 85, maxPct: 100 },
];

function getActiveStep(progress: number): string {
  for (const step of INSTALL_STEPS) {
    if (progress >= step.minPct && progress < step.maxPct) return step.id;
  }
  return progress >= 100 ? 'publish' : 'validate';
}

// ─── Component ────────────────────────────────────────────────────────────────

export function TemplatePreviewDialog({
  template,
  onClose,
  onInstalled,
}: TemplatePreviewDialogProps) {
  const [state, setState] = useState<DialogState>('preview');
  const [errorMessage, setErrorMessage] = useState('');
  const [installProgress, setInstallProgress] = useState<ProgressState>({
    taskCode: null,
    status: 'pending',
    progress: 0,
    progressMessage: 'Starting...',
    errorMessage: '',
  });
  const sseRef = useRef<EventSource | null>(null);
  const token =
    typeof document !== 'undefined'
      ? (document.cookie.match(/(?:^|;\s*)token=([^;]+)/)?.[1] ?? '')
      : '';

  // Cleanup SSE on unmount
  useEffect(() => {
    return () => {
      sseRef.current?.close();
    };
  }, []);

  const handleInstall = async () => {
    setState('installing');
    setErrorMessage('');
    setInstallProgress({
      taskCode: null,
      status: 'pending',
      progress: 2,
      progressMessage: 'Submitting install task...',
      errorMessage: '',
    });

    try {
      // Submit async install — returns { taskCode, status, ... }
      const res = await post<{ taskCode?: string; success?: boolean }>(
        `/api/templates/${template.id}/install`,
        {
          conflictStrategy: 'overwrite',
          autoPublishModels: true,
          autoPublishFields: true,
          autoPublishCommands: true,
          autoPublishPages: true,
        },
      );

      const taskCode = res?.data?.taskCode;

      // Fast path: synchronous result (no taskCode = already done)
      if (!taskCode) {
        const ok = res?.data?.success !== false;
        if (ok) {
          setState('success');
          onInstalled(template);
        } else {
          setState('error');
          setErrorMessage('Installation returned failure status.');
        }
        return;
      }

      // Async path: track progress via SSE
      setInstallProgress((prev) => ({
        ...prev,
        taskCode,
        progress: 5,
        progressMessage: 'Task submitted...',
      }));
      startSseTracking(taskCode);
    } catch (err: any) {
      setState('error');
      setErrorMessage(err?.message ?? 'Unknown error');
    }
  };

  const startSseTracking = (taskCode: string) => {
    sseRef.current?.close();

    // Use polling fallback (SSE may not work well through BFF proxy in all envs)
    // Poll every 800ms until terminal
    const poll = async () => {
      try {
        const res = await fetch(`/api/async-tasks/${taskCode}`);
        if (!res.ok) return;
        const json = await res.json();
        const task = json.data;
        if (!task) return;

        const progress = task.progress ?? 0;
        const status = task.status ?? 'running';
        const progressMessage = task.progressMessage ?? '';
        const errorMsg = task.errorMessage ?? '';

        setInstallProgress((prev) => ({
          ...prev,
          taskCode,
          status,
          progress,
          progressMessage,
          errorMessage: errorMsg,
        }));

        if (['success', 'failed', 'cancelled', 'timed_out'].includes(status)) {
          if (status === 'success') {
            setState('success');
            onInstalled(template);
          } else {
            setState('error');
            setErrorMessage(errorMsg || `Install failed with status: ${status}`);
          }
          return; // stop polling
        }

        // Continue polling
        setTimeout(poll, 800);
      } catch {
        setTimeout(poll, 1500); // retry on network error
      }
    };

    poll();
  };

  const handleGoToApp = () => {
    window.location.reload();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
      data-testid="template-preview-dialog"
    >
      <div className="mx-4 w-full max-w-lg overflow-hidden rounded-2xl bg-white shadow-2xl dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 p-5 dark:border-gray-700">
          <div className="flex items-center gap-3">
            <span className="text-3xl" role="img">
              {template.icon}
            </span>
            <div>
              <h3
                className="text-lg font-semibold text-gray-900 dark:text-white"
                data-testid="template-preview-name"
              >
                {template.name}
              </h3>
              <span className="text-xs text-gray-500 dark:text-gray-400">{template.category}</span>
            </div>
          </div>
          <button
            onClick={onClose}
            disabled={state === 'installing'}
            className="rounded-lg p-1 transition-colors hover:bg-gray-100 disabled:opacity-30 dark:hover:bg-gray-700"
            aria-label="Close"
            data-testid="template-preview-close"
          >
            <XMarkIcon className="h-5 w-5 text-gray-500" />
          </button>
        </div>

        {/* Body */}
        <div className="p-5">
          {state === 'success' ? (
            <SuccessView templateName={template.name} onGoToApp={handleGoToApp} />
          ) : state === 'installing' ? (
            <InstallingView progress={installProgress} />
          ) : (
            <>
              {/* Description */}
              <p
                className="mb-4 text-sm text-gray-600 dark:text-gray-400"
                data-testid="template-preview-description"
              >
                {template.description}
              </p>

              {/* Features list */}
              <div className="mb-4">
                <h4 className="mb-2 text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Features
                </h4>
                <div className="space-y-1.5" data-testid="template-preview-features">
                  {template.features.map((feat) => (
                    <div
                      key={feat}
                      className="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400"
                    >
                      <CheckIcon className="h-3.5 w-3.5 flex-shrink-0 text-green-500 dark:text-green-400" />
                      {feat}
                    </div>
                  ))}
                </div>
              </div>

              {/* Resource summary */}
              <div className="mb-4 space-y-2 rounded-lg bg-gray-50 p-4 text-sm dark:bg-gray-900/50">
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-2 text-gray-600 dark:text-gray-400">
                    <CubeIcon className="h-4 w-4" />
                    Data Models
                  </span>
                  <span
                    className="font-medium text-gray-900 dark:text-white"
                    data-testid="template-preview-model-count"
                  >
                    {template.modelCount}
                  </span>
                </div>
                {template.commandCount > 0 && (
                  <div className="flex items-center justify-between">
                    <span className="flex items-center gap-2 text-gray-600 dark:text-gray-400">
                      <CommandLineIcon className="h-4 w-4" />
                      Commands
                    </span>
                    <span className="font-medium text-gray-900 dark:text-white">
                      {template.commandCount}
                    </span>
                  </div>
                )}
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-2 text-gray-600 dark:text-gray-400">
                    <ArrowTopRightOnSquareIcon className="h-4 w-4" />
                    Pages & Menus
                  </span>
                  <span className="font-medium text-gray-900 dark:text-white">Auto-generated</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-2 text-gray-600 dark:text-gray-400">
                    <StarIcon className="h-4 w-4" />
                    Permissions
                  </span>
                  <span className="font-medium text-gray-900 dark:text-white">Auto-created</span>
                </div>
              </div>

              {/* Error message */}
              {state === 'error' && (
                <div className="mb-4 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400">
                  Installation failed: {errorMessage}
                </div>
              )}

              <p className="text-xs text-gray-500 dark:text-gray-500">
                If previously installed, existing resources will be overwritten.
              </p>
            </>
          )}
        </div>

        {/* Footer */}
        {state !== 'success' && state !== 'installing' && (
          <div className="flex items-center justify-end gap-3 border-t border-gray-100 bg-gray-50 p-5 dark:border-gray-700 dark:bg-gray-900/30">
            <button
              onClick={onClose}
              className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            >
              Cancel
            </button>
            <button
              onClick={handleInstall}
              data-testid="template-preview-install"
              className="flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-600"
            >
              {state === 'error' ? 'Retry Install' : 'Install Template'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Installing view ──────────────────────────────────────────────────────────

function InstallingView({ progress }: { progress: ProgressState }) {
  const activeStep = getActiveStep(progress.progress);
  const pct = Math.max(2, Math.min(100, progress.progress));

  return (
    <div data-testid="template-install-progress">
      {/* Progress bar */}
      <div className="mb-5">
        <div className="mb-1.5 flex items-center justify-between">
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
            Installing...
          </span>
          <span className="text-sm text-gray-500 dark:text-gray-400">{pct}%</span>
        </div>
        <div className="h-2 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
          <div
            className="h-full rounded-full bg-blue-600 transition-all duration-300"
            style={{ width: `${pct}%` }}
          />
        </div>
        <p className="mt-1.5 truncate text-xs text-gray-500 dark:text-gray-400">
          {progress.progressMessage}
        </p>
      </div>

      {/* Step list */}
      <div className="space-y-2.5">
        {INSTALL_STEPS.map((step) => {
          const isActive = step.id === activeStep;
          const isDone = progress.progress >= step.maxPct;
          const isPending = !isActive && !isDone;

          return (
            <div key={step.id} className="flex items-center gap-3">
              {/* Step indicator */}
              <div
                className={`flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full ${
                  isDone
                    ? 'bg-green-100 dark:bg-green-900/30'
                    : isActive
                      ? 'bg-blue-100 dark:bg-blue-900/30'
                      : 'bg-gray-100 dark:bg-gray-800'
                }`}
              >
                {isDone ? (
                  <CheckCircleIcon className="h-4 w-4 text-green-600 dark:text-green-400" />
                ) : isActive ? (
                  <ArrowPathIcon className="h-3.5 w-3.5 animate-spin text-blue-600 dark:text-blue-400" />
                ) : (
                  <ClockIcon className="h-3.5 w-3.5 text-gray-400 dark:text-gray-600" />
                )}
              </div>

              {/* Step label */}
              <span
                className={`text-sm ${
                  isDone
                    ? 'text-green-700 dark:text-green-400'
                    : isActive
                      ? 'font-medium text-blue-700 dark:text-blue-300'
                      : 'text-gray-400 dark:text-gray-600'
                }`}
              >
                {step.label}
              </span>
            </div>
          );
        })}
      </div>

      {/* Error during install */}
      {progress.errorMessage && (
        <div className="mt-4 flex items-start gap-2 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400">
          <ExclamationCircleIcon className="mt-0.5 h-4 w-4 flex-shrink-0" />
          {progress.errorMessage}
        </div>
      )}

      {/* Task code for debugging */}
      {progress.taskCode && (
        <p className="mt-3 text-center text-xs text-gray-400 dark:text-gray-600">
          Task: {progress.taskCode}
        </p>
      )}
    </div>
  );
}

// ─── Success sub-view ─────────────────────────────────────────────────────────

function SuccessView({ templateName, onGoToApp }: { templateName: string; onGoToApp: () => void }) {
  return (
    <div className="py-4 text-center" data-testid="template-install-success">
      <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30">
        <CheckCircleIcon className="h-7 w-7 text-green-600 dark:text-green-400" />
      </div>
      <h4 className="mb-1 text-lg font-semibold text-gray-900 dark:text-white">
        {templateName} Installed
      </h4>
      <p className="mb-5 text-sm text-gray-500 dark:text-gray-400">
        All models, pages, commands, and menus have been created. Refresh to see new entries in the
        sidebar.
      </p>
      <button
        onClick={onGoToApp}
        data-testid="template-go-to-app"
        className="inline-flex items-center gap-2 rounded-lg bg-green-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-green-700 dark:bg-green-500 dark:hover:bg-green-600"
      >
        <ArrowTopRightOnSquareIcon className="h-4 w-4" />
        Go to App
      </button>
    </div>
  );
}

export default TemplatePreviewDialog;
