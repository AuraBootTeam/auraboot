import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import ConfirmDialog from '~/ui/ConfirmDialog';
import {
  roleNavigationService,
  type EntryRoles,
} from '~/routes/enterprise/permission/capability/roleNavigationService';
import { AuthorizationConflict } from '~/routes/enterprise/permission/capability/authorizationDraftService';
export default function NavigationRoleAssignment({
  menuPid,
  disabled,
}: {
  menuPid: string;
  disabled: boolean;
}) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [snapshot, setSnapshot] = useState<EntryRoles | null>(null),
    [selected, setSelected] = useState<string[]>([]),
    [open, setOpen] = useState(false),
    [error, setError] = useState(false),
    [saving, setSaving] = useState(false),
    [conflict, setConflict] = useState(false),
    [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setSnapshot(null);
    setError(false);
    setOpen(false);
    setConflict(false);
    roleNavigationService
      .entryRoles(menuPid)
      .then((v) => {
        if (active) {
          setSnapshot(v);
          setSelected(v.roles.filter((r) => r.selected).map((r) => r.pid));
        }
      })
      .catch(() => {
        if (active) setError(true);
      });
    return () => {
      active = false;
    };
  }, [menuPid, attempt]);
  async function save() {
    if (!snapshot || saving || conflict) return;
    setSaving(true);
    try {
      const next = await roleNavigationService.saveEntryRoles(menuPid, snapshot.revision, selected);
      setSnapshot(next);
      setOpen(false);
      showSuccessToast(
        t(
          'roleNavigation.assignSaved',
          undefined,
          'Menu roles saved. Business permissions are unchanged.',
        ),
      );
      window.dispatchEvent(new CustomEvent('menu:refresh'));
    } catch (e) {
      if (e instanceof AuthorizationConflict) setConflict(true);
      showErrorToast(
        t(
          'roleNavigation.assignError',
          undefined,
          'Could not save menu roles. Your selection is preserved.',
        ),
      );
    } finally {
      setSaving(false);
    }
  }
  return (
    <section
      data-testid="navigation-visible-roles"
      data-menu-pid={menuPid}
      className="border-border mt-5 space-y-3 border-t pt-5"
    >
      <div className="flex justify-between gap-3">
        <h3 className="text-text-1 text-sm font-medium">
          {t('roleNavigation.visibleRoles', undefined, 'Visible roles')}
        </h3>
        <button
          type="button"
          data-testid="navigation-roles-configure"
          disabled={disabled || !snapshot}
          onClick={() => {
            setSelected(snapshot!.roles.filter((r) => r.selected).map((r) => r.pid));
            setOpen(true);
          }}
          className="text-accent text-xs underline disabled:opacity-50"
        >
          {t('roleNavigation.configureRoles', undefined, 'Configure roles')}
        </button>
      </div>
      {error ? (
        <div role="alert" className="text-text-2 text-xs">
          <p>{t('roleNavigation.rolesError', undefined, 'Could not load visible roles.')}</p>
          <button
            type="button"
            onClick={() => setAttempt((a) => a + 1)}
            className="text-accent underline"
          >
            {t('common.retry', undefined, 'Retry')}
          </button>
        </div>
      ) : !snapshot ? (
        <p role="status" className="text-text-2 text-xs">
          {t('common.loading', undefined, 'Loading…')}
        </p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {snapshot.roles
            .filter((r) => r.selected)
            .map((r) => (
              <span key={r.pid} className="bg-subtle text-text-2 rounded px-2 py-1 text-xs">
                {r.name}
                {r.mode === 'legacy'
                  ? ` · ${t('roleNavigation.historical', undefined, 'Legacy')}`
                  : ''}
              </span>
            ))}
          {!snapshot.roles.some((r) => r.selected) && (
            <p className="text-text-2 text-xs">
              {t('roleNavigation.noRoles', undefined, 'Not assigned to any role')}
            </p>
          )}
        </div>
      )}
      <p className="text-text-2 text-xs">
        {t(
          'roleNavigation.globalHelp',
          undefined,
          'Role assignment and global enabled status determine visibility. Target pages retain their own business authorization.',
        )}
      </p>
      <ConfirmDialog
        confirmDisabled={saving || conflict}
        cancelDisabled={saving}
        open={open}
        title={t('roleNavigation.visibleRoles', undefined, 'Visible roles')}
        confirmText={t('common.save', undefined, 'Save')}
        onConfirm={() => {
          void save();
        }}
        onCancel={() => {
          if (!saving) setOpen(false);
        }}
        content={
          <div data-testid="navigation-role-assignment" className="space-y-4">
            <p className="text-text-2 text-xs">
              {t(
                'roleNavigation.assignHelp',
                undefined,
                'Only navigation is assigned. Business permissions, record scopes and field policies will not change.',
              )}
            </p>
            {conflict && (
              <div role="alert" className="rounded border border-amber-300 p-3 text-sm">
                <p>
                  {t(
                    'roleNavigation.assignConflict',
                    undefined,
                    'Roles or menus changed. Your selection is preserved; reload before saving.',
                  )}
                </p>
                <button
                  type="button"
                  onClick={() => setAttempt((a) => a + 1)}
                  className="text-accent mt-2 underline"
                >
                  {t('roleNavigation.reload', undefined, 'Reload current assignments')}
                </button>
              </div>
            )}
            <div className="grid gap-3 sm:grid-cols-2">
              {snapshot?.roles.map((r) => (
                <label key={r.pid} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    data-testid={`navigation-role-${r.pid}`}
                    disabled={saving || conflict}
                    checked={selected.includes(r.pid)}
                    onChange={(e) =>
                      setSelected((old) =>
                        e.target.checked ? [...old, r.pid] : old.filter((pid) => pid !== r.pid),
                      )
                    }
                  />
                  {r.name}
                  {!r.enabled && (
                    <span className="text-text-2 text-xs">
                      {t('roleNavigation.roleInactive', undefined, 'Inactive')}
                    </span>
                  )}
                </label>
              ))}
            </div>
          </div>
        }
      />
    </section>
  );
}
