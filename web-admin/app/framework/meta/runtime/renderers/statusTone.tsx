/**
 * Status/tag tone resolution + the 色点 + 文字 (dot + text) cell presentation.
 *
 * Standard §3 / §1.3: list & detail status is rendered as a small semantic-colored
 * DOT next to the label — not a filled `rounded-full` pill. Business color names
 * (success / processing / 各种 dict colors) collapse to the 5 canonical tones.
 */
import React from 'react';

export type StatusTone = 'gray' | 'blue' | 'amber' | 'green' | 'red';

/** Dot background utility per tone (semantic design-system tokens). */
export const STATUS_TONE_DOT: Record<StatusTone, string> = {
  gray: 'bg-status-gray',
  blue: 'bg-status-blue',
  amber: 'bg-status-amber',
  green: 'bg-status-green',
  red: 'bg-status-red',
};

const TONE_BY_NAME: Record<string, StatusTone> = {};
const seed = (tone: StatusTone, names: string[]) => {
  for (const n of names) TONE_BY_NAME[n] = tone;
};
seed('green', [
  'success',
  'green',
  'done',
  'completed',
  'complete',
  'normal',
  'pass',
  'passed',
  'ok',
  'approved',
  'valid',
  'enabled',
  'online',
]);
seed('red', [
  'error',
  'red',
  'danger',
  'failed',
  'fail',
  'rejected',
  'overdue',
  'invalid',
  'expired',
  'offline',
]);
seed('amber', ['warning', 'warn', 'amber', 'yellow', 'orange', 'pending', 'waiting', 'review']);
seed('blue', [
  'info',
  'blue',
  'processing',
  'in_progress',
  'inprogress',
  'active',
  'running',
  'open',
  'primary',
]);
seed('gray', [
  'gray',
  'grey',
  'default',
  'neutral',
  'draft',
  'closed',
  'inactive',
  'none',
  'disabled',
]);

/** Map any status/tag color name to one of the 5 canonical semantic tones. */
export function resolveStatusTone(color: string | undefined | null): StatusTone {
  if (!color) return 'gray';
  return TONE_BY_NAME[String(color).trim().toLowerCase()] ?? 'gray';
}

/** 色点 + 文字 presentation for a status/tag value. */
export function StatusDot({
  tone,
  label,
  className = '',
}: {
  tone: StatusTone;
  label: React.ReactNode;
  className?: string;
}) {
  return (
    <span className={`inline-flex items-center gap-1.5 ${className}`}>
      <span
        className={`rounded-pill h-2 w-2 shrink-0 ${STATUS_TONE_DOT[tone]}`}
        aria-hidden="true"
      />
      <span className="text-text">{label}</span>
    </span>
  );
}
