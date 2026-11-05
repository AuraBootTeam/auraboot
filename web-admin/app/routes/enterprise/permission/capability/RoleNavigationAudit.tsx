import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { roleNavigationService } from './roleNavigationService';
type Audit = Awaited<ReturnType<typeof roleNavigationService.audit>>;
export default function RoleNavigationAudit({ rolePid }: { rolePid: string }) {
  const { t } = useI18n();
  const [rows, setRows] = useState<Audit | null>(null),
    [error, setError] = useState(false),
    [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setRows(null);
    setError(false);
    roleNavigationService
      .audit(rolePid)
      .then((v) => {
        if (active) setRows(v);
      })
      .catch(() => {
        if (active) setError(true);
      });
    return () => {
      active = false;
    };
  }, [rolePid, attempt]);
  return (
    <section data-testid="role-navigation-audit" className="space-y-4">
      <h3 className="text-text-1 text-sm font-medium">
        {t('roleChanges.title', undefined, 'Role authorization changes')}
      </h3>
      {error ? (
        <div role="alert">
          <p>{t('roleChanges.error', undefined, 'Could not load role authorization changes.')}</p>
          <button
            type="button"
            onClick={() => setAttempt((a) => a + 1)}
            className="text-accent underline"
          >
            {t('common.retry', undefined, 'Retry')}
          </button>
        </div>
      ) : rows === null ? (
        <p role="status">{t('common.loading', undefined, 'Loading…')}</p>
      ) : rows.length === 0 ? (
        <p className="text-text-2 text-sm">
          {t('roleChanges.empty', undefined, 'No authorization changes for this role yet.')}
        </p>
      ) : (
        rows.map((row) => (
          <article key={row.pid} className="border-border border-b pb-4">
            <div className="flex flex-wrap justify-between gap-2 text-sm">
              <span>
                {row.changes?.length
                  ? t('roleChanges.updated', undefined, 'Updated role authorization')
                  : t('roleNavigation.menuAssignment', undefined, 'Updated visible menus')}
              </span>
              <time className="text-text-2 text-xs">
                {new Date(row.createdAt).toLocaleString()}
              </time>
            </div>
            <p className="text-text-2 mt-2 text-xs">
              {row.actor || t('navigation.audit.removed', undefined, 'Removed account')}
            </p>
            {row.previousMode === 'legacy' && (
              <p className="text-text-2 mt-1 text-xs">
                {t(
                  'roleNavigation.adopted',
                  undefined,
                  'Confirmed legacy projection and switched to independent assignments.',
                )}
              </p>
            )}
            {row.addedMenus.length > 0 && (
              <p className="text-text-2 mt-1 text-xs">
                {t('roleNavigation.added', undefined, 'Added')}:{' '}
                {row.addedMenus
                  .map((n) => n || t('roleNavigation.removed', undefined, 'Removed menu'))
                  .join('、')}
              </p>
            )}
            {row.removedMenus.length > 0 && (
              <p className="text-text-2 mt-1 text-xs">
                {t('roleNavigation.removedEntries', undefined, 'Removed')}:{' '}
                {row.removedMenus
                  .map((n) => n || t('roleNavigation.removed', undefined, 'Removed menu'))
                  .join('、')}
              </p>
            )}
            {row.changes?.map((change, index) => (
              <p key={index} className="text-text-2 mt-2 text-xs">
                {t('roleChanges.kind.' + change.kind, undefined, 'Authorization')} {change.target}:{' '}
                {t(
                  'roleChanges.value.' + (change.before ?? 'not_configured'),
                  undefined,
                  change.before ??
                    t('roleChanges.value.not_configured', undefined, 'Not configured'),
                )}{' '}
                →{' '}
                {t(
                  'roleChanges.value.' + (change.after ?? 'not_configured'),
                  undefined,
                  change.after ??
                    t('roleChanges.value.not_configured', undefined, 'Not configured'),
                )}
              </p>
            ))}
          </article>
        ))
      )}
    </section>
  );
}
