import { useCallback, useEffect, useMemo, useState, type ComponentType } from 'react';
import {
  CalendarClock,
  Eye,
  Loader2,
  PenLine,
  Pencil,
  RefreshCw,
  Trash2,
  UserPlus,
  UsersRound,
  X,
} from 'lucide-react';
import { MemberPicker } from '~/ui/smart/picker/MemberPicker';
import { DatePicker } from '~/ui/smart/datetime/DatePicker';
import { DateTime } from '~/ui/DateTime';
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

type ExpiryPreset = 'never' | '7d' | '30d' | 'custom';

function toLocalDateValue(value: string): string {
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
}

function tomorrowLocalDate(): string {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  return toLocalDateValue(date.toISOString());
}

function resolveExpiresAt(preset: ExpiryPreset, customExpiry: string): string | null {
  if (preset === 'never') return null;
  if (preset === 'custom') {
    return customExpiry ? new Date(`${customExpiry}T23:59:59.999`).toISOString() : null;
  }
  const date = new Date();
  date.setDate(date.getDate() + (preset === '7d' ? 7 : 30));
  return date.toISOString();
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
  const [expiryPreset, setExpiryPreset] = useState<ExpiryPreset>('never');
  const [customExpiry, setCustomExpiry] = useState('');
  const [editingShare, setEditingShare] = useState<ShareEntry>();
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [removingPid, setRemovingPid] = useState<string>();
  const [selectedSharePids, setSelectedSharePids] = useState<string[]>([]);
  const [confirmBatchDelete, setConfirmBatchDelete] = useState(false);
  const [removingBatch, setRemovingBatch] = useState(false);
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
    setExpiryPreset('never');
    setCustomExpiry('');
    setEditingShare(undefined);
    setSelectedSharePids([]);
    setConfirmBatchDelete(false);
    void loadShares();
  }, [loadShares, open]);

  const resetEditor = useCallback(() => {
    setSubjectPid(undefined);
    setPermissionMask('read');
    setExpiryPreset('never');
    setCustomExpiry('');
    setEditingShare(undefined);
    setPickerVersion((current) => current + 1);
  }, []);

  const editShare = useCallback((share: ShareEntry) => {
    setEditingShare(share);
    setSubjectPid(undefined);
    setPermissionMask(share.permissionMask.includes('update') ? 'read,update' : 'read');
    if (share.expiresAt) {
      setExpiryPreset('custom');
      setCustomExpiry(toLocalDateValue(share.expiresAt));
    } else {
      setExpiryPreset('never');
      setCustomExpiry('');
    }
  }, []);

  const saveShare = useCallback(async () => {
    if (!resourceCode || !recordPid || (!editingShare && !subjectPid)) return;
    if (expiryPreset === 'custom' && !customExpiry) return;
    setAdding(true);
    try {
      const expiresAt = resolveExpiresAt(expiryPreset, customExpiry);
      const response = await fetch(
        editingShare
          ? `/api/record-share/${encodeURIComponent(editingShare.pid)}`
          : '/api/record-share',
        {
          method: editingShare ? 'PATCH' : 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(
            editingShare
              ? { permissionMask, expiresAt }
              : {
                  resourceCode,
                  recordPid,
                  subjectType: 'member',
                  subjectPid,
                  permissionMask,
                  expiresAt,
                },
          ),
        },
      );
      const body = await response.json().catch(() => null);
      if (!response.ok || !ResultHelper.isSuccess(body)) {
        throw new Error(body?.message || `HTTP ${response.status}`);
      }
      showSuccessToast(
        editingShare
          ? t('record_share.updated', undefined, 'Collaboration access updated')
          : t('record_share.saved', undefined, 'Collaborator saved'),
      );
      resetEditor();
      await loadShares();
    } catch {
      showErrorToast(
        editingShare
          ? t('record_share.update_failed', undefined, 'Failed to update collaboration access')
          : t('record_share.save_failed', undefined, 'Failed to save collaborator'),
      );
    } finally {
      setAdding(false);
    }
  }, [
    loadShares,
    customExpiry,
    editingShare,
    expiryPreset,
    permissionMask,
    recordPid,
    resourceCode,
    showErrorToast,
    showSuccessToast,
    subjectPid,
    t,
    resetEditor,
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

  const removeSelectedShares = useCallback(async () => {
    if (selectedSharePids.length === 0) return;
    setRemovingBatch(true);
    try {
      const response = await fetch('/api/record-share/batch-delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sharePids: selectedSharePids }),
      });
      const body = await response.json().catch(() => null);
      if (!response.ok || !ResultHelper.isSuccess(body)) {
        throw new Error(body?.message || `HTTP ${response.status}`);
      }
      const removed = new Set(selectedSharePids);
      setShares((current) => current.filter((share) => !removed.has(share.pid)));
      setSelectedSharePids([]);
      setConfirmBatchDelete(false);
      showSuccessToast(t('record_share.batch_removed', undefined, 'Collaborators removed'));
    } catch {
      showErrorToast(
        t('record_share.batch_remove_failed', undefined, 'Failed to remove collaborators'),
      );
    } finally {
      setRemovingBatch(false);
    }
  }, [selectedSharePids, showErrorToast, showSuccessToast, t]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4 backdrop-blur-[2px]"
      data-testid="record-share-dialog"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        aria-labelledby="record-share-title"
        aria-modal="true"
        className="rounded-card bg-panel border-border max-h-[92vh] w-full max-w-3xl overflow-hidden border shadow-2xl"
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

        <div className="grid max-h-[calc(92vh-85px)] gap-6 overflow-y-auto px-6 py-6 md:grid-cols-[minmax(0,1.08fr)_minmax(0,0.92fr)]">
          <section className="space-y-5">
            <div>
              <div className="mb-3 flex items-center gap-2">
                <UserPlus className="text-accent h-4 w-4" />
                <h3 className="text-text text-sm font-semibold">
                  {editingShare
                    ? t('record_share.edit_member', undefined, 'Update collaborator')
                    : t('record_share.add_member', undefined, 'Add collaborator')}
                </h3>
              </div>
              {editingShare ? (
                <div
                  className="rounded-card border-border bg-subtle flex items-center gap-3 border px-3 py-3"
                  data-testid="record-share-editing-member"
                >
                  <span className="bg-accent-weak text-accent flex h-9 w-9 items-center justify-center rounded-full text-sm font-semibold">
                    {(editingShare.subjectName || '?').slice(0, 1).toUpperCase()}
                  </span>
                  <div className="min-w-0">
                    <p className="text-text truncate text-sm font-medium">
                      {editingShare.subjectName ||
                        t('record_share.unavailable_member', undefined, 'Unavailable member')}
                    </p>
                    <p className="text-text-3 mt-0.5 text-xs">
                      {t(
                        'record_share.editing_description',
                        undefined,
                        'Change permission or renew access without creating a duplicate',
                      )}
                    </p>
                  </div>
                </div>
              ) : (
                <MemberPicker
                  key={pickerVersion}
                  label={t('record_share.member_label', undefined, 'Tenant member')}
                  onChange={(value) =>
                    setSubjectPid(typeof value === 'string' ? value : value?.[0])
                  }
                  placeholder={t('record_share.member_placeholder', undefined, 'Choose a member')}
                  value={subjectPid}
                />
              )}
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

            <fieldset>
              <legend className="text-text mb-3 flex items-center gap-2 text-sm font-semibold">
                <CalendarClock className="text-accent h-4 w-4" />
                {t('record_share.expiry', undefined, 'Access duration')}
              </legend>
              <div
                className="grid grid-cols-2 gap-2 sm:grid-cols-4"
                data-testid="record-share-expiry-presets"
              >
                {(
                  [
                    ['never', t('record_share.expiry_never', undefined, 'Permanent')],
                    ['7d', t('record_share.expiry_7d', undefined, '7 days')],
                    ['30d', t('record_share.expiry_30d', undefined, '30 days')],
                    ['custom', t('record_share.expiry_custom', undefined, 'Custom')],
                  ] as Array<[ExpiryPreset, string]>
                ).map(([value, label]) => (
                  <button
                    aria-pressed={expiryPreset === value}
                    className={`rounded-control border px-3 py-2 text-xs font-medium transition-colors ${
                      expiryPreset === value
                        ? 'border-accent bg-accent-weak text-accent'
                        : 'border-border bg-panel text-text-2 hover:bg-subtle'
                    }`}
                    data-testid={`record-share-expiry-${value}`}
                    key={value}
                    onClick={() => setExpiryPreset(value)}
                    type="button"
                  >
                    {label}
                  </button>
                ))}
              </div>
              {expiryPreset === 'custom' ? (
                <div className="mt-3" data-testid="record-share-custom-expiry">
                  <DatePicker
                    dateType="date"
                    inline={false}
                    label={t('record_share.expiry_date', undefined, 'Expiry date')}
                    minDate={tomorrowLocalDate()}
                    name="record-share-expiry-date"
                    onChange={(value) => setCustomExpiry(String(value || ''))}
                    required
                    value={customExpiry}
                  />
                </div>
              ) : null}
              <p className="text-text-3 mt-2 text-xs leading-5">
                {expiryPreset === 'never'
                  ? t(
                      'record_share.expiry_never_hint',
                      undefined,
                      'Access remains until the owner removes it',
                    )
                  : t(
                      'record_share.expiry_hint',
                      undefined,
                      'Access ends automatically at the selected time and can be renewed later',
                    )}
              </p>
            </fieldset>

            <div className="flex gap-2">
              {editingShare ? (
                <button
                  className="rounded-control border-border text-text-2 hover:bg-subtle flex items-center justify-center border px-4 py-2.5 text-sm font-medium"
                  data-testid="record-share-edit-cancel"
                  disabled={adding}
                  onClick={resetEditor}
                  type="button"
                >
                  {t('common.cancel', undefined, 'Cancel')}
                </button>
              ) : null}
              <button
                className="rounded-control bg-accent hover:bg-accent-hover flex flex-1 items-center justify-center gap-2 px-4 py-2.5 text-sm font-semibold text-white shadow-sm disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="record-share-add-btn"
                disabled={
                  adding ||
                  (!editingShare && !subjectPid) ||
                  !resourceCode ||
                  !recordPid ||
                  (expiryPreset === 'custom' && !customExpiry)
                }
                onClick={() => void saveShare()}
                type="button"
              >
                {adding ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : editingShare ? (
                  <RefreshCw className="h-4 w-4" />
                ) : (
                  <UserPlus className="h-4 w-4" />
                )}
                {adding
                  ? t('record_share.saving', undefined, 'Saving…')
                  : editingShare
                    ? t('record_share.update', undefined, 'Update access')
                    : t('record_share.save', undefined, 'Save collaborator')}
              </button>
            </div>
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
            {shares.length > 1 ? (
              <div className="border-border mb-3 flex items-center justify-between gap-2 border-b pb-3">
                <label className="text-text-2 flex items-center gap-2 text-xs font-medium">
                  <input
                    checked={selectedSharePids.length === shares.length}
                    data-testid="record-share-select-all"
                    onChange={(event) =>
                      setSelectedSharePids(
                        event.target.checked ? shares.map((share) => share.pid) : [],
                      )
                    }
                    type="checkbox"
                  />
                  {t('record_share.select_all', undefined, 'Select all')}
                </label>
                <button
                  className="rounded-control text-status-red hover:bg-status-red-bg px-3 py-2 text-xs font-semibold disabled:opacity-50"
                  data-testid="record-share-batch-remove"
                  disabled={selectedSharePids.length === 0 || removingBatch}
                  onClick={() => setConfirmBatchDelete(true)}
                  type="button"
                >
                  {t('record_share.remove_selected', undefined, 'Remove selected')} (
                  {selectedSharePids.length})
                </button>
              </div>
            ) : null}
            {confirmBatchDelete ? (
              <div
                className="border-status-red bg-status-red-bg rounded-card mb-3 border p-3"
                data-testid="record-share-batch-confirm"
              >
                <p className="text-text text-sm font-medium">
                  {t(
                    'record_share.batch_confirm',
                    { count: selectedSharePids.length },
                    `Remove ${selectedSharePids.length} selected collaborators?`,
                  )}
                </p>
                <div className="mt-3 flex justify-end gap-2">
                  <button
                    className="rounded-control border-border border px-3 py-2 text-xs font-medium"
                    disabled={removingBatch}
                    onClick={() => setConfirmBatchDelete(false)}
                    type="button"
                  >
                    {t('common.cancel', undefined, 'Cancel')}
                  </button>
                  <button
                    className="rounded-control bg-status-red px-3 py-2 text-xs font-semibold text-white"
                    data-testid="record-share-batch-confirm-ok"
                    disabled={removingBatch}
                    onClick={() => void removeSelectedShares()}
                    type="button"
                  >
                    {t('record_share.confirm_remove', undefined, 'Confirm removal')}
                  </button>
                </div>
              </div>
            ) : null}
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
                  const expired = Boolean(
                    share.expiresAt && new Date(share.expiresAt).getTime() <= Date.now(),
                  );
                  return (
                    <div
                      className="rounded-card border-border bg-panel flex items-center justify-between gap-3 border px-3 py-3"
                      data-testid={`record-share-row-${share.pid}`}
                      key={share.pid}
                    >
                      <input
                        aria-label={t(
                          'record_share.select_member',
                          undefined,
                          'Select collaborator',
                        )}
                        checked={selectedSharePids.includes(share.pid)}
                        data-testid={`record-share-select-${share.pid}`}
                        onChange={(event) =>
                          setSelectedSharePids((current) =>
                            event.target.checked
                              ? [...current, share.pid]
                              : current.filter((pid) => pid !== share.pid),
                          )
                        }
                        type="checkbox"
                      />
                      <div className="min-w-0 flex-1">
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
                            ? t(
                                'record_share.permission_collaborate_title',
                                undefined,
                                'Collaborate',
                              )
                            : t('record_share.permission_read_title', undefined, 'View only')}
                        </span>
                        <div
                          className={`mt-2 flex items-center gap-1 text-xs ${
                            expired ? 'text-status-red' : 'text-text-3'
                          }`}
                          data-testid={`record-share-expiry-status-${share.pid}`}
                        >
                          <CalendarClock className="h-3.5 w-3.5" />
                          {expired ? (
                            <span>{t('record_share.expired', undefined, 'Expired')}</span>
                          ) : share.expiresAt ? (
                            <span className="flex items-center gap-1">
                              {t('record_share.expires_on', undefined, 'Expires')}
                              <DateTime value={share.expiresAt} type="date" />
                            </span>
                          ) : (
                            <span>{t('record_share.expiry_never', undefined, 'Permanent')}</span>
                          )}
                        </div>
                      </div>
                      <div className="flex shrink-0 items-center gap-1">
                        <button
                          aria-label={
                            expired
                              ? t('record_share.renew', undefined, 'Renew collaborator')
                              : t('record_share.edit', undefined, 'Edit collaborator')
                          }
                          className="rounded-control text-accent hover:bg-accent-weak flex h-8 w-8 items-center justify-center"
                          data-testid={`record-share-edit-${share.pid}`}
                          onClick={() => editShare(share)}
                          type="button"
                        >
                          {expired ? (
                            <RefreshCw className="h-4 w-4" />
                          ) : (
                            <Pencil className="h-4 w-4" />
                          )}
                        </button>
                        <button
                          aria-label={t('record_share.remove', undefined, 'Remove collaborator')}
                          className="rounded-control text-status-red hover:bg-status-red-bg flex h-8 w-8 items-center justify-center disabled:opacity-50"
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
