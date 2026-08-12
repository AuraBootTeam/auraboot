import { useCallback, useEffect, useMemo, useState, type ComponentType } from 'react';
import { Eye, Loader2, PenLine, Trash2, UserPlus, UsersRound, X } from 'lucide-react';
import { MemberPicker } from '~/ui/smart/picker/MemberPicker';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { useContributionRegistry } from '~/framework/extensions/use-contribution';
import { ResultHelper } from '~/utils/type';

export interface RecordShareDialogProps {
  open: boolean;
  onClose: () => void;
  resourceCode?: string;
  recordPid?: string;
}

interface ShareEntry {
  pid: string;
  subjectType: string;
  subjectName?: string | null;
  permissionMask: string;
  expiresAt?: string | null;
  createdAt?: string | null;
}

export function RecordShareDialog(props: RecordShareDialogProps) {
  const registry = useContributionRegistry();
  const registration = registry.getRenderer('record-share-dialog');
  const Component = registration?.component as ComponentType<RecordShareDialogProps> | undefined;

  // Advanced organization plugins may replace the OSS member-sharing surface
  // with department/link policies. A standalone OSS installation still gets a
  // complete, honest member share/revoke workflow instead of a dead Share button.
  return Component ? <Component {...props} /> : <MemberRecordShareDialog {...props} />;
}

function MemberRecordShareDialog({
  open,
  onClose,
  resourceCode,
  recordPid,
}: RecordShareDialogProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [shares, setShares] = useState<ShareEntry[]>([]);
  const [subjectPid, setSubjectPid] = useState<string>();
  const [permissionMask, setPermissionMask] = useState('read');
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [removingPid, setRemovingPid] = useState<string>();
  const [pickerVersion, setPickerVersion] = useState(0);

  const permissionOptions = useMemo(
    () => [
      {
        value: 'read',
        title: t('record_share.permission_read_title', undefined, 'View only'),
        description: t(
          'record_share.permission_read_description',
          undefined,
          'Can view this record, but cannot change it',
        ),
        icon: Eye,
      },
      {
        value: 'read,update',
        title: t('record_share.permission_collaborate_title', undefined, 'Collaborate'),
        description: t(
          'record_share.permission_collaborate_description',
          undefined,
          'Can view and update this record together',
        ),
        icon: PenLine,
      },
    ],
    [t],
  );

  const loadShares = useCallback(async () => {
    if (!open || !resourceCode || !recordPid) return;
    setLoading(true);
    try {
      const params = new URLSearchParams({ resourceCode, recordPid });
      const response = await fetch(`/api/record-share?${params.toString()}`);
      const body = await response.json().catch(() => null);
      if (!response.ok || !ResultHelper.isSuccess(body)) {
        throw new Error(body?.message || `HTTP ${response.status}`);
      }
      setShares(Array.isArray(body?.data) ? body.data : []);
    } catch {
      showErrorToast(t('record_share.load_failed', undefined, 'Failed to load collaborators'));
    } finally {
      setLoading(false);
    }
  }, [open, recordPid, resourceCode, showErrorToast, t]);

  useEffect(() => {
    if (!open) return;
    setSubjectPid(undefined);
    setPermissionMask('read');
    void loadShares();
  }, [loadShares, open]);

  const addShare = useCallback(async () => {
    if (!resourceCode || !recordPid || !subjectPid) return;
    setAdding(true);
    try {
      const response = await fetch('/api/record-share', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          resourceCode,
          recordPid,
          subjectType: 'member',
          subjectPid,
          permissionMask,
        }),
      });
      const body = await response.json().catch(() => null);
      if (!response.ok || !ResultHelper.isSuccess(body)) {
        throw new Error(body?.message || `HTTP ${response.status}`);
      }
      showSuccessToast(t('record_share.saved', undefined, 'Collaborator saved'));
      setSubjectPid(undefined);
      setPermissionMask('read');
      setPickerVersion((current) => current + 1);
      await loadShares();
    } catch {
      showErrorToast(t('record_share.save_failed', undefined, 'Failed to save collaborator'));
    } finally {
      setAdding(false);
    }
  }, [
    loadShares,
    permissionMask,
    recordPid,
    resourceCode,
    showErrorToast,
    showSuccessToast,
    subjectPid,
    t,
  ]);

  const removeShare = useCallback(
    async (sharePid: string) => {
      setRemovingPid(sharePid);
      try {
        const response = await fetch(`/api/record-share/${encodeURIComponent(sharePid)}`, {
          method: 'DELETE',
        });
        const body = await response.json().catch(() => null);
        if (!response.ok || !ResultHelper.isSuccess(body)) {
          throw new Error(body?.message || `HTTP ${response.status}`);
        }
        setShares((current) => current.filter((share) => share.pid !== sharePid));
        showSuccessToast(t('record_share.removed', undefined, 'Collaborator removed'));
      } catch {
        showErrorToast(t('record_share.remove_failed', undefined, 'Failed to remove collaborator'));
      } finally {
        setRemovingPid(undefined);
      }
    },
    [showErrorToast, showSuccessToast, t],
  );

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-[2px]"
      data-testid="record-share-dialog"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        aria-labelledby="record-share-title"
        aria-modal="true"
        className="rounded-card bg-panel border-border w-full max-w-2xl overflow-hidden border shadow-2xl"
        role="dialog"
      >
        <header className="border-border bg-subtle flex items-start justify-between gap-4 border-b px-6 py-5">
          <div className="flex min-w-0 items-start gap-3">
            <span className="bg-accent-weak text-accent rounded-control flex h-10 w-10 shrink-0 items-center justify-center">
              <UsersRound className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <h2 className="text-text text-lg font-semibold" id="record-share-title">
                {t('record_share.title', undefined, 'Record collaboration')}
              </h2>
              <p className="text-text-3 mt-1 text-sm">
                {t(
                  'record_share.subtitle',
                  undefined,
                  'Invite tenant members to view or maintain this record with you',
                )}
              </p>
            </div>
          </div>
          <button
            aria-label={t('record_share.close', undefined, 'Close collaboration dialog')}
            className="rounded-control text-text-3 hover:bg-hover hover:text-text flex h-8 w-8 items-center justify-center"
            data-testid="record-share-dialog-close"
            onClick={onClose}
            type="button"
          >
            <X className="h-4 w-4" />
          </button>
        </header>

        <div className="grid gap-6 px-6 py-6 md:grid-cols-[minmax(0,1.05fr)_minmax(0,0.95fr)]">
          <section className="space-y-5">
            <div>
              <div className="mb-3 flex items-center gap-2">
                <UserPlus className="text-accent h-4 w-4" />
                <h3 className="text-text text-sm font-semibold">
                  {t('record_share.add_member', undefined, 'Add collaborator')}
                </h3>
              </div>
              <MemberPicker
                key={pickerVersion}
                label={t('record_share.member_label', undefined, 'Tenant member')}
                onChange={(value) =>
                  setSubjectPid(typeof value === 'string' ? value : value?.[0])
                }
                placeholder={t('record_share.member_placeholder', undefined, 'Choose a member')}
                value={subjectPid}
              />
            </div>

            <fieldset>
              <legend className="text-text mb-3 text-sm font-semibold">
                {t('record_share.permission', undefined, 'Collaboration permission')}
              </legend>
              <div className="grid gap-2">
                {permissionOptions.map((option) => {
                  const selected = permissionMask === option.value;
                  const Icon = option.icon;
                  return (
                    <button
                      aria-pressed={selected}
                      className={`rounded-card flex items-start gap-3 border p-3 text-left transition-colors ${
                        selected
                          ? 'border-accent bg-accent-weak'
                          : 'border-border bg-panel hover:bg-subtle'
                      }`}
                      data-testid={`record-share-permission-${option.value.replace(',', '-')}`}
                      key={option.value}
                      onClick={() => setPermissionMask(option.value)}
                      type="button"
                    >
                      <span
                        className={`rounded-control flex h-8 w-8 shrink-0 items-center justify-center ${
                          selected ? 'bg-accent text-white' : 'bg-subtle text-text-3'
                        }`}
                      >
                        <Icon className="h-4 w-4" />
                      </span>
                      <span className="min-w-0">
                        <span className="text-text block text-sm font-medium">{option.title}</span>
                        <span className="text-text-3 mt-0.5 block text-xs leading-5">
                          {option.description}
                        </span>
                      </span>
                    </button>
                  );
                })}
              </div>
            </fieldset>

            <button
              className="rounded-control bg-accent hover:bg-accent-hover flex w-full items-center justify-center gap-2 px-4 py-2.5 text-sm font-semibold text-white shadow-sm disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="record-share-add-btn"
              disabled={adding || !subjectPid || !resourceCode || !recordPid}
              onClick={() => void addShare()}
              type="button"
            >
              {adding ? <Loader2 className="h-4 w-4 animate-spin" /> : <UserPlus className="h-4 w-4" />}
              {adding
                ? t('record_share.saving', undefined, 'Saving…')
                : t('record_share.save', undefined, 'Save collaborator')}
            </button>
          </section>

          <section className="border-border md:border-l md:pl-6">
            <div className="mb-3 flex items-center justify-between gap-3">
              <h3 className="text-text text-sm font-semibold">
                {t('record_share.current_members', undefined, 'Current collaborators')}
              </h3>
              <span className="rounded-pill bg-subtle text-text-2 px-2 py-0.5 text-xs font-medium">
                {shares.length}
              </span>
            </div>
            {loading ? (
              <div className="text-text-3 flex items-center justify-center gap-2 py-12 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" />
                {t('common.loading', undefined, 'Loading…')}
              </div>
            ) : shares.length === 0 ? (
              <div
                className="rounded-card border-border bg-subtle flex flex-col items-center border border-dashed px-5 py-10 text-center"
                data-testid="record-share-empty"
              >
                <UsersRound className="text-text-3 h-8 w-8" />
                <p className="text-text-2 mt-3 text-sm font-medium">
                  {t('record_share.empty_title', undefined, 'No collaborators yet')}
                </p>
                <p className="text-text-3 mt-1 text-xs leading-5">
                  {t(
                    'record_share.empty_description',
                    undefined,
                    'Choose a member and permission to start collaborating',
                  )}
                </p>
              </div>
            ) : (
              <div className="space-y-2" data-testid="record-share-list">
                {shares.map((share) => {
                  const canEdit = share.permissionMask.split(',').includes('update');
                  return (
                    <div
                      className="rounded-card border-border bg-panel flex items-center justify-between gap-3 border px-3 py-3"
                      data-testid={`record-share-row-${share.pid}`}
                      key={share.pid}
                    >
                      <div className="min-w-0">
                        <p className="text-text truncate text-sm font-medium">
                          {share.subjectName ||
                            t('record_share.unavailable_member', undefined, 'Unavailable member')}
                        </p>
                        <span
                          className={`rounded-pill mt-1.5 inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium ${
                            canEdit
                              ? 'bg-status-green-bg text-status-green'
                              : 'bg-status-blue-bg text-status-blue'
                          }`}
                        >
                          {canEdit ? <PenLine className="h-3 w-3" /> : <Eye className="h-3 w-3" />}
                          {canEdit
                            ? t('record_share.permission_collaborate_title', undefined, 'Collaborate')
                            : t('record_share.permission_read_title', undefined, 'View only')}
                        </span>
                      </div>
                      <button
                        aria-label={t('record_share.remove', undefined, 'Remove collaborator')}
                        className="rounded-control text-status-red hover:bg-status-red-bg flex h-8 w-8 shrink-0 items-center justify-center disabled:opacity-50"
                        data-testid={`record-share-remove-${share.pid}`}
                        disabled={removingPid === share.pid}
                        onClick={() => void removeShare(share.pid)}
                        type="button"
                      >
                        {removingPid === share.pid ? (
                          <Loader2 className="h-4 w-4 animate-spin" />
                        ) : (
                          <Trash2 className="h-4 w-4" />
                        )}
                      </button>
                    </div>
                  );
                })}
              </div>
            )}
          </section>
        </div>
      </section>
    </div>
  );
}

export default RecordShareDialog;
