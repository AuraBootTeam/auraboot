/**
 * DateRelativeField
 *
 * Renders a relative time label (e.g. `today`, `in 3d`, `7d ago`) with urgency
 * styling: due-soon (<7d future) is red+bold, past dates are gray. Distant
 * future dates render with no urgency class. Falls back to em-dash for invalid
 * inputs.
 *
 * NOTE: english labels are demo-stage placeholders pending i18n wrapping.
 * TODO(i18n): swap to LocalizedText keys (`kanban.field.dateRelative.*`).
 */

export interface DateRelativeFieldProps {
  value: string | Date | null | undefined;
}

export function DateRelativeField({ value }: DateRelativeFieldProps) {
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

  let label: string;
  if (diffDays === 0) {
    label = 'today';
  } else if (diffDays > 0) {
    label = `in ${diffDays}d`;
  } else {
    label = `${Math.abs(diffDays)}d ago`;
  }

  return (
    <span data-field-type="date-relative" className={cls}>
      {label}
    </span>
  );
}
