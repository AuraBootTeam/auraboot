import { useCallback, useEffect, useState, type ComponentType } from 'react';
import { MemberPicker } from '~/ui/smart/picker/MemberPicker';
import { useToastContext } from '~/contexts/ToastContext';
import { useContributionRegistry } from '~/framework/extensions/use-contribution';

export interface RecordShareDialogProps {
  open: boolean;
  onClose: () => void;
  resourceCode?: string;
  recordPid?: string;
}

interface ShareEntry {
  id: number;
  subjectType: string;
  subjectId?: number | null;
  subjectPid?: string | null;
  permissionMask: string;
  expiresAt?: string | null;
}

const PERMISSION_OPTIONS = [
  { value: 'read', label: 'View only' },
  { value: 'read,update', label: 'View & edit' },
] as const;

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
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [shares, setShares] = useState<ShareEntry[]>([]);
  const [subjectPid, setSubjectPid] = useState<string>();
  const [permissionMask, setPermissionMask] = useState('read');
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [removingId, setRemovingId] = useState<number>();

  const loadShares = useCallback(async () => {
    if (!open || !resourceCode || !recordPid) return;
    setLoading(true);
    try {
      const params = new URLSearchParams({ resourceCode, recordPid });
      const response = await fetch(`/api/record-share?${params.toString()}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const body = await response.json();
      setShares(Array.isArray(body?.data) ? body.data : []);
    } catch {
      showErrorToast('Failed to load record shares');
    } finally {
      setLoading(false);
    }
  }, [open, recordPid, resourceCode, showErrorToast]);

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
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      showSuccessToast('Record shared successfully');
      setSubjectPid(undefined);
      setPermissionMask('read');
      await loadShares();
    } catch {
      showErrorToast('Failed to share record');
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
  ]);

  const removeShare = useCallback(
    async (shareId: number) => {
      setRemovingId(shareId);
      try {
        const response = await fetch(`/api/record-share/${shareId}`, { method: 'DELETE' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        setShares((current) => current.filter((share) => share.id !== shareId));
        showSuccessToast('Share removed');
      } catch {
        showErrorToast('Failed to remove share');
      } finally {
        setRemovingId(undefined);
      }
    },
    [showErrorToast, showSuccessToast],
  );

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      data-testid="record-share-dialog"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        aria-labelledby="record-share-title"
        aria-modal="true"
        className="rounded-card bg-panel border-border w-full max-w-lg border shadow-xl"
        role="dialog"
      >
        <header className="border-border flex items-center justify-between border-b px-6 py-4">
          <h2 className="text-text text-base font-semibold" id="record-share-title">
            Share record
          </h2>
          <button
            aria-label="Close share dialog"
            className="rounded-control text-text-3 hover:bg-hover hover:text-text px-2 py-1"
            data-testid="record-share-dialog-close"
            onClick={onClose}
            type="button"
          >
            ×
          </button>
        </header>

        <div className="space-y-5 px-6 py-5">
          <section className="rounded-card border-border bg-subtle space-y-3 border p-4">
            <h3 className="text-text-2 text-sm font-medium">Add member share</h3>
            <MemberPicker
              label="Share with"
              onChange={(value) => setSubjectPid(typeof value === 'string' ? value : value?.[0])}
              placeholder="Search member..."
              value={subjectPid}
            />
            <label
              className="text-text-2 block text-sm font-medium"
              htmlFor="record-share-permission"
            >
              Permission
            </label>
            <select
              className="rounded-control border-border-strong bg-panel text-text w-full border px-3 py-2 text-sm"
              data-testid="record-share-permission-select"
              id="record-share-permission"
              onChange={(event) => setPermissionMask(event.target.value)}
              value={permissionMask}
            >
              {PERMISSION_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <button
              className="rounded-control bg-accent hover:bg-accent-hover w-full px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="record-share-add-btn"
              disabled={adding || !subjectPid || !resourceCode || !recordPid}
              onClick={() => void addShare()}
              type="button"
            >
              {adding ? 'Sharing…' : 'Share'}
            </button>
          </section>

          <section>
            <h3 className="text-text-2 mb-2 text-sm font-medium">Current shares</h3>
            {loading ? (
              <p className="text-text-3 py-5 text-center text-sm">Loading…</p>
            ) : shares.length === 0 ? (
              <p
                className="rounded-card border-border text-text-3 border border-dashed py-5 text-center text-sm"
                data-testid="record-share-empty"
              >
                Not shared with anyone yet
              </p>
            ) : (
              <div
                className="rounded-card border-border divide-border divide-y border"
                data-testid="record-share-list"
              >
                {shares.map((share) => (
                  <div
                    className="flex items-center justify-between gap-3 px-4 py-3"
                    data-testid={`record-share-row-${share.id}`}
                    key={share.id}
                  >
                    <div className="min-w-0">
                      <p className="text-text truncate text-sm font-medium">
                        {share.subjectPid || `ID: ${share.subjectId}`}
                      </p>
                      <p className="text-text-3 text-xs">{permissionLabel(share.permissionMask)}</p>
                    </div>
                    <button
                      aria-label="Remove share"
                      className="rounded-control text-status-red hover:bg-status-red-bg px-2 py-1 text-sm disabled:opacity-50"
                      data-testid={`record-share-remove-${share.id}`}
                      disabled={removingId === share.id}
                      onClick={() => void removeShare(share.id)}
                      type="button"
                    >
                      {removingId === share.id ? 'Removing…' : 'Remove'}
                    </button>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>
      </section>
    </div>
  );
}

function permissionLabel(mask: string): string {
  return PERMISSION_OPTIONS.find((option) => option.value === mask)?.label ?? mask;
}

export default RecordShareDialog;
