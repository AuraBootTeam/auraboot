import React, { useState } from 'react';
import { Clock3, KeyRound, Loader2 } from 'lucide-react';
import type { AuthoringWriterLease } from './types';

export function AuthoringWriterLeaseNotice({
  lease,
  canTakeover,
  pending = false,
  onTakeover,
}: {
  lease?: AuthoringWriterLease;
  canTakeover: boolean;
  pending?: boolean;
  onTakeover: (reason: string) => Promise<void> | void;
}) {
  const [reason, setReason] = useState('');
  if (!lease || lease.status === 'OWNED') return null;

  const expired = lease.status === 'EXPIRED';
  const sameActor = lease.status === 'HELD_BY_OTHER_SESSION';
  const title = expired
    ? 'Writer lease 已过期'
    : sameActor
      ? '当前账号的另一个会话持有编辑权'
      : '另一位管理员正在编辑此 ChangeSet';

  return (
    <div
      className="border-status-amber bg-status-amber-bg text-status-amber rounded-md border px-3 py-3 text-sm"
      data-testid="authoring-writer-lease-notice"
      role="status"
    >
      <div className="flex items-start gap-2">
        <Clock3 className="mt-0.5 h-4 w-4 shrink-0" />
        <div>
          <div className="font-semibold">{title}</div>
          <div className="mt-1 text-xs">
            当前会话只读，未保存内容不会被清空。租约版本 r{lease.revision}，到期时间{' '}
            {formatLeaseTime(lease.leasedUntil)}。
          </div>
        </div>
      </div>
      {canTakeover ? (
        <div className="mt-3 flex flex-col gap-2 sm:flex-row">
          <label className="sr-only" htmlFor="authoring-lease-takeover-reason">
            接管原因
          </label>
          <input
            id="authoring-lease-takeover-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={1000}
            placeholder="填写接管原因（必填，将写入审计）"
            className="border-border bg-panel min-h-9 min-w-0 flex-1 rounded-md border px-2 text-sm text-slate-800"
          />
          <button
            type="button"
            disabled={pending || reason.trim().length === 0}
            onClick={() => void onTakeover(reason.trim())}
            className="inline-flex min-h-9 items-center justify-center gap-1.5 rounded-md bg-amber-700 px-3 font-semibold text-white hover:bg-amber-800 disabled:cursor-not-allowed disabled:bg-slate-300"
            data-testid="authoring-writer-lease-takeover"
          >
            {pending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <KeyRound className="h-4 w-4" />
            )}
            {pending ? '正在接管…' : expired ? '重新取得编辑权' : '接管编辑权'}
          </button>
        </div>
      ) : (
        <div className="mt-2 text-xs">如需继续编辑，请联系具备高级设计权限的管理员接管。</div>
      )}
    </div>
  );
}

function formatLeaseTime(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}
