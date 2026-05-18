// web-admin/app/smart/automation/components/AutomationList.tsx
import React, { useEffect, useState, useRef } from 'react';
import { Link, useNavigate, useRevalidator } from 'react-router';
import { useSmartText } from '~/utils/i18n';
import { cn } from '~/utils/cn';
import { ExecutionLogDialog } from './ExecutionLogDialog';
import { TemplateGallery } from './TemplateGallery';
import type { Automation } from '../services/automationService';
import type { AutomationTemplate } from '../templates/automationTemplates';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';

export interface AutomationListProps {
  className?: string;
  initialAutomations: Automation[];
  token: string | null;
  serverError?: string;
}

export function AutomationList({
  className,
  initialAutomations,
  token,
  serverError,
}: AutomationListProps) {
  const st = useSmartText();
  const navigate = useNavigate();
  const revalidator = useRevalidator();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [hydrated, setHydrated] = useState(false);
  const [logDialogAutomation, setLogDialogAutomation] = useState<Automation | null>(null);
  const [showTemplateGallery, setShowTemplateGallery] = useState(false);
  const importInputRef = useRef<HTMLInputElement>(null);

  const authHeaders: HeadersInit = token
    ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    : { 'Content-Type': 'application/json' };

  useEffect(() => {
    setHydrated(true);
  }, []);

  const handleToggleEnabled = async (automation: Automation) => {
    try {
      const response = await fetch(`/api/automations/${automation.pid}/toggle`, {
        method: 'post',
        headers: authHeaders,
      });
      if (!response.ok) {
        throw new Error('Failed to toggle automation');
      }
      revalidator.revalidate();
    } catch (err) {
      showErrorToast(err instanceof Error ? err.message : 'Failed to toggle');
    }
  };

  const handleDelete = async (automation: Automation) => {
    if (
      !(await confirmDialog({
        content: st('$i18n:automation.list.deleteConfirm') || `Delete "${automation.name}"?`,
        variant: 'danger',
      }))
    ) {
      return;
    }
    try {
      const response = await fetch(`/api/automations/${automation.pid}`, {
        method: 'delete',
        headers: authHeaders,
      });
      if (!response.ok) {
        throw new Error('Failed to delete automation');
      }
      revalidator.revalidate();
    } catch (err) {
      showErrorToast(err instanceof Error ? err.message : 'Failed to delete');
    }
  };

  const handleSelectTemplate = async (template: AutomationTemplate) => {
    try {
      const response = await fetch('/api/automations', {
        method: 'post',
        headers: authHeaders,
        body: JSON.stringify({
          name: template.name,
          description: template.description,
          flowConfig: template.flowData,
        }),
      });
      if (!response.ok) throw new Error('Failed to create automation from template');
      const result = await response.json();
      showSuccessToast(`Created automation "${template.name}" from template`);
      navigate(`/automation/${result.data.pid}`);
    } catch (err) {
      showErrorToast(err instanceof Error ? err.message : 'Failed to create from template');
    }
  };

  const handleExportAutomation = async (automation: Automation) => {
    try {
      const response = await fetch(`/api/automations/${automation.pid}`, {
        headers: authHeaders,
      });
      if (!response.ok) throw new Error('Failed to fetch automation data');
      const result = await response.json();
      const exportData = result.data;
      const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `automation-${automation.name.replace(/\s+/g, '-').toLowerCase()}.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      showSuccessToast(`Exported "${automation.name}"`);
    } catch (err) {
      showErrorToast(err instanceof Error ? err.message : 'Export failed');
    }
  };

  const handleImportAutomation = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      const data = JSON.parse(text);
      const response = await fetch('/api/automations', {
        method: 'post',
        headers: authHeaders,
        body: JSON.stringify({
          name: data.name || `Imported - ${file.name}`,
          description: data.description || 'Imported automation',
          flowConfig: data.flowConfig || data.flowData,
          enabled: false,
        }),
      });
      if (!response.ok) throw new Error('Failed to import automation');
      showSuccessToast(`Imported "${data.name || file.name}"`);
      revalidator.revalidate();
    } catch (err) {
      showErrorToast(err instanceof Error ? err.message : 'Import failed. Check JSON format.');
    } finally {
      // Reset the input so re-importing the same file works
      if (importInputRef.current) importInputRef.current.value = '';
    }
  };

  const getTriggerIcon = (triggerType: string) => {
    const icons: Record<string, string> = {
      ON_RECORD_CREATE: '+',
      ON_RECORD_UPDATE: '~',
      ON_FIELD_CHANGE: '*',
      ON_STATE_CHANGE: '#',
      SCHEDULED: '@',
      WEBHOOK: '&',
    };
    return icons[triggerType] || '>';
  };

  if (serverError) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="text-red-500">{serverError}</div>
      </div>
    );
  }

  return (
    <div className={cn('rounded-lg bg-white shadow', className)}>
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
        <h2 className="text-lg font-semibold text-gray-900" data-testid="page-title">
          {st('$i18n:automation.list.title') || 'Automations'}
        </h2>
        <div className="flex items-center gap-2">
          {/* Import */}
          <input
            ref={importInputRef}
            type="file"
            accept=".json"
            className="hidden"
            onChange={handleImportAutomation}
            data-testid="input-import-automation"
          />
          <button
            onClick={() => importInputRef.current?.click()}
            disabled={!hydrated}
            className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
            data-testid="btn-import-automation"
          >
            {st('$i18n:automation.list.import') || 'Import'}
          </button>
          {/* New from Template */}
          <button
            onClick={() => setShowTemplateGallery(true)}
            disabled={!hydrated}
            className="rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-sm font-medium text-blue-700 transition-colors hover:bg-blue-100"
            data-testid="btn-new-from-template"
          >
            {st('$i18n:automation.list.newFromTemplate') || 'New from Template'}
          </button>
          {/* Create blank */}
          <Link
            to="/automation/new"
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            data-testid="btn-create-automation"
          >
            {st('$i18n:automation.list.create') || 'Create Automation'}
          </Link>
        </div>
      </div>

      {/* List */}
      {initialAutomations.length === 0 ? (
        <div className="px-6 py-12 text-center text-gray-500" data-testid="automation-empty">
          {st('$i18n:automation.list.empty') || 'No automations yet. Create your first one!'}
        </div>
      ) : (
        <div className="divide-y divide-gray-200">
          {initialAutomations.map((automation) => (
            <div
              key={automation.pid}
              className="px-6 py-4 transition-colors hover:bg-gray-50"
              data-testid={`automation-row-${automation.pid}`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-4">
                  <span className="font-mono text-2xl text-gray-400">
                    {getTriggerIcon(automation.triggerType)}
                  </span>
                  <div>
                    <div className="flex items-center gap-2">
                      <Link
                        to={`/automation/${automation.pid}`}
                        className="font-medium text-gray-900 hover:text-blue-600"
                      >
                        {automation.name}
                      </Link>
                      <span
                        className={cn(
                          'rounded px-2 py-0.5 text-xs font-medium',
                          automation.enabled
                            ? 'bg-green-100 text-green-700'
                            : 'bg-gray-100 text-gray-600',
                        )}
                        data-testid={`status-${automation.pid}`}
                      >
                        {automation.enabled
                          ? st('$i18n:automation.list.enabled') || 'Enabled'
                          : st('$i18n:automation.list.disabled') || 'Disabled'}
                      </span>
                    </div>
                    {automation.description && (
                      <p className="mt-1 text-sm text-gray-500">{automation.description}</p>
                    )}
                    {automation.lastRunAt && (
                      <p className="mt-1 text-xs text-gray-400">
                        {st('$i18n:automation.list.lastRun') || 'Last run'}:{' '}
                        {new Date(automation.lastRunAt).toLocaleString()}
                      </p>
                    )}
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleToggleEnabled(automation)}
                    disabled={!hydrated}
                    className={cn(
                      'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                      automation.enabled
                        ? 'bg-orange-50 text-orange-700 hover:bg-orange-100'
                        : 'bg-green-50 text-green-700 hover:bg-green-100',
                    )}
                    data-testid={`btn-toggle-${automation.pid}`}
                  >
                    {automation.enabled
                      ? st('$i18n:automation.list.disable') || 'Disable'
                      : st('$i18n:automation.list.enable') || 'Enable'}
                  </button>
                  <button
                    onClick={() => setLogDialogAutomation(automation)}
                    disabled={!hydrated}
                    className="rounded-md bg-purple-50 px-3 py-1.5 text-sm font-medium text-purple-700 transition-colors hover:bg-purple-100"
                    data-testid={`btn-logs-${automation.pid}`}
                  >
                    {st('$i18n:automation.list.logs') || 'Logs'}
                  </button>
                  <button
                    onClick={() => handleExportAutomation(automation)}
                    disabled={!hydrated}
                    className="rounded-md bg-teal-50 px-3 py-1.5 text-sm font-medium text-teal-700 transition-colors hover:bg-teal-100"
                    data-testid={`btn-export-${automation.pid}`}
                  >
                    {st('$i18n:automation.list.export') || 'Export'}
                  </button>
                  <Link
                    to={`/automation/${automation.pid}`}
                    className="rounded-md bg-blue-50 px-3 py-1.5 text-sm font-medium text-blue-700 transition-colors hover:bg-blue-100"
                    data-testid={`btn-edit-${automation.pid}`}
                  >
                    {st('$i18n:automation.list.edit') || 'Edit'}
                  </Link>
                  <button
                    onClick={() => handleDelete(automation)}
                    disabled={!hydrated}
                    className="rounded-md bg-red-50 px-3 py-1.5 text-sm font-medium text-red-700 transition-colors hover:bg-red-100"
                    data-testid={`btn-delete-${automation.pid}`}
                  >
                    {st('$i18n:automation.list.delete') || 'Delete'}
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
      {logDialogAutomation && (
        <ExecutionLogDialog
          open={!!logDialogAutomation}
          onOpenChange={(open) => {
            if (!open) setLogDialogAutomation(null);
          }}
          automationId={logDialogAutomation.pid}
          automationName={logDialogAutomation.name}
          token={token}
        />
      )}
      <TemplateGallery
        open={showTemplateGallery}
        onClose={() => setShowTemplateGallery(false)}
        onSelectTemplate={handleSelectTemplate}
      />
    </div>
  );
}

export default AutomationList;
