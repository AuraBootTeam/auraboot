import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ChevronDownIcon,
  ChevronRightIcon,
  FolderIcon,
  FolderPlusIcon,
  PencilSquareIcon,
  ArrowUpRightIcon,
  TrashIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import {
  dashboardModuleService,
  type DashboardModuleNode,
} from '~/plugins/core-dashboard/services/dashboardModuleService';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import type { Dashboard } from '~/plugins/core-dashboard/types';

/**
 * Dashboard module tree — folder sidebar for the dashboard management page
 * (Cordys dashboard-module parity). DSL has no tree block, so this ships as a
 * Smart `custom` block: renders the folder tree, per-folder dashboard counts,
 * and the create / rename / move / delete / assign actions against
 * /api/dashboard-modules and PUT /api/dashboards/{pid}.
 */

interface DashboardModuleTreeProps {
  block?: {
    props?: {
      title?: string;
    };
  };
  runtime?: {
    getContext?: () => Record<string, unknown>;
  };
}

interface FlatFolder {
  pid: string;
  name: string;
  parentPid?: string | null;
  dashboardCount: number;
}

function flatten(nodes: DashboardModuleNode[]): FlatFolder[] {
  const folders: FlatFolder[] = [];
  const walk = (node: DashboardModuleNode, parentPid: string | null) => {
    folders.push({
      pid: node.pid,
      name: node.name,
      parentPid,
      dashboardCount: node.dashboardCount ?? 0,
    });
    for (const child of node.children ?? []) {
      walk(child, node.pid);
    }
  };
  nodes.forEach((node) => walk(node, null));
  return folders;
}

function tr(t: (key: string) => string, key: string, fallback: string) {
  const translated = t(key);
  return translated !== key ? translated : fallback;
}

export function DashboardModuleTree({ block }: DashboardModuleTreeProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [tree, setTree] = useState<DashboardModuleNode[]>([]);
  const [folders, setFolders] = useState<FlatFolder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selectedPid, setSelectedPid] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [newFolderName, setNewFolderName] = useState('');
  const [renamingPid, setRenamingPid] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [assignable, setAssignable] = useState<Dashboard[]>([]);
  const [folderDashboards, setFolderDashboards] = useState<Dashboard[]>([]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const nextTree = await dashboardModuleService.tree();
      setTree(nextTree);
      setFolders(flatten(nextTree));
      // Small trees render fully expanded so children stay reachable.
      setExpanded((current) => {
        const next = new Set(current);
        for (const folder of flatten(nextTree)) next.add(folder.pid);
        return next;
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load folders');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Selected-folder dashboards: published + drafts are both assignable; the
  // management table above stays untouched — this panel owns the assignment UX.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await dashboardService.list({ pageSize: 200 });
        if (cancelled) return;
        setAssignable(list.filter((dashboard) => !dashboard.modulePid));
        setFolderDashboards(
          selectedPid ? list.filter((dashboard) => dashboard.modulePid === selectedPid) : [],
        );
      } catch {
        if (!cancelled) {
          setAssignable([]);
          setFolderDashboards([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [selectedPid, busy]);

  const withBusy = useCallback(
    async (action: () => Promise<void>, successMessage?: string) => {
      setBusy(true);
      try {
        await action();
        await refresh();
        if (successMessage) showSuccessToast(successMessage);
      } catch (err) {
        showErrorToast(err instanceof Error ? err.message : 'Request failed');
      } finally {
        setBusy(false);
      }
    },
    [refresh, showSuccessToast, showErrorToast],
  );

  const selected = useMemo(
    () => folders.find((folder) => folder.pid === selectedPid) ?? null,
    [folders, selectedPid],
  );

  const handleCreate = useCallback(async () => {
    const name = newFolderName.trim();
    if (!name) return;
    await withBusy(
      async () => {
        await dashboardModuleService.create(name, selectedPid);
        setNewFolderName('');
        if (selectedPid) {
          // Reveal the new child under its parent.
          setExpanded((current) => new Set(current).add(selectedPid));
        }
      },
      tr(t, 'dashboard.module.created', 'Folder created'),
    );
  }, [newFolderName, selectedPid, withBusy, t]);

  const handleRenameSubmit = useCallback(
    async (pid: string) => {
      const name = renameValue.trim();
      if (!name) return;
      await withBusy(
        async () => {
          await dashboardModuleService.rename(pid, name);
          setRenamingPid(null);
        },
        tr(t, 'dashboard.module.renamed', 'Folder renamed'),
      );
    },
    [renameValue, withBusy, t],
  );

  const handleDelete = useCallback(
    async (pid: string, name: string) => {
      if (
        !window.confirm(
          tr(t, 'dashboard.module.confirmDelete', 'Delete this folder?') + ` (${name})`,
        )
      ) {
        return;
      }
      await withBusy(
        async () => {
          await dashboardModuleService.remove(pid);
          if (selectedPid === pid) setSelectedPid(null);
        },
        tr(t, 'dashboard.module.deleted', 'Folder deleted'),
      );
    },
    [selectedPid, withBusy, t],
  );

  const handleMove = useCallback(
    async (pid: string, targetParentPid: string | null) => {
      await withBusy(
        async () => {
          await dashboardModuleService.move(pid, targetParentPid);
        },
        tr(t, 'dashboard.module.moved', 'Folder moved'),
      );
    },
    [withBusy, t],
  );

  const handleAssign = useCallback(
    async (dashboardPid: string) => {
      await withBusy(
        async () => {
          await dashboardService.update(dashboardPid, { modulePid: selectedPid });
        },
        tr(t, 'dashboard.module.assigned', 'Dashboard moved into folder'),
      );
    },
    [selectedPid, withBusy, t],
  );

  const toggleExpand = useCallback((pid: string) => {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(pid)) {
        next.delete(pid);
      } else {
        next.add(pid);
      }
      return next;
    });
  }, []);

  const renderNodes = (nodes: DashboardModuleNode[], depth: number) =>
    nodes.map((node) => {
      const hasChildren = (node.children ?? []).length > 0;
      const isExpanded = expanded.has(node.pid);
      return (
        <li key={node.pid} data-testid={`module-node-${node.pid}`}>
          <div
            className={`group flex items-center gap-1 rounded px-1 py-1 ${
              selectedPid === node.pid ? 'bg-blue-50' : 'hover:bg-gray-50'
            }`}
            style={{ paddingLeft: depth * 16 + 4 }}
          >
            <button
              type="button"
              className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-gray-400 hover:text-gray-600"
              data-testid={`module-expand-${node.pid}`}
              onClick={() => hasChildren && toggleExpand(node.pid)}
              aria-label={hasChildren && !isExpanded ? 'expand' : 'collapse'}
            >
              {hasChildren ? (
                isExpanded ? (
                  <ChevronDownIcon className="h-4 w-4" />
                ) : (
                  <ChevronRightIcon className="h-4 w-4" />
                )
              ) : (
                <FolderIcon className="h-4 w-4" />
              )}
            </button>
            <button
              type="button"
              className="flex flex-1 items-center gap-1 truncate text-left text-sm text-gray-700"
              data-testid={`module-select-${node.pid}`}
              onClick={() => {
                setSelectedPid(node.pid);
                if (hasChildren) toggleExpand(node.pid);
              }}
            >
              <span className="truncate">{node.name}</span>
              <span
                className="ml-auto shrink-0 rounded-full bg-gray-100 px-1.5 text-xs text-gray-500"
                data-testid={`module-count-${node.pid}`}
              >
                {node.dashboardCount ?? 0}
              </span>
            </button>
            <span className="relative z-40 flex shrink-0 gap-0.5">
              <button
                type="button"
                className="rounded p-1 text-gray-400 hover:text-blue-600"
                data-testid={`module-rename-${node.pid}`}
                onClick={() => {
                  setRenamingPid(node.pid);
                  setRenameValue(node.name);
                }}
                aria-label="rename"
              >
                <PencilSquareIcon className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                className="rounded p-1 text-gray-400 hover:text-blue-600"
                data-testid={`module-move-${node.pid}`}
                onClick={() => {
                  const target = window.prompt(
                    `${tr(t, 'dashboard.module.move', 'Move to folder (empty = root)')}: ` +
                      folders
                        .filter((folder) => folder.pid !== node.pid)
                        .map((folder) => folder.name)
                        .join(', '),
                    '',
                  );
                  if (target === null) return;
                  const trimmed = target.trim();
                  const targetFolder = trimmed
                    ? folders.find((folder) => folder.name === trimmed && folder.pid !== node.pid)
                    : null;
                  if (trimmed && !targetFolder) {
                    showErrorToast(
                      tr(t, 'dashboard.module.targetNotFound', 'Target folder not found'),
                    );
                    return;
                  }
                  void handleMove(node.pid, targetFolder?.pid ?? null);
                }}
                aria-label="move"
              >
                <ArrowUpRightIcon className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                className="rounded p-1 text-gray-400 hover:text-red-600"
                data-testid={`module-delete-${node.pid}`}
                onClick={() => void handleDelete(node.pid, node.name)}
                aria-label="delete"
              >
                <TrashIcon className="h-3.5 w-3.5" />
              </button>
            </span>
          </div>
          {renamingPid === node.pid && (
            <div className="mb-1 flex items-center gap-1" style={{ paddingLeft: depth * 16 + 28 }}>
              <input
                type="text"
                className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                value={renameValue}
                onChange={(event) => setRenameValue(event.target.value)}
                data-testid={`module-rename-input-${node.pid}`}
              />
              <button
                type="button"
                className="rounded bg-blue-600 px-2 py-1 text-xs text-white"
                data-testid={`module-rename-save-${node.pid}`}
                onClick={() => void handleRenameSubmit(node.pid)}
              >
                {tr(t, 'common.save', 'Save')}
              </button>
            </div>
          )}
          {isExpanded && hasChildren && <ul>{renderNodes(node.children ?? [], depth + 1)}</ul>}
        </li>
      );
    });

  const title = block?.props?.title || tr(t, 'dashboard.module.title', 'Folders');

  return (
    <div
      className="rounded-lg border border-gray-200 bg-white p-4"
      data-testid="dashboard-module-tree"
    >
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-700">{title}</h3>
        <button
          type="button"
          className="inline-flex items-center gap-1 rounded-md bg-gray-100 px-2 py-1 text-xs text-gray-700 hover:bg-gray-200 disabled:opacity-50"
          data-testid="module-create-toggle"
          disabled={busy}
          onClick={() => document.getElementById('dashboard-module-new-name')?.focus()}
        >
          <FolderPlusIcon className="h-4 w-4" />
          {tr(t, 'dashboard.module.newFolder', 'New folder')}
        </button>
      </div>

      <div className="mb-2 flex items-center gap-1">
        <input
          id="dashboard-module-new-name"
          type="text"
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          placeholder={tr(t, 'dashboard.module.newFolderPlaceholder', 'New folder name')}
          value={newFolderName}
          onChange={(event) => setNewFolderName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') void handleCreate();
          }}
          data-testid="module-new-name"
        />
        <button
          type="button"
          className="shrink-0 rounded bg-blue-600 px-2 py-1 text-xs text-white disabled:opacity-50"
          data-testid="module-create-save"
          disabled={busy || !newFolderName.trim()}
          onClick={() => void handleCreate()}
        >
          {tr(t, 'common.save', 'Save')}
        </button>
      </div>
      <p className="mb-2 text-xs text-gray-400">
        {selected
          ? `${tr(t, 'dashboard.module.under', 'Created under')}: ${selected.name}`
          : tr(t, 'dashboard.module.rootHint', 'New folders land at the root')}
      </p>

      {loading && (
        <p className="py-2 text-sm text-gray-400">{tr(t, 'common.loading', 'Loading...')}</p>
      )}
      {error && (
        <div
          className="rounded border border-red-200 bg-red-50 p-2 text-sm text-red-600"
          data-testid="module-tree-error"
        >
          {error}
          <button type="button" className="ml-2 underline" onClick={() => void refresh()}>
            {tr(t, 'dashboard.retry', 'Retry')}
          </button>
        </div>
      )}
      {!loading && !error && folders.length === 0 && (
        <p className="py-2 text-sm text-gray-400" data-testid="module-tree-empty">
          {tr(t, 'dashboard.module.empty', 'No folders yet')}
        </p>
      )}
      <ul>{renderNodes(tree, 0)}</ul>

      {selected && (
        <div className="mt-3 border-t border-gray-100 pt-2">
          <p className="mb-1 text-xs font-medium text-gray-500">
            {tr(t, 'dashboard.module.inFolder', 'Dashboards in this folder')}
          </p>
          {folderDashboards.length === 0 ? (
            <p className="text-xs text-gray-400">
              {tr(t, 'dashboard.module.inFolderEmpty', 'None')}
            </p>
          ) : (
            <ul className="mb-1 text-sm text-gray-700">
              {folderDashboards.map((dashboard) => (
                <li key={dashboard.pid} className="truncate">
                  · {dashboard.title}
                </li>
              ))}
            </ul>
          )}
          {assignable.length > 0 && (
            <select
              className="mt-1 w-full rounded border border-gray-300 px-2 py-1 text-sm"
              value=""
              data-testid="module-assign-select"
              disabled={busy}
              onChange={(event) => {
                const dashboardPid = event.target.value;
                if (dashboardPid) void handleAssign(dashboardPid);
              }}
            >
              <option value="">
                {tr(t, 'dashboard.module.assign', 'Add dashboard to folder...')}
              </option>
              {assignable.map((dashboard) => (
                <option key={dashboard.pid} value={dashboard.pid}>
                  {dashboard.title}
                </option>
              ))}
            </select>
          )}
        </div>
      )}
    </div>
  );
}

export default DashboardModuleTree;
