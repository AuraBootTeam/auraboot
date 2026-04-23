/**
 * TemplateResourcePreview — Main content area for the template preview page.
 *
 * Shows details of the selected resource:
 * - Default: template overview with description, features, and resource summary
 * - MODEL: table of fields belonging to this model
 * - COMMAND: command details (code, type, action)
 * - PAGE: page type and description
 * - Other: basic code + name + action info
 */

import {
  CubeIcon,
  CommandLineIcon,
  DocumentTextIcon,
  CheckIcon,
  InformationCircleIcon,
} from '@heroicons/react/24/outline';
import type { AppTemplate } from './templateCatalog';
import type { PreviewGroup, ResourceChange } from './useTemplatePreview';
import type { SelectedItem } from './TemplatePreviewSidebar';

// ─── Props ───────────────────────────────────────────────────────────────────

interface TemplateResourcePreviewProps {
  template: AppTemplate;
  groups: PreviewGroup[];
  selectedItem: SelectedItem | null;
  loading: boolean;
}

// ─── Component ───────────────────────────────────────────────────────────────

export function TemplateResourcePreview({
  template,
  groups,
  selectedItem,
  loading,
}: TemplateResourcePreviewProps) {
  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center" data-testid="preview-loading">
        <div className="text-center">
          <div className="mx-auto mb-3 h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
          <p className="text-sm text-gray-500 dark:text-gray-400">Loading template resources...</p>
        </div>
      </div>
    );
  }

  if (!selectedItem) {
    return <OverviewView template={template} groups={groups} />;
  }

  // Find the selected resource
  const group = groups.find((g) => g.type === selectedItem.type);
  const resource = group?.items.find((i) => i.resourceCode === selectedItem.code);

  if (!resource) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-sm text-gray-400 dark:text-gray-500">Resource not found</p>
      </div>
    );
  }

  switch (selectedItem.type) {
    case 'MODEL':
      return <ModelView resource={resource} groups={groups} />;
    case 'COMMAND':
      return <CommandView resource={resource} />;
    case 'PAGE':
      return <PageView resource={resource} />;
    default:
      return <GenericView resource={resource} typeName={group?.label || selectedItem.type} />;
  }
}

// ─── Overview (default, nothing selected) ────────────────────────────────────

function OverviewView({ template, groups }: { template: AppTemplate; groups: PreviewGroup[] }) {
  const modelGroup = groups.find((g) => g.type === 'MODEL');
  const cmdGroup = groups.find((g) => g.type === 'COMMAND');
  const pageGroup = groups.find((g) => g.type === 'PAGE');
  const menuGroup = groups.find((g) => g.type === 'MENU');

  const stats = [
    { label: 'Data Models', count: modelGroup?.items.length ?? 0, icon: CubeIcon },
    { label: 'Commands', count: cmdGroup?.items.length ?? 0, icon: CommandLineIcon },
    { label: 'Pages', count: pageGroup?.items.length ?? 0, icon: DocumentTextIcon },
    { label: 'Menus', count: menuGroup?.items.length ?? 0, icon: DocumentTextIcon },
  ];

  return (
    <div className="flex-1 overflow-y-auto p-8" data-testid="preview-overview">
      {/* Header */}
      <div className="mb-8">
        <div className="mb-3 flex items-center gap-3">
          <span className="text-4xl" role="img">
            {template.icon}
          </span>
          <div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-white">{template.name}</h1>
            <div className="flex items-center gap-2">
              <span className="text-sm text-gray-500 dark:text-gray-400">{template.category}</span>
              <span
                className={`rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase ${
                  template.source === 'enterprise'
                    ? 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'
                    : 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-300'
                }`}
              >
                {template.source === 'enterprise' ? 'Enterprise' : 'OSS'}
              </span>
            </div>
          </div>
        </div>
        <p className="max-w-2xl text-base leading-relaxed text-gray-600 dark:text-gray-400">
          {template.description}
        </p>
      </div>

      {/* Resource summary cards */}
      <div
        className="mb-8 grid grid-cols-2 gap-4 sm:grid-cols-4"
        data-testid="preview-resource-summary"
      >
        {stats.map(({ label, count, icon: Icon }) => (
          <div
            key={label}
            className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
          >
            <div className="mb-2 flex items-center gap-2 text-gray-500 dark:text-gray-400">
              <Icon className="h-4 w-4" />
              <span className="text-xs font-medium">{label}</span>
            </div>
            <p className="text-2xl font-bold text-gray-900 dark:text-white">{count}</p>
          </div>
        ))}
      </div>

      {/* Features */}
      {template.features.length > 0 && (
        <div className="mb-8">
          <h3 className="mb-3 text-sm font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
            Features
          </h3>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2" data-testid="preview-features">
            {template.features.map((feat) => (
              <div
                key={feat}
                className="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-400"
              >
                <CheckIcon className="h-4 w-4 flex-shrink-0 text-green-500 dark:text-green-400" />
                {feat}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Info note */}
      <div className="flex items-start gap-2 rounded-lg bg-blue-50 p-4 text-sm text-blue-700 dark:bg-blue-900/20 dark:text-blue-300">
        <InformationCircleIcon className="mt-0.5 h-4 w-4 flex-shrink-0" />
        <p>
          Select a resource from the sidebar to view its details. Click{' '}
          <strong>Use This Template</strong> to install all resources into your workspace.
        </p>
      </div>
    </div>
  );
}

// ─── Model detail (with fields table) ────────────────────────────────────────

function ModelView({ resource, groups }: { resource: ResourceChange; groups: PreviewGroup[] }) {
  // Find fields belonging to this model
  const fieldGroup = groups.find((g) => g.type === 'FIELD');
  const modelCode = resource.resourceCode;
  const fields = (fieldGroup?.items ?? []).filter((f) => {
    // Field resourceCode is typically "modelCode.fieldCode"
    return f.resourceCode.startsWith(modelCode + '.');
  });

  return (
    <div className="flex-1 overflow-y-auto p-8" data-testid="preview-model-detail">
      <ResourceHeader
        resource={resource}
        icon={<CubeIcon className="h-5 w-5" />}
        typeName="Data Model"
      />

      {/* Fields table */}
      <div className="mt-6">
        <h3 className="mb-3 text-sm font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
          Fields ({fields.length})
        </h3>
        {fields.length === 0 ? (
          <p className="text-sm text-gray-400 dark:text-gray-500">No fields detected in preview.</p>
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700">
            <table className="w-full text-sm" data-testid="preview-fields-table">
              <thead>
                <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50">
                  <th className="px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                    Field Code
                  </th>
                  <th className="px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                    Name
                  </th>
                  <th className="px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                    Action
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                {fields.map((field) => {
                  // Extract field code from "modelCode.fieldCode"
                  const fieldCode = field.resourceCode.includes('.')
                    ? field.resourceCode.split('.').slice(1).join('.')
                    : field.resourceCode;

                  return (
                    <tr
                      key={field.resourceCode}
                      className="hover:bg-gray-50 dark:hover:bg-gray-800/30"
                    >
                      <td className="px-4 py-2 font-mono text-xs text-gray-700 dark:text-gray-300">
                        {fieldCode}
                      </td>
                      <td className="px-4 py-2 text-gray-600 dark:text-gray-400">
                        {field.resourceName || fieldCode}
                      </td>
                      <td className="px-4 py-2">
                        <ActionBadge action={field.action} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Command detail ──────────────────────────────────────────────────────────

function CommandView({ resource }: { resource: ResourceChange }) {
  const details = resource.details ?? {};
  const commandType = details.commandType ? String(details.commandType) : null;
  const actionType = details.actionType ? String(details.actionType) : null;
  const modelCode = details.modelCode ? String(details.modelCode) : null;

  return (
    <div className="flex-1 overflow-y-auto p-8" data-testid="preview-command-detail">
      <ResourceHeader
        resource={resource}
        icon={<CommandLineIcon className="h-5 w-5" />}
        typeName="Command"
      />

      <div className="mt-6 space-y-3">
        <DetailRow label="Code" value={resource.resourceCode} mono />
        {commandType && <DetailRow label="Type" value={commandType} />}
        {actionType && <DetailRow label="Action" value={actionType} />}
        {modelCode && <DetailRow label="Model" value={modelCode} mono />}
      </div>
    </div>
  );
}

// ─── Page detail ─────────────────────────────────────────────────────────────

function PageView({ resource }: { resource: ResourceChange }) {
  const details = resource.details ?? {};
  const pageType = details.kind ? String(details.kind) : null;
  const modelCode = details.modelCode ? String(details.modelCode) : null;

  return (
    <div className="flex-1 overflow-y-auto p-8" data-testid="preview-page-detail">
      <ResourceHeader
        resource={resource}
        icon={<DocumentTextIcon className="h-5 w-5" />}
        typeName="Page"
      />

      <div className="mt-6 space-y-3">
        <DetailRow label="Page Key" value={resource.resourceCode} mono />
        {pageType && <DetailRow label="Type" value={pageType} />}
        {modelCode && <DetailRow label="Bound Model" value={modelCode} mono />}
      </div>
    </div>
  );
}

// ─── Generic detail (menus, permissions, dicts, etc.) ────────────────────────

function GenericView({ resource, typeName }: { resource: ResourceChange; typeName: string }) {
  return (
    <div className="flex-1 overflow-y-auto p-8" data-testid="preview-generic-detail">
      <ResourceHeader resource={resource} typeName={typeName} />

      <div className="mt-6 space-y-3">
        <DetailRow label="Code" value={resource.resourceCode} mono />
        <DetailRow label="Name" value={resource.resourceName || resource.resourceCode} />
        <DetailRow label="Action" value={resource.action} />
      </div>
    </div>
  );
}

// ─── Shared subcomponents ────────────────────────────────────────────────────

function ResourceHeader({
  resource,
  icon,
  typeName,
}: {
  resource: ResourceChange;
  icon?: React.ReactNode;
  typeName: string;
}) {
  return (
    <div>
      <div className="mb-1 flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
        {icon}
        <span>{typeName}</span>
        <ActionBadge action={resource.action} />
      </div>
      <h2
        className="text-xl font-bold text-gray-900 dark:text-white"
        data-testid="preview-resource-title"
      >
        {resource.resourceName || resource.resourceCode}
      </h2>
      <p className="mt-1 font-mono text-sm text-gray-500 dark:text-gray-400">
        {resource.resourceCode}
      </p>
    </div>
  );
}

function DetailRow({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex items-baseline gap-3">
      <span className="w-28 flex-shrink-0 text-sm font-medium text-gray-500 dark:text-gray-400">
        {label}
      </span>
      <span className={`text-sm text-gray-900 dark:text-gray-200 ${mono ? 'font-mono' : ''}`}>
        {value}
      </span>
    </div>
  );
}

function ActionBadge({ action }: { action: string }) {
  const colors: Record<string, string> = {
    CREATE: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    UPDATE: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
    SKIP: 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400',
  };

  return (
    <span
      className={`inline-flex rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${
        colors[action] || colors.SKIP
      }`}
    >
      {action}
    </span>
  );
}
