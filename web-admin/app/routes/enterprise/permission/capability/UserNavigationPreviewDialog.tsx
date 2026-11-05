import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import ConfirmDialog from '~/ui/ConfirmDialog';
import {
  roleNavigationService,
  type NavigationPreviewOptions,
  type NavigationUserPreview,
  type NavigationPreviewRequest,
} from './roleNavigationService';
import type { AuthorizationDraft } from './authorizationDraftService';
export interface ActiveRoleDraft {
  rolePid: string;
  draft: AuthorizationDraft;
}
export default function UserNavigationPreviewDialog({
  open,
  onClose,
  rolePid,
  draft,
}: {
  open: boolean;
  onClose: () => void;
  rolePid?: string;
  draft?: ActiveRoleDraft | null;
}) {
  const { t } = useI18n();
  const [options, setOptions] = useState<NavigationPreviewOptions | null>(null),
    [selectedRoles, setSelectedRoles] = useState<string[]>([]),
    [member, setMember] = useState(''),
    [result, setResult] = useState<NavigationUserPreview | null>(null),
    [activeMenu, setActiveMenu] = useState(''),
    [failed, setFailed] = useState(false),
    [loading, setLoading] = useState(false),
    [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!open) return;
    let alive = true;
    setOptions(null);
    setResult(null);
    setFailed(false);
    setMember('');
    setSelectedRoles(rolePid ? [rolePid] : []);
    roleNavigationService
      .options()
      .then((v) => {
        if (alive) setOptions(v);
      })
      .catch(() => {
        if (alive) setFailed(true);
      });
    return () => {
      alive = false;
    };
  }, [open, rolePid, attempt]);
  const body: NavigationPreviewRequest = {
    ...(member ? { memberPid: member } : { rolePids: selectedRoles }),
    ...(draft ? { draftRolePid: draft.rolePid, draft: draft.draft } : {}),
  };
  const key = JSON.stringify(body);
  useEffect(() => {
    if (!open || !options) return;
    let alive = true;
    setLoading(true);
    setFailed(false);
    const requested = JSON.parse(key) as NavigationPreviewRequest;
    roleNavigationService
      .preview(requested)
      .then((v) => {
        if (alive) {
          setResult(v);
          setActiveMenu((old) =>
            v.menus.some((m) => m.pid === old) ? old : (v.menus[0]?.pid ?? ''),
          );
          if (v.source === 'member') setSelectedRoles(v.rolePids);
        }
      })
      .catch(() => {
        if (alive) {
          setFailed(true);
          setResult(null);
        }
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [open, options, key]);
  const active = result?.menus.find((m) => m.pid === activeMenu);
  return (
    <ConfirmDialog
      size="wide"
      open={open}
      title={t('roleNavigation.userPreview', undefined, 'Preview as a user')}
      confirmText={t('roleNavigation.finishPreview', undefined, 'Finish preview')}
      onConfirm={onClose}
      onCancel={onClose}
      content={
        <div data-testid="role-navigation-user-preview" className="space-y-4">
          <p className="text-text-2 text-xs">
            {draft
              ? t(
                  'roleNavigation.previewDraft',
                  undefined,
                  'Includes the current role draft. Menu visibility and business access are calculated independently.',
                )
              : t(
                  'roleNavigation.previewSaved',
                  undefined,
                  'Uses the current saved configuration.',
                )}
          </p>
          {failed ? (
            <div role="alert" className="text-sm">
              <p>
                {t(
                  'roleNavigation.previewError',
                  undefined,
                  'Preview failed or the draft is stale. Reload the role before retrying.',
                )}
              </p>
              <button
                type="button"
                onClick={() => setAttempt((a) => a + 1)}
                className="text-accent mt-2 underline"
              >
                {t('common.retry', undefined, 'Retry')}
              </button>
            </div>
          ) : !options ? (
            <p role="status">{t('common.loading', undefined, 'Loading…')}</p>
          ) : (
            <>
              <label className="block">
                <span className="text-text-2 mb-1 block text-xs">
                  {t('roleNavigation.chooseMember', undefined, 'Choose a member or simulate roles')}
                </span>
                <select
                  data-testid="role-preview-member"
                  value={member}
                  onChange={(e) => setMember(e.target.value)}
                  className="border-border bg-panel rounded-control w-full border p-2 text-sm"
                >
                  <option value="">
                    {t('roleNavigation.simulation', undefined, 'Simulated role combination')}
                  </option>
                  {options.members.map((m) => (
                    <option key={m.pid} value={m.pid}>
                      {m.name}
                    </option>
                  ))}
                </select>
              </label>
              <div className="flex flex-wrap gap-2">
                {options.roles.map((r) => (
                  <label
                    key={r.pid}
                    className="border-border rounded-control flex items-center gap-2 border px-2 py-1.5 text-xs"
                  >
                    <input
                      type="checkbox"
                      data-testid={`role-preview-role-${r.pid}`}
                      disabled={!r.enabled || loading}
                      checked={selectedRoles.includes(r.pid)}
                      onChange={(e) => {
                        setMember('');
                        setSelectedRoles((old) =>
                          e.target.checked
                            ? [...new Set([...old, r.pid])]
                            : old.filter((id) => id !== r.pid),
                        );
                      }}
                    />
                    {r.name}
                    {!r.enabled && ` · ${t('roleNavigation.roleInactive', undefined, 'Inactive')}`}
                  </label>
                ))}
              </div>
              {loading ? (
                <p role="status" className="text-text-2 text-sm">
                  {t('roleNavigation.calculating', undefined, 'Calculating effective navigation…')}
                </p>
              ) : (
                <div className="border-border grid min-h-56 overflow-hidden rounded-lg border sm:grid-cols-[160px_minmax(0,1fr)]">
                  <nav
                    className="bg-subtle max-h-80 space-y-1 overflow-y-auto p-3"
                    aria-label={t('roleNavigation.merged', undefined, 'Merged navigation')}
                  >
                    {result?.menus.map((m) => (
                      <button
                        type="button"
                        key={m.pid}
                        data-testid={`role-preview-menu-${m.pid}`}
                        onClick={() => setActiveMenu(m.pid)}
                        className={`rounded-control w-full px-2 py-2 text-left text-xs ${activeMenu === m.pid ? 'bg-panel text-accent font-medium' : 'text-text-2'}`}
                      >
                        {m.name || t('roleNavigation.removed', undefined, 'Removed menu')}
                      </button>
                    ))}
                    {!result?.menus.length && (
                      <p className="text-text-2 text-xs">
                        {t('roleNavigation.empty', undefined, 'No visible entries')}
                      </p>
                    )}
                  </nav>
                  <div
                    className="flex flex-col items-center justify-center gap-3 p-5 text-center"
                    aria-live="polite"
                  >
                    {active ? (
                      <>
                        <h3 className="text-text-1 text-sm font-medium">{active.name}</h3>
                        <p
                          data-testid="role-preview-access"
                          data-allowed={active.businessAllowed}
                          className={`rounded px-2 py-1 text-xs ${active.businessAllowed ? 'bg-green-50 text-green-800' : 'bg-amber-50 text-amber-900'}`}
                        >
                          {active.businessAllowed
                            ? t(
                                'roleNavigation.businessAllowed',
                                undefined,
                                'Business entry permission present',
                              )
                            : t(
                                'roleNavigation.businessDenied',
                                undefined,
                                'Business permission not granted',
                              )}
                        </p>
                        <p className="text-text-2 text-xs">
                          {active.businessAllowed
                            ? t(
                                'roleNavigation.allowedHelp',
                                undefined,
                                'Record scopes, object checks and field restrictions still apply. This preview does not read business records.',
                              )
                            : t(
                                'roleNavigation.deniedHelp',
                                undefined,
                                'This menu can be shown. Selecting it does not grant permission to access the target business.',
                              )}
                        </p>
                      </>
                    ) : (
                      <p className="text-text-2 text-sm">
                        {t('roleNavigation.empty', undefined, 'No visible entries')}
                      </p>
                    )}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      }
    />
  );
}
