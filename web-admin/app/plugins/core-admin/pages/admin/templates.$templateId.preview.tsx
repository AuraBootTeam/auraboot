/**
 * Template Preview Page — Full-page preview of a template before installation.
 *
 * Route: /admin/templates/:templateId/preview
 *
 * Two-panel layout with a resource tree sidebar (left) and resource detail view
 * (right). Sticky footer with Cancel / Use This Template buttons.
 */

import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import {
  ArrowLeftIcon,
  ArrowPathIcon,
  CheckCircleIcon,
  ExclamationCircleIcon,
} from '@heroicons/react/24/outline';
import { APP_TEMPLATES } from '~/plugins/core-admin/templates/templateCatalog';
import { useTemplatePreview } from '~/plugins/core-admin/templates/useTemplatePreview';
import {
  TemplatePreviewSidebar,
  type SelectedItem,
} from '~/plugins/core-admin/templates/TemplatePreviewSidebar';
import { TemplateResourcePreview } from '~/plugins/core-admin/templates/TemplateResourcePreview';
import { post } from '~/services/http-client';

type InstallState = 'idle' | 'installing' | 'success' | 'error';

export default function TemplatePreviewPage() {
  const { templateId } = useParams<{ templateId: string }>();
  const navigate = useNavigate();

  const template = APP_TEMPLATES.find((t) => t.id === templateId) ?? null;
  const { groups, loading, error } = useTemplatePreview(template);
  const [selectedItem, setSelectedItem] = useState<SelectedItem | null>(null);
  const [installState, setInstallState] = useState<InstallState>('idle');
  const [installError, setInstallError] = useState('');

  // ── Not found ──────────────────────────────────────────────────────────────
  if (!template) {
    return (
      <div
        className="flex h-full flex-col items-center justify-center gap-4"
        data-testid="template-not-found"
      >
        <ExclamationCircleIcon className="h-12 w-12 text-gray-400" />
        <h2 className="text-lg font-semibold text-gray-700 dark:text-gray-300">
          Template not found
        </h2>
        <Link
          to="/admin/templates"
          className="text-sm text-blue-600 hover:underline dark:text-blue-400"
        >
          Back to Template Center
        </Link>
      </div>
    );
  }

  // ── Install handler ────────────────────────────────────────────────────────
  const handleInstall = async () => {
    setInstallState('installing');
    setInstallError('');

    try {
      await post(`/api/templates/${template.id}/install`, {
        conflictStrategy: 'overwrite',
        autoPublishModels: true,
        autoPublishFields: true,
        autoPublishCommands: true,
        autoPublishPages: true,
      });

      setInstallState('success');

      // Refresh sidebar menus
      window.dispatchEvent(new Event('menu:refresh'));

      // Navigate to first model after a brief delay for visual feedback
      const modelGroup = groups.find((g) => g.type === 'MODEL');
      const firstModelCode = modelGroup?.items[0]?.resourceCode;
      if (firstModelCode) {
        const routeKey = firstModelCode;
        setTimeout(() => navigate(`/p/${routeKey}`), 1200);
      }
    } catch (err: unknown) {
      setInstallState('error');
      const message = err instanceof Error ? err.message : 'Unknown error';
      setInstallError(message);
    }
  };

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div className="flex h-full flex-col" data-testid="template-preview-page">
      {/* Breadcrumb bar */}
      <div className="flex items-center border-b border-gray-200 bg-white px-5 py-3 dark:border-gray-700 dark:bg-gray-800">
        <Link
          to="/admin/templates"
          className="flex items-center gap-1.5 text-sm text-gray-500 transition-colors hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
          data-testid="back-to-templates"
        >
          <ArrowLeftIcon className="h-4 w-4" />
          Back to Template Center
        </Link>
      </div>

      {/* Main two-panel content */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar */}
        <TemplatePreviewSidebar
          template={template}
          groups={groups}
          selectedItem={selectedItem}
          onSelectItem={setSelectedItem}
          loading={loading}
        />

        {/* Main content area */}
        <main className="flex flex-1 flex-col overflow-hidden bg-gray-50 dark:bg-gray-900">
          {error ? (
            <div className="flex flex-1 items-center justify-center p-8">
              <div className="text-center">
                <ExclamationCircleIcon className="mx-auto mb-3 h-10 w-10 text-red-400" />
                <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
              </div>
            </div>
          ) : (
            <TemplateResourcePreview
              template={template}
              groups={groups}
              selectedItem={selectedItem}
              loading={loading}
            />
          )}
        </main>
      </div>

      {/* Sticky footer */}
      <div
        className="flex items-center justify-between border-t border-gray-200 bg-white px-6 py-4 dark:border-gray-700 dark:bg-gray-800"
        data-testid="preview-footer"
      >
        <div>
          {installState === 'success' && (
            <span className="flex items-center gap-1.5 text-sm text-green-600 dark:text-green-400">
              <CheckCircleIcon className="h-4 w-4" />
              Template installed successfully! Redirecting...
            </span>
          )}
          {installState === 'error' && (
            <span className="flex items-center gap-1.5 text-sm text-red-600 dark:text-red-400">
              <ExclamationCircleIcon className="h-4 w-4" />
              {installError || 'Installation failed'}
            </span>
          )}
        </div>
        <div className="flex items-center gap-3">
          <Link
            to="/admin/templates"
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            data-testid="preview-cancel"
          >
            Cancel
          </Link>
          <button
            onClick={handleInstall}
            disabled={installState === 'installing' || installState === 'success'}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-blue-500 dark:hover:bg-blue-600"
            data-testid="preview-install"
          >
            {installState === 'installing' ? (
              <>
                <ArrowPathIcon className="h-4 w-4 animate-spin" />
                Installing...
              </>
            ) : installState === 'error' ? (
              'Retry Install'
            ) : installState === 'success' ? (
              <>
                <CheckCircleIcon className="h-4 w-4" />
                Installed
              </>
            ) : (
              'Use This Template'
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
