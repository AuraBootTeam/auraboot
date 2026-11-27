/**
 * DateRelativeField
 *
 * Renders a relative time label (e.g. `today`, `in 3d`, `7d ago`) with urgency
 * styling: due-soon (<7d future) is red+bold, past dates are gray. Distant
 * future dates render with no urgency class. Falls back to em-dash for invalid
 * inputs.
 *
 * The three labels follow the reader's language: Chinese for a Chinese locale,
 * English otherwise. A Chinese opportunity board read `139d ago` on every card
 * until the locale was passed in (CRM journey walkthrough, 2026-11-26).
 */

export interface DateRelativeFieldProps {
  value: string | Date | null | undefined;
  /** The reader's language, e.g. `zh-CN`. Omitted: English. */
  locale?: string;
}

/** The label for a date that is `diffDays` days away from today (negative: past). */
export function relativeDayLabel(diffDays: number, locale?: string): string {
  const chinese = String(locale ?? '').toLowerCase().startsWith('zh');
  const days = Math.abs(diffDays);
  if (diffDays === 0) return chinese ? '今天' : 'today';
  if (diffDays > 0) return chinese ? `${days} 天后` : `in ${days}d`;
  return chinese ? `${days} 天前` : `${days}d ago`;
}

export function DateRelativeField({ value, locale }: DateRelativeFieldProps) {
  if (value === null || value === undefined || value === '') {
    return <span data-field-type="date-relative">—</span>;
  }
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) {
    return <span data-field-type="date-relative">—</span>;
  }

  const diffMs = d.getTime() - Date.now();
  const diffDays = Math.round(diffMs / 86_400_000);

  let cls = '';
  if (diffDays < 0) {
    cls = 'text-gray-500';
  } else if (diffDays < 7) {
    cls = 'text-red-600 font-medium';
  }

  return (
    <span data-field-type="date-relative" className={cls}>
      {relativeDayLabel(diffDays, locale)}
    </span>
  );
}
