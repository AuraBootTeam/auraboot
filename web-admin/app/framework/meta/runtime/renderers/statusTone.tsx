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

/** Map a hex color (dicts often store `#10b981` etc.) to the nearest tone by hue. */
function hexToTone(raw: string): StatusTone | null {
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(raw);
  if (!m) return null;
  let h = m[1];
  if (h.length === 3)
    h = h
      .split('')
      .map((c) => c + c)
      .join('');
  const r = parseInt(h.slice(0, 2), 16) / 255;
  const g = parseInt(h.slice(2, 4), 16) / 255;
  const b = parseInt(h.slice(4, 6), 16) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  const l = (max + min) / 2;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  if (s < 0.18) return 'gray'; // desaturated → neutral
  let hue = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  hue *= 60;
  if (hue < 0) hue += 360;
  if (hue < 20 || hue >= 330) return 'red';
  if (hue < 70) return 'amber';
  if (hue < 170) return 'green';
  if (hue < 265) return 'blue';
  return 'gray'; // purple/magenta — no semantic status tone
}

/** Map any status/tag color (name OR hex) to one of the 5 canonical semantic tones. */
export function resolveStatusTone(color: string | undefined | null): StatusTone {
  if (!color) return 'gray';
  const key = String(color).trim().toLowerCase();
  return TONE_BY_NAME[key] ?? hexToTone(key) ?? 'gray';
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
