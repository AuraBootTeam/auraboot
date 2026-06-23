import { useMemo, useState } from 'react';
import { ChevronDownIcon, ChevronRightIcon, Cog6ToothIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import type { PermissionMatrixDTO } from '../types';
import type { CapabilityGroup } from './types';
import { SCOPE_OPTIONS, normalizeScope } from '../scopeConfig';
import { deriveCodeSources, sourceFor } from './coverageHelpers';
import PolicyConfigDialog from '../PolicyConfigDialog';

interface AdvancedAtomicActionsProps {
  rolePid: string;
  matrix: PermissionMatrixDTO | null;
  capabilityGroups: CapabilityGroup[];
  onToggle: (permissionId: number, granted: boolean) => void;
  onScopeChange: (resourceCode: string, actionCode: string, scopeType: string) => void;
}

interface FlatRow {
  resourceCode: string;
  resourceName: string;
  permissionId: number;
  permissionPid: string;
  code: string;
  action: string;
  label: string;
  granted: boolean;
  scopeType: string;
  policySchema?: string;
}

interface PolicyDialogState {
  permissionPid: string;
  permissionLabel: string;
  schema: Record<string, any>;
  initialValues?: Record<string, any>;
}

/**
 * ③ Advanced "escape hatch": the old dense matrix, folded into a default-collapsed, searchable
 * resource-grouped table of atomic permission codes. Each row shows the code + business name, its
 * data scope (per-code override), and its SOURCE — green when granted via a declared business
 * capability (edit it there), amber "exception" when it's a direct grant uncovered by any capability.
 * For the ~5% audit / exception case; everyday editing happens in ① the capability checklist.
 */
export default function AdvancedAtomicActions({
  rolePid,
  matrix,
  capabilityGroups,
  onToggle,
  onScopeChange,
}: AdvancedAtomicActionsProps) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const [query, setQuery] = useState('');
  const [onlyGranted, setOnlyGranted] = useState(false);
  const [onlyUncovered, setOnlyUncovered] = useState(false);
  const [policyDialog, setPolicyDialog] = useState<PolicyDialogState | null>(null);

  const sources = useMemo(() => deriveCodeSources(capabilityGroups), [capabilityGroups]);

  const rows = useMemo<FlatRow[]>(() => {
    if (!matrix) return [];
    const out: FlatRow[] = [];
    for (const mod of matrix.modules) {
      for (const res of mod.resources) {
        for (const act of res.actions) {
          if (!act.supported) continue;
          out.push({
            resourceCode: res.resourceCode,
            resourceName: res.resourceName,
            permissionId: act.permissionId,
            permissionPid: act.permissionPid,
            code: act.code,
            action: act.action,
            label: act.label,
            granted: act.granted,
            scopeType: normalizeScope(act.scopeType),
            policySchema: act.policySchema,
          });
        }
      }
    }
    return out;
  }, [matrix]);

  const totalCodes = rows.length;
  const exceptionCount = rows.filter((r) => r.granted && !sources[r.code]?.covered).length;

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((r) => {
      if (onlyGranted && !r.granted) return false;
      if (onlyUncovered && sources[r.code]?.covered) return false;
      if (q && !r.code.toLowerCase().includes(q) && !(r.label || '').toLowerCase().includes(q)) return false;
      return true;
    });
  }, [rows, query, onlyGranted, onlyUncovered, sources]);

  // Group filtered rows by resource, preserving first-seen order.
  const grouped = useMemo(() => {
    const map = new Map<string, { resourceName: string; rows: FlatRow[] }>();
    for (const r of filtered) {
      if (!map.has(r.resourceCode)) map.set(r.resourceCode, { resourceName: r.resourceName, rows: [] });
      map.get(r.resourceCode)!.rows.push(r);
    }
    return [...map.entries()];
  }, [filtered]);

  return (
    <section
      data-testid="advanced-atomic-section"
      className="border-t border-gray-100 pt-4 dark:border-gray-800"
    >
      <button
        type="button"
        data-testid="advanced-atomic-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded((v) => !v)}
        className="flex w-full items-center justify-between text-sm text-gray-900 dark:text-gray-100"
      >
        <span className="flex items-center gap-2">
          <span className="flex h-5 w-5 items-center justify-center rounded-full bg-amber-400 text-[11px] text-white">
            ③
          </span>
          <span className="font-semibold">
            {t('admin.permission.advanced.title', undefined, 'Advanced · configure by atomic action')}
          </span>
          <span className="text-[11px] text-gray-400">
            {t('admin.permission.advanced.summary', { total: totalCodes, exceptions: exceptionCount },
              `${totalCodes} codes · ${exceptionCount} exceptions`)}
          </span>
        </span>
        {expanded ? (
          <ChevronDownIcon className="h-4 w-4 text-gray-400" />
        ) : (
          <ChevronRightIcon className="h-4 w-4 text-gray-400" />
        )}
      </button>

      {expanded && (
        <div data-testid="advanced-atomic-body" className="mt-3">
          {/* purpose banner */}
          <div className="mb-3 rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900 dark:border-amber-900/40 dark:bg-amber-900/10 dark:text-amber-300">
            {t(
              'admin.permission.advanced.banner',
              undefined,
              'These are low-level permission codes — for everyday work use the capability checklist above, which manages them automatically. Use this only to audit which codes a capability includes, or to grant an exception not yet covered by any capability.',
            )}
          </div>

          {/* toolbar */}
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <input
              data-testid="advanced-atomic-search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t('admin.permission.advanced.search', undefined, 'Search code / name…')}
              className="min-w-[220px] flex-1 rounded border border-gray-200 px-2.5 py-1.5 text-xs focus:border-blue-500 focus:outline-none dark:border-gray-700 dark:bg-gray-800"
            />
            <label className="flex cursor-pointer items-center gap-1.5 rounded border border-gray-200 px-2 py-1.5 text-xs text-gray-600 dark:border-gray-700 dark:text-gray-300">
              <input
                type="checkbox"
                data-testid="advanced-filter-granted"
                checked={onlyGranted}
                onChange={(e) => setOnlyGranted(e.target.checked)}
              />
              {t('admin.permission.advanced.onlyGranted', undefined, 'Granted only')}
            </label>
            <label className="flex cursor-pointer items-center gap-1.5 rounded border border-gray-200 px-2 py-1.5 text-xs text-gray-600 dark:border-gray-700 dark:text-gray-300">
              <input
                type="checkbox"
                data-testid="advanced-filter-uncovered"
                checked={onlyUncovered}
                onChange={(e) => setOnlyUncovered(e.target.checked)}
              />
              {t('admin.permission.advanced.onlyUncovered', undefined, 'Uncovered / exception only')}
            </label>
          </div>

          {/* table */}
          <div className="overflow-hidden rounded-md border border-gray-200 dark:border-gray-700">
            <div className="grid grid-cols-[28px_1fr_160px_180px] items-center border-b border-gray-200 bg-gray-50 px-3 py-2 text-[11px] text-gray-500 dark:border-gray-700 dark:bg-gray-800">
              <div />
              <div>{t('admin.permission.advanced.colCode', undefined, 'Code / name')}</div>
              <div>{t('admin.permission.scope.label', undefined, 'Data scope')}</div>
              <div>{t('admin.permission.advanced.colSource', undefined, 'Source')}</div>
            </div>

            {grouped.length === 0 ? (
              <div data-testid="advanced-atomic-empty" className="px-3 py-8 text-center text-xs text-gray-400">
                {t('admin.permission.advanced.empty', undefined, 'No matching permission codes')}
              </div>
            ) : (
              grouped.map(([resourceCode, { resourceName, rows: resRows }]) => (
                <div key={resourceCode} data-testid={`atomic-resource-${resourceCode}`}>
                  <div className="border-b border-gray-100 bg-gray-50/60 px-3 py-1.5 text-[11px] text-gray-500 dark:border-gray-800 dark:bg-gray-800/40">
                    {resourceName} <span className="ml-1 font-mono text-gray-400">{resourceCode}</span>
                  </div>
                  {resRows.map((r) => {
                    const src = sourceFor(sources, r.code);
                    const isException = r.granted && !src.covered;
                    return (
                      <div
                        key={r.code}
                        data-testid={`atomic-row-${r.code}`}
                        className={`grid grid-cols-[28px_1fr_160px_180px] items-center border-b border-gray-50 px-3 py-2 dark:border-gray-800 ${
                          isException ? 'bg-amber-50/40 dark:bg-amber-900/10' : ''
                        }`}
                      >
                        <input
                          type="checkbox"
                          data-testid={`atomic-checkbox-${r.code}`}
                          checked={r.granted}
                          onChange={() => onToggle(r.permissionId, !r.granted)}
                          className="h-4 w-4 cursor-pointer rounded border-gray-300 text-blue-600"
                        />
                        <div className="min-w-0">
                          <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[11px] text-gray-600 dark:bg-gray-800 dark:text-gray-300">
                            {r.code}
                          </span>
                          <span className="ml-2 text-xs text-gray-800 dark:text-gray-200">{r.label}</span>
                          {r.granted && r.policySchema && (
                            <button
                              type="button"
                              title={t('admin.permission.advanced.configurePolicy', undefined, 'Configure policy')}
                              data-testid={`atomic-policy-${r.code}`}
                              onClick={() => {
                                try {
                                  setPolicyDialog({
                                    permissionPid: r.permissionPid,
                                    permissionLabel: r.label || r.code,
                                    schema: JSON.parse(r.policySchema!),
                                  });
                                } catch {
                                  /* invalid schema — ignore */
                                }
                              }}
                              className="ml-1 inline-flex items-center rounded p-0.5 text-gray-400 hover:bg-gray-100 hover:text-blue-600 dark:hover:bg-gray-700"
                            >
                              <Cog6ToothIcon className="h-3.5 w-3.5" />
                            </button>
                          )}
                        </div>
                        <div>
                          {r.granted ? (
                            <select
                              data-testid={`atomic-scope-${r.code}`}
                              value={r.scopeType}
                              onChange={(e) => onScopeChange(r.resourceCode, r.action, e.target.value)}
                              className="w-full rounded border border-gray-200 bg-white px-1.5 py-1 text-[11px] text-gray-700 focus:border-blue-500 focus:outline-none dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200"
                            >
                              {SCOPE_OPTIONS.map((opt) => (
                                <option key={opt.value} value={opt.value}>
                                  {t(opt.labelKey, undefined, opt.labelFallback)}
                                </option>
                              ))}
                            </select>
                          ) : (
                            <span className="text-[11px] text-gray-300">—</span>
                          )}
                        </div>
                        <div data-testid={`atomic-source-${r.code}`}>
                          {src.covered ? (
                            <span className="inline-flex items-center rounded-full border border-green-200 bg-green-50 px-2 py-0.5 text-[11px] text-green-700 dark:border-green-900/40 dark:bg-green-900/10 dark:text-green-400">
                              {t('admin.permission.advanced.sourceCapability', { name: src.capabilityLabel ?? '' },
                                `Capability "${src.capabilityLabel ?? ''}"`)}
                            </span>
                          ) : (
                            <span className="inline-flex items-center rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-700 dark:border-amber-900/40 dark:bg-amber-900/10 dark:text-amber-400">
                              {t('admin.permission.advanced.sourceException', undefined, 'Uncovered · exception')}
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ))
            )}
          </div>

          <p className="mt-2 text-[11px] text-gray-400">
            {t(
              'admin.permission.advanced.legend',
              undefined,
              'Green "Capability X" = granted by a capability above; edit it there. Amber "exception" = no matching business capability; grant directly here (counted as an exception for audit).',
            )}
          </p>
        </div>
      )}

      {policyDialog && (
        <PolicyConfigDialog
          open={policyDialog !== null}
          onClose={() => setPolicyDialog(null)}
          rolePid={rolePid}
          permissionPid={policyDialog.permissionPid}
          permissionLabel={policyDialog.permissionLabel}
          schema={policyDialog.schema}
          initialValues={policyDialog.initialValues}
          onSuccess={() => setPolicyDialog(null)}
        />
      )}
    </section>
  );
}
