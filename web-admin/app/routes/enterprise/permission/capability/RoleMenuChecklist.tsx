import { useState, useEffect, useRef } from 'react';
import { Link } from 'react-router';
import { ChevronRightIcon, ChevronDownIcon, LockClosedIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import type { RoleNavigationSelection, RoleNavigationChoice } from './roleNavigationService';
interface Props {
  selection: RoleNavigationSelection | undefined;
  selected: string[];
  disabled: boolean;
  onChange: (pids: string[]) => void;
  onPreview: (menuPid?: string) => void;
  previewAllowed?: Record<string, boolean> | null;
  previewFailed?: boolean;
}
function GroupCheckbox({
  checked,
  partial,
  disabled,
  label,
  onChange,
}: {
  checked: boolean;
  partial: boolean;
  disabled: boolean;
  label: string;
  onChange: () => void;
}) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = partial;
  }, [partial]);
  return (
    <input
      ref={ref}
      type="checkbox"
      checked={checked}
      disabled={disabled}
      aria-label={label}
      onChange={onChange}
      className="h-4 w-4 rounded text-blue-600"
    />
  );
}
export default function RoleMenuChecklist({
  selection,
  selected,
  disabled,
  onChange,
  onPreview,
  previewAllowed,
  previewFailed = false,
}: Props) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const menus = selection?.menus ?? [];
  const selectedSet = new Set(selected);
  function descendants(pid: string, seen = new Set<string>()): string[] {
    if (seen.has(pid)) return [];
    seen.add(pid);
    return menus
      .filter((m) => m.parentPid === pid)
      .flatMap((m) => (m.directory ? descendants(m.pid, seen) : [m.pid]));
  }
  function toggle(pids: string[]) {
    const all = pids.every((pid) => selectedSet.has(pid));
    onChange(
      all ? selected.filter((pid) => !pids.includes(pid)) : [...new Set([...selected, ...pids])],
    );
  }
  function title(m: RoleNavigationChoice) {
    return m.name || t('roleNavigation.removed', undefined, 'Removed menu');
  }
  function leaf(m: RoleNavigationChoice, depth = 0) {
    return (
      <label
        key={m.pid}
        data-testid={`role-menu-row-${m.pid}`}
        className="flex min-h-10 items-center gap-2 py-2 pr-3 text-sm"
        style={{ paddingLeft: 12 + depth * 20 }}
      >
        <input
          data-testid={`role-menu-${m.pid}`}
          type="checkbox"
          checked={selectedSet.has(m.pid)}
          onChange={() => toggle([m.pid])}
          disabled={disabled}
          className="h-4 w-4 rounded text-blue-600"
        />
        <span className="min-w-0 flex-1 truncate" title={title(m)}>
          {title(m)}
        </span>
        {!m.enabled && (
          <span className="text-text-2 text-xs">
            {t('roleNavigation.disabled', undefined, 'Unavailable globally')}
          </span>
        )}
      </label>
    );
  }
  function branch(parent: string | null, depth = 0, seen = new Set<string>()): React.ReactNode {
    return menus
      .filter((m) => (m.parentPid ?? null) === parent)
      .map((m) => {
        if (seen.has(m.pid)) return null;
        if (!m.directory) return leaf(m, depth);
        const children = descendants(m.pid);
        const n = children.filter((pid) => selectedSet.has(pid)).length;
        const opened = expanded.has(m.pid);
        return (
          <div key={m.pid} className="border-border border-b last:border-b-0">
            <div
              className="flex min-h-11 items-center gap-2 pr-3 text-sm"
              style={{ paddingLeft: 12 + depth * 20 }}
            >
              <button
                type="button"
                data-testid={`role-menu-expand-${m.pid}`}
                aria-label={title(m)}
                aria-expanded={opened}
                onClick={() =>
                  setExpanded((old) => {
                    const next = new Set(old);
                    next.has(m.pid) ? next.delete(m.pid) : next.add(m.pid);
                    return next;
                  })
                }
              >
                {opened ? (
                  <ChevronDownIcon className="h-4 w-4" />
                ) : (
                  <ChevronRightIcon className="h-4 w-4" />
                )}
              </button>
              <GroupCheckbox
                checked={children.length > 0 && n === children.length}
                partial={n > 0 && n < children.length}
                disabled={disabled || !children.length}
                label={title(m)}
                onChange={() => toggle(children)}
              />
              <span className="min-w-0 flex-1 truncate font-medium">{title(m)}</span>
              <span className="text-text-2 text-xs">
                {n}/{children.length}
              </span>
            </div>
            {opened && branch(m.pid, depth + 1, new Set([...seen, m.pid]))}
          </div>
        );
      });
  }
  const preview = menus.filter((m) => !m.directory && m.enabled && selectedSet.has(m.pid));
  const previewGroups = new Map<string, { name: string; entries: RoleNavigationChoice[] }>();
  for (const item of preview) {
    let parent = menus.find((menu) => menu.pid === item.parentPid);
    const seen = new Set<string>([item.pid]);
    while (parent?.parentPid && !seen.has(parent.pid)) {
      seen.add(parent.pid);
      const ancestor = menus.find((menu) => menu.pid === parent!.parentPid);
      if (!ancestor || seen.has(ancestor.pid)) break;
      parent = ancestor;
    }
    const key = parent?.pid ?? '';
    if (!previewGroups.has(key))
      previewGroups.set(key, {
        name: parent ? title(parent) : t('rbacExperience.otherEntries', undefined, 'Other entries'),
        entries: [],
      });
    previewGroups.get(key)!.entries.push(item);
  }
  if (!selection)
    return (
      <p role="alert" className="text-text-2 text-sm">
        {t(
          'roleNavigation.unavailable',
          undefined,
          'Role navigation is unavailable. Existing assignments are preserved.',
        )}
      </p>
    );
  return (
    <section data-testid="role-menu-checklist" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-text-1 text-sm font-medium">
          {t('roleNavigation.title', undefined, 'Select visible menus for this role')}
        </h3>
        <Link to="/settings/navigation" className="text-accent text-xs underline">
          {t('roleNavigation.structure', undefined, 'Manage menu structure')}
        </Link>
      </div>
      <p className="bg-subtle text-text-2 rounded-control px-3 py-2 text-xs">
        {t(
          'roleNavigation.help',
          undefined,
          'Selection controls navigation only. Business permissions, record scopes and fields remain independent.',
        )}
      </p>
      {selection.mode === 'legacy' && (
        <div
          data-testid="role-menu-legacy"
          className="rounded-control border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900"
        >
          <p>
            {t(
              'roleNavigation.legacy',
              undefined,
              'This role follows legacy navigation rules. Confirm the current selection to manage its menus independently.',
            )}
          </p>
          <button
            type="button"
            data-testid="role-menu-adopt"
            disabled={disabled}
            onClick={() => onChange([...selected])}
            className="mt-2 underline"
          >
            {t('roleNavigation.adopt', undefined, 'Confirm current menu selection')}
          </button>
        </div>
      )}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_190px]">
        <div className="border-border overflow-hidden rounded-lg border">
          <div className="bg-subtle border-border flex flex-wrap items-center justify-between gap-2 border-b p-3">
            <input
              data-testid="role-menu-search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              aria-label={t('roleNavigation.search', undefined, 'Search menus')}
              placeholder={t('roleNavigation.search', undefined, 'Search menus')}
              className="border-border bg-panel rounded-control min-w-0 flex-1 border px-2 py-1.5 text-xs"
            />
            <span className="text-text-2 text-xs">
              {t(
                'roleNavigation.selected',
                { count: selected.length },
                `${selected.length} selected`,
              )}
            </span>
          </div>
          {query
            ? menus
                .filter((m) => !m.directory && title(m).toLowerCase().includes(query.toLowerCase()))
                .map((m) => leaf(m))
            : branch(null)}
          {query &&
            !menus.some(
              (m) => !m.directory && title(m).toLowerCase().includes(query.toLowerCase()),
            ) && (
              <p className="text-text-2 p-6 text-center text-sm">
                {t('roleNavigation.emptySearch', undefined, 'No matching menus')}
              </p>
            )}
        </div>
        <aside className="border-border bg-subtle self-start rounded-lg border p-3">
          <h3 className="text-text-1 text-sm font-medium">
            {t('roleNavigation.previewTitle', undefined, 'Navigation preview')}
          </h3>
          <p className="text-text-2 mt-1 text-xs">
            {t('roleNavigation.currentDraft', undefined, 'Current role draft')}
          </p>
          {previewAllowed === null && (
            <p role={previewFailed ? 'alert' : 'status'} className="text-text-2 mt-2 text-xs">
              {t(
                previewFailed
                  ? 'rbacExperience.previewUnverified'
                  : 'rbacExperience.previewComputing',
                undefined,
                previewFailed
                  ? 'Draft access could not be verified. Open user preview to retry.'
                  : 'Calculating draft access…',
              )}
            </p>
          )}
          <div className="mt-3 max-h-[46vh] space-y-1 overflow-y-auto">
            {[...previewGroups].map(([key, group]) => (
              <section key={key} data-testid="role-preview-group" className="mt-4 first:mt-0">
                <h4 className="text-text-2 mb-1 text-xs font-medium">{group.name}</h4>
                {group.entries.map((m) => (
                  <button
                    type="button"
                    key={m.pid}
                    data-testid={`role-preview-entry-${m.pid}`}
                    data-business-access={
                      (previewAllowed === undefined
                        ? m.businessAllowed
                        : previewAllowed?.[m.pid]) === undefined
                        ? 'unverified'
                        : (
                              previewAllowed === undefined
                                ? m.businessAllowed
                                : previewAllowed?.[m.pid]
                            )
                          ? 'allowed'
                          : 'denied'
                    }
                    onClick={() => onPreview(m.pid)}
                    className="bg-panel text-text-1 hover:bg-hover focus-visible:ring-accent flex w-full items-center justify-between gap-2 rounded px-2 py-2 text-start text-xs focus-visible:ring-2"
                  >
                    <span className="min-w-0 truncate" title={title(m)}>
                      {title(m)}
                    </span>
                    {(previewAllowed === undefined
                      ? m.businessAllowed
                      : previewAllowed?.[m.pid]) === undefined ? (
                      <span className="text-text-2">…</span>
                    ) : !(previewAllowed === undefined
                        ? m.businessAllowed
                        : previewAllowed?.[m.pid]) ? (
                      <LockClosedIcon className="text-text-2 h-3.5 w-3.5 shrink-0" />
                    ) : (
                      <ChevronRightIcon className="h-3.5 w-3.5 shrink-0" />
                    )}
                  </button>
                ))}
              </section>
            ))}
            {!preview.length && (
              <p className="text-text-2 py-5 text-center text-xs">
                {t('roleNavigation.empty', undefined, 'No visible entries')}
              </p>
            )}
          </div>
          <p className="text-text-2 mt-4 text-xs">
            {t(
              'roleNavigation.lockHelp',
              undefined,
              'A lock indicates missing current business access. Preview the user to include draft changes.',
            )}
          </p>
          <button
            type="button"
            onClick={() => onPreview()}
            className="text-accent mt-3 text-xs underline"
          >
            {t('roleNavigation.userPreview', undefined, 'Preview as a user')}
          </button>
        </aside>
      </div>
    </section>
  );
}
