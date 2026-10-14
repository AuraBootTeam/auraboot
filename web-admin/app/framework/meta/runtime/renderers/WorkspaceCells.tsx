import React from 'react';
import type { CellRendererContext } from './CellRendererRegistry';
import { getAvatarColor, getInitials } from '~/ui/smart/picker/avatar-utils';

export interface WorkspaceCellPresentation {
  size?: 'summary';
  kind: 'object' | 'member' | 'progress' | 'deadline' | 'currency';
  currencyCode?: string;
  precision?: number;
  subtitleFields?: string[];
  displayField?: string;
  completedField?: string;
  completedValues?: string[];
}

const internalIdentity = /^(?:[0-9A-HJKMNP-TV-Z]{26}|[0-9a-f]{8}-[0-9a-f-]{27,})$/i;

export function calendarDayDifference(value: string, now: Date, timezone: string): number | null {
  const target = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  if (!target) return null;
  const parts = new Intl.DateTimeFormat('en', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const part = (type: string) => Number(parts.find(p => p.type === type)?.value);
  const deadline = Date.UTC(Number(target[1]), Number(target[2]) - 1, Number(target[3]));
  const today = Date.UTC(part('year'), part('month') - 1, part('day'));
  return Math.round((deadline - today) / 86400000);
}

export function WorkspaceCell({ value, record, column, locale = 'en' }: CellRendererContext) {
  const config = column.presentation as WorkspaceCellPresentation;
  if (!config) throw new Error('Workspace cell requires a presentation configuration.');
  if (config.kind === 'object') {
    const subtitles = (config.subtitleFields || []).map(field => record[`${field}_display`] ?? record[field])
      .filter(item => item != null && !internalIdentity.test(String(item)));
    return <div className="min-w-0"><div className="text-text truncate text-[13px] leading-5 font-semibold" title={String(value ?? '')}>{String(value ?? '')}</div>
      {subtitles.length > 0 && <div className="text-text-3 mt-1 truncate text-[11px] leading-4" title={subtitles.join(' · ')}>{subtitles.join(' · ')}</div>}</div>;
  }
  if (config.kind === 'member') {
    const name = record[config.displayField || `${column.field}_display`] ?? value;
    if (!name || internalIdentity.test(String(name))) return <span className="text-text-3">—</span>;
    const text = String(name);
    return <span className="inline-flex items-center gap-2 text-[12px]"><span aria-hidden="true" className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-[11px]`} style={{ color: getAvatarColor(text), backgroundColor: `${getAvatarColor(text)}15` }}>{/\p{Script=Han}/u.test(text) ? Array.from(text)[0] : getInitials(text)}</span><span className="text-text truncate">{text}</span></span>;
  }
  if (config.kind === 'currency') {
    if (value == null || value === '') return <span className="text-text-3">—</span>;
    const amount = Number(value);
    return Number.isFinite(amount) ? <span className={`text-text tabular-nums ${config.size === 'summary' ? 'text-lg font-semibold' : 'text-[12px]'}`}>{new Intl.NumberFormat(locale, { style: 'currency', currency: config.currencyCode || 'CNY', minimumFractionDigits: config.precision ?? 0, maximumFractionDigits: config.precision ?? 0 }).format(amount)}</span> : <span className="text-text-3">—</span>;
  }
  if (config.kind === 'progress') {
    const progress = Number(value);
    if (value == null || !Number.isFinite(progress) || progress < 0 || progress > 100) return <span className="text-text-3">—</span>;
    return <div className={config.size === 'summary' ? 'w-[120px]' : 'w-[76px]'}><div className={`text-text-2 mb-1.5 tabular-nums ${config.size === 'summary' ? 'text-xl font-semibold' : 'text-[11px]'}`}>{progress}%</div><div role="progressbar" aria-label={locale.startsWith('zh') ? '进度' : 'Progress'} aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress} className="bg-subtle h-1 overflow-hidden rounded-full"><div className={`h-full rounded-full ${progress === 100 ? 'bg-status-green' : 'bg-accent'}`} style={{ width: `${progress}%` }} /></div></div>;
  }
  if (config.kind === 'deadline') {
    if (!value) return <span className="text-text-3">—</span>;
    const text = String(value);
    const days = calendarDayDifference(text, new Date(), column.timezone || 'UTC');
    if (days === null) return <span className="text-text-3">—</span>;
    const completed = config.completedField && config.completedValues?.includes(String(record[config.completedField]));
    const zh = locale.startsWith('zh');
    const hint = completed ? (zh ? '已交付' : 'Delivered') : days < 0 ? (zh ? `逾期 ${-days} 天` : `${-days} days overdue`) : days === 0 ? (zh ? '今天交付' : 'Due today') : (zh ? `还有 ${days} 天` : `${days} days remaining`);
    const date = new Date(text.slice(0, 10) + 'T12:00:00Z');
    return <div className=""><div className={`text-text leading-5 tabular-nums ${config.size === 'summary' ? 'text-sm font-medium' : 'text-[12px]'}`}>{new Intl.DateTimeFormat(locale, { month: zh ? 'long' : 'short', day: '2-digit', timeZone: 'UTC' }).format(date)}</div><div className={`mt-1 text-[10px] leading-4 ${!completed && days <= 7 ? 'text-status-amber' : 'text-text-3'}`}>{hint}</div></div>;
  }
  throw new Error(`Unsupported workspace cell presentation: ${config.kind}`);
}
